# app/main.py
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from typing import List, Optional
import asyncio, time

from .config import settings
from .ModelRunner import ModelRunner
from .cache import cache

app = FastAPI(title="Jarvis Revisor/Tradutor")

runner = ModelRunner()

# ====== MODELOS DE REQUISIÇÃO/RESPOSTA ======
class TranslateIn(BaseModel):
    text: str = Field(..., description="Texto de entrada")
    src_lang: str = Field("eng_Latn")
    tgt_lang: str = Field("por_Latn")

class TranslateOut(BaseModel):
    translation: str
    cached: bool = False
    latency_ms: int
    model_id: str

class BatchTranslateIn(BaseModel):
    items: List[TranslateIn]

class BatchTranslateOutItem(BaseModel):
    translation: str
    cached: bool
    latency_ms: int

class BatchTranslateOut(BaseModel):
    results: List[BatchTranslateOutItem]
    model_id: str

class GenerateIn(BaseModel):
    prompt: str

class GenerateOut(BaseModel):
    text: str

# ====== STARTUP / SHUTDOWN ======
@app.on_event("startup")
async def on_startup():
    print(f"[ENV] MODEL_ID = {settings.MODEL_ID}")
    print(f"[ENV] DEVICE   = {settings.DEVICE}")
    print(f"[ENV] HF_OFF   = {settings.HF_HUB_OFFLINE}")
    await cache.connect()
    print("[Mongo] conectado.")

@app.on_event("shutdown")
async def on_shutdown():
    await cache.close()

# ====== HEALTH ======
@app.get("/health")
def health():
    return {
        "ok": True,
        "model_id": runner.model_id,
        "device": runner.device,
        "dtype": str(runner.dtype),
        "max_input_tokens": runner.max_input_tokens,
        "max_new_tokens_default": runner.max_new_tokens
    }

# ====== ENDPOINTS EXISTENTES ======
@app.post("/generate", response_model=GenerateOut)
def generate(body: GenerateIn):
    try:
        text = runner.generate(body.prompt)
        return GenerateOut(text=text)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

# ====== NOVO: /translate (single) ======
@app.post("/translate", response_model=TranslateOut)
async def translate(body: TranslateIn):
    key = cache.make_key(body.text, body.src_lang, body.tgt_lang, runner.model_id, runner.max_new_tokens)

    # 1) tenta cache
    cached_doc = await cache.get(key)
    if cached_doc:
        return TranslateOut(
            translation=cached_doc["text"],
            cached=True,
            latency_ms=0,
            model_id=runner.model_id
        )

    # 2) miss → traduz e grava
    t0 = time.perf_counter()
    # como o runner é síncrono, rodamos em threadpool
    loop = asyncio.get_event_loop()
    try:
        translation = await loop.run_in_executor(None, runner.translate, body.text, body.src_lang, body.tgt_lang)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
    dt_ms = int((time.perf_counter() - t0) * 1000)

    # grava cache
    meta = {
        "src_lang": body.src_lang,
        "tgt_lang": body.tgt_lang,
        "model_id": runner.model_id,
        "max_new_tokens": runner.max_new_tokens,
        "latency_ms": dt_ms
    }
    await cache.set(key, translation, meta)

    return TranslateOut(
        translation=translation,
        cached=False,
        latency_ms=dt_ms,
        model_id=runner.model_id
    )

# ====== NOVO: /translate/batch ======
@app.post("/translate/batch", response_model=BatchTranslateOut)
async def translate_batch(body: BatchTranslateIn):
    results: List[BatchTranslateOutItem] = []

    # 1) tenta pegar todos do cache primeiro
    tasks = []
    keys = []
    for it in body.items:
        k = cache.make_key(it.text, it.src_lang, it.tgt_lang, runner.model_id, runner.max_new_tokens)
        keys.append(k)
        tasks.append(cache.get(k))

    cached_docs = await asyncio.gather(*tasks)
    # 2) para misses, roda tradução em paralelo (threadpool)
    loop = asyncio.get_event_loop()
    run_tasks = []
    idx_map = {}

    for idx, (it, doc) in enumerate(zip(body.items, cached_docs)):
        if doc:
            results.append(BatchTranslateOutItem(translation=doc["text"], cached=True, latency_ms=0))
        else:
            idx_map[len(run_tasks)] = idx
            run_tasks.append(loop.run_in_executor(None, runner.translate, it.text, it.src_lang, it.tgt_lang))

    # 3) coleta traduções calculadas e upserts
    if run_tasks:
        outs = await asyncio.gather(*run_tasks, return_exceptions=True)
        upserts = []
        for run_idx, out in enumerate(outs):
            idx = idx_map[run_idx]
            it = body.items[idx]
            if isinstance(out, Exception):
                # falha: devolve erro parcial
                results.append(BatchTranslateOutItem(translation=f"[error] {out}", cached=False, latency_ms=0))
                continue
            # mede latência não batemos aqui; pode medir individualmente se quiser
            results.append(BatchTranslateOutItem(translation=out, cached=False, latency_ms=0))
            # grava cache
            meta = {
                "src_lang": it.src_lang,
                "tgt_lang": it.tgt_lang,
                "model_id": runner.model_id,
                "max_new_tokens": runner.max_new_tokens,
                "latency_ms": 0
            }
            await cache.set(keys[idx], out, meta)

    return BatchTranslateOut(results=results, model_id=runner.model_id)

class NBestIn(BaseModel):
    text: str
    src_lang: str = "eng_Latn"
    tgt_lang: str = "por_Latn"
    k: int = 5

class NBestOut(BaseModel):
    candidates: list[str]

@app.post("/translate/nbest", response_model=NBestOut)
async def translate_nbest(body: NBestIn):
    loop = asyncio.get_event_loop()
    cands = await loop.run_in_executor(None, runner.translate_nbest, body.text, body.src_lang, body.tgt_lang, body.k)
    return NBestOut(candidates=cands)

class NBestIn(BaseModel):
    text: str
    src_lang: str = "eng_Latn"
    tgt_lang: str = "por_Latn"
    k: int = Field(5, ge=1, le=10)

class NBestOut(BaseModel):
    candidates: List[str]
    model_id: str | None = None

@app.post("/translate/nbest", response_model=NBestOut)
async def translate_nbest(body: NBestIn):
    loop = asyncio.get_event_loop()
    try:
        cands = await loop.run_in_executor(None, runner.translate_nbest,
                                           body.text, body.src_lang, body.tgt_lang, body.k)
        # ✅ inclua o model_id na resposta:
        return NBestOut(candidates=cands, model_id=runner.model_id)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))