# worker/app.py
from worker.translate_epub import translate_epub
from worker.translate_txt import translate_txt

from transformers import MarianMTModel, MarianTokenizer  # ou NLLB depois

import os, shutil, uuid
import logging
from fastapi import FastAPI, UploadFile, File, HTTPException


app = FastAPI(title="Translator Worker")

MODEL_NAME = "Helsinki-NLP/opus-mt-tc-big-en-pt"
_tok = None
_model = None

logging.basicConfig(
    level=logging.INFO,  # pode ser DEBUG, INFO, WARNING, ERROR
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)

logger = logging.getLogger("jarvis-tradutor")

def get_model():
    global _tok, _model
    if _tok is None or _model is None:
        _tok = MarianTokenizer.from_pretrained(MODEL_NAME)
        _model = MarianMTModel.from_pretrained(MODEL_NAME)
    return _tok, _model

@app.post("/translate")
async def translate(file: UploadFile = File(...)):
    ext = os.path.splitext(file.filename)[1].lower()
    BASE_DIR = r"C:\Users\souno\Desktop\Projects2025\jarvistradutor"
    uploads = os.path.join(BASE_DIR, "uploads")
    outdir = os.path.join(BASE_DIR, "translated")
    os.makedirs(uploads, exist_ok=True); os.makedirs(outdir, exist_ok=True)

    tmpname = f"{uuid.uuid4()}{ext}"
    src = os.path.join(uploads, tmpname)
    with open(src, "wb") as f:
        shutil.copyfileobj(file.file, f)

    tok, model = get_model()

    try:
        if ext == ".epub":
            out = os.path.abspath(os.path.join(outdir, tmpname.replace(".epub", "_ptbr.epub")))
            translate_epub(src, out, model_name=MODEL_NAME, tok=tok, model=model)
        else:
            out = os.path.abspath(os.path.join(outdir, tmpname.replace(ext, f"_ptbr{ext}")))
            translate_txt(src, out, model_name=MODEL_NAME, tok=tok, model=model)
    except Exception as e:
        logger.exception("Falha ao processar arquivo")
        raise HTTPException(status_code=500, detail=f"Erro ao traduzir: {e}")

    if not os.path.exists(out):
        logger.error("Saída não encontrada: %s", out)
        raise HTTPException(status_code=500, detail=f"Falha ao salvar saída em {out}")

    size = os.path.getsize(out)
    logger.info("Saída OK: %s | size=%d", out, size)
    return {"status": "ok", "outputName": os.path.basename(out), "absPath": out}