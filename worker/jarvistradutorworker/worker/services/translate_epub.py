# worker/services/translate_epub.py
from __future__ import annotations
from ebooklib import epub, ITEM_DOCUMENT
from bs4 import BeautifulSoup, NavigableString
from ..core.config import settings
from ..nlp.loader import load_model
import pysbd
import logging, os, tempfile, shutil, re, csv
from typing import Iterable, List, Dict, Any
from worker.quality.inspector import QualityInspector, QualityThresholds

logger = logging.getLogger("jarvis-tradutor")

# ====== parâmetros de batching/decoding ======
MAX_TOKENS_PER_BATCH = 880
MAX_NEW_TOKENS = 256
NUM_BEAMS = 4
LENGTH_PENALTY = 1.0
REPETITION_PENALTY = 1.05

_cache: dict[str, str] = {}
_tok = _model = _prepare_inputs = _decode_kwargs = None

def get_model_bundle():
    """
    Carrega/lazy-cacheia (tokenizer, model, prepare_inputs, decode_kwargs)
    via o loader que entende Marian / M2M100 / NLLB.
    """
    global _tok, _model, _prepare_inputs, _decode_kwargs
    if _tok is None:
        _tok, _model, _prepare_inputs, _decode_kwargs = load_model(
            settings.model_name,
            settings.src_lang, settings.tgt_lang,
            settings.nllb_src, settings.nllb_tgt,
        )
        _model.eval()
        logger.info("Modelo carregado: %s", settings.model_name)
    return _tok, _model, _prepare_inputs, _decode_kwargs


# ---------- utilidades ----------
_SKIP_TAGS = {"code", "pre", "kbd", "samp", "script", "style"}

def is_visible_text(node: NavigableString) -> bool:
    p = node.parent
    while p is not None:
        if getattr(p, "name", "").lower() in _SKIP_TAGS:
            return False
        p = getattr(p, "parent", None)
    return bool(node.strip())


def estimate_len_tokens(texts: List[str], tok) -> int:
    enc = tok(
        texts,
        add_special_tokens=False,
        padding=False,
        truncation=False,
        return_attention_mask=False,
        return_token_type_ids=False,
    )
    ids = enc["input_ids"]
    return sum(len(x) for x in ids)


def batch_by_tokens(items: List[str], tok, max_tokens: int):
    cur, cur_tokens = [], 0
    for s in items:
        t = estimate_len_tokens([s], tok)
        if not cur:
            cur = [s]; cur_tokens = t
            if cur_tokens >= max_tokens:
                yield cur
                cur, cur_tokens = [], 0
            continue
        if cur_tokens + t <= max_tokens:
            cur.append(s); cur_tokens += t
        else:
            yield cur
            cur, cur_tokens = [s], t
            if cur_tokens >= max_tokens:
                yield cur
                cur, cur_tokens = [], 0
    if cur:
        yield cur


_PT_SPACES = re.compile(r"\s+([,.;:!?])")
_PT_AFTER = re.compile(r"([,.;:!?])\s*")
_MULTI_WS = re.compile(r"\s{2,}")

def postprocess_pt(text: str) -> str:
    t = text
    t = _PT_SPACES.sub(r"\1", t)
    t = _PT_AFTER.sub(r"\1 ", t)
    t = t.replace(" - ", " – ")
    t = t.replace("''", '"').replace("``", '"')
    t = t.replace(" )", ")").replace("( ", "(")
    t = _MULTI_WS.sub(" ", t).strip()
    return t


def translate_segments(segments: List[str]) -> List[str]:
    tok, model, prepare_inputs, decode_kwargs = get_model_bundle()

    out: List[str] = [None] * len(segments)  # type: ignore
    to_translate_idx: List[int] = []
    to_translate_texts: List[str] = []
    for i, s in enumerate(segments):
        key = s.strip()
        hit = _cache.get(key)
        if hit is not None:
            out[i] = hit
        else:
            to_translate_idx.append(i)
            to_translate_texts.append(key)

    if to_translate_texts:
        for batch in batch_by_tokens(to_translate_texts, tok, MAX_TOKENS_PER_BATCH):
            enc, gen_extra = prepare_inputs(batch)
            outputs = model.generate(
                **enc,
                **gen_extra,
                max_new_tokens=MAX_NEW_TOKENS,
                num_beams=NUM_BEAMS,
                length_penalty=LENGTH_PENALTY,
                repetition_penalty=REPETITION_PENALTY,
                early_stopping=True,
            )
            decoded = tok.batch_decode(outputs, **decode_kwargs)
            for _ in decoded:
                idx_global = to_translate_idx.pop(0)
                src_text = to_translate_texts.pop(0)
                pt = postprocess_pt(_)
                out[idx_global] = pt
                _cache[src_text] = pt

    return out  # type: ignore


def normalize_nav_toc_spine(book: epub.EpubBook) -> None:
    # Garante NAV/NCX
    for maker in (epub.EpubNav, epub.EpubNcx):
        try:
            book.add_item(maker())
        except Exception:
            pass

    # Colete todos os documentos XHTML com caminho válido
    docs = [d for d in book.get_items_of_type(ITEM_DOCUMENT) if getattr(d, "file_name", None)]
    # Reconstrói TOC completamente plano, ignorando TOC original
    valid = []
    for doc in docs:
        href = doc.file_name.replace("\\", "/")
        # título simples: usa title se houver; senão, o id ou o nome do arquivo sem extensão
        base_title = getattr(doc, "title", None) or doc.get_id() or os.path.splitext(os.path.basename(href))[0]
        valid.append(epub.Link(href, base_title, doc.get_id()))

    # Se por algum motivo não sobrou nada, evite estourar lá na frente
    if not valid:
        raise RuntimeError("Nenhum documento com file_name válido para montar o TOC.")

    # TOC final e spine em ordem dos docs
    book.toc = tuple(valid)
    book.spine = ["nav"] + docs



def translate_epub(
    src_path: str,
    dst_path: str,
    model_name: str | None = None,  # mantido por compat
    batch_size: int = 12,           # hoje controlamos por tokens
    tok=None,
    model=None,
):
    logger.info("Iniciando tradução do EPUB: %s", src_path)
    try:
        out_dir = os.path.dirname(os.path.abspath(dst_path))
        os.makedirs(out_dir, exist_ok=True)

        # carrega / prepara modelo e inspector
        tok_m, model_m, prepare_inputs, decode_kwargs = get_model_bundle()
        inspector = QualityInspector(
            thresholds=QualityThresholds(
                min_len_ratio=0.6,
                max_len_ratio=1.6,
                max_untranslated_hits=2,
                max_punct_issues=3,
                min_semantic_sim=0.68,
                min_avg_logprob=-2.5,
            ),
            enable_semantic=True,             # desliga se não quiser embeddings
            tokenizer=tok_m,
            model=model_m,
            prepare_inputs=prepare_inputs,
            decode_kwargs=decode_kwargs,
            src_lang=settings.src_lang,
            tgt_lang=settings.tgt_lang,
        )

        # CSV de métricas por parágrafo (opcional)
        quality_rows: List[Dict[str, Any]] = []

        book = epub.read_epub(src_path)
        logger.info("Arquivo EPUB carregado.")

        lang_for_seg = settings.src_lang if settings.src_lang in {"en", "pt"} else "en"
        seg = pysbd.Segmenter(language=lang_for_seg, clean=True)

        docs = list(book.get_items_of_type(ITEM_DOCUMENT))
        logger.info("Total de documentos XHTML: %d", len(docs))

        for item in docs:
            logger.info("Traduzindo doc id=%s file=%s", item.get_id(), item.file_name)
            try:
                soup = BeautifulSoup(item.get_content(), "lxml")
            except Exception:
                soup = BeautifulSoup(item.get_content(), "html.parser")

            text_nodes = [n for n in soup.find_all(string=True) if is_visible_text(n)]
            for node in text_nodes:
                original = node.strip()
                if not original:
                    continue
                sents = seg.segment(original) or []
                if not sents:
                    continue
                translated_sents = translate_segments(sents)
                pt_text = " ".join(translated_sents)
                node.replace_with(pt_text)

                # --- Quality Inspector: avalia e loga ---
                metrics = inspector.analyze_paragraph(original, pt_text)
                # guarda resumão para CSV (opcional)
                quality_rows.append({
                    "doc_id": item.get_id(),
                    "file_name": item.file_name or "",
                    "orig_len": len(original),
                    "pt_len": len(pt_text),
                    "length_ratio": f"{metrics.get('length_ratio', 0):.3f}",
                    "untranslated_hits": metrics.get("untranslated_hits"),
                    "punct_issues": metrics.get("punct_issues"),
                    "semantic_sim": (None if metrics.get("semantic_sim") is None else f"{metrics['semantic_sim']:.3f}"),
                    "avg_logprob": (None if metrics.get("avg_logprob") is None else f"{metrics['avg_logprob']:.3f}"),
                    "lang_detect": metrics.get("lang_detect"),
                    "needs_review": metrics.get("needs_review"),
                    "reasons": ";".join(metrics.get("reasons", [])),
                    # pequenos trechos pra inspecionar (evita CSV gigante)
                    "orig_snippet": (original[:120] + "…") if len(original) > 120 else original,
                    "pt_snippet": (pt_text[:120] + "…") if len(pt_text) > 120 else pt_text,
                })

            item.set_content(str(soup).encode("utf-8"))

        normalize_nav_toc_spine(book)

        fd, tmp_path = tempfile.mkstemp(suffix=".epub")
        os.close(fd)
        logger.info("Gravando EPUB temporário em %s ...", tmp_path)

        try:
            hrefs = []
            for it in book.toc:
                if isinstance(it, epub.Link):
                    hrefs.append(it.href)
                elif isinstance(it, epub.Section):
                    for sub in it:
                        if isinstance(sub, epub.Link):
                            hrefs.append(sub.href)
            logger.info("TOC final (hrefs): %s", hrefs)
        except Exception:
            logger.exception("Falha ao inspecionar TOC para log")

        epub.write_epub(tmp_path, book)

        size = os.path.getsize(tmp_path)
        if size <= 0:
            raise RuntimeError("EPUB gerado com tamanho 0.")
        shutil.move(tmp_path, dst_path)
        logger.info("Tradução concluída. Arquivo salvo em: %s (%d bytes)", dst_path, size)

        # ---- escreve CSV com métricas (opcional; comente se não quiser) ----
        try:
            csv_path = dst_path + ".quality.csv"
            with open(csv_path, "w", newline="", encoding="utf-8") as f:
                writer = csv.DictWriter(f, fieldnames=list(quality_rows[0].keys()) if quality_rows else [])
                if quality_rows:
                    writer.writeheader()
                    writer.writerows(quality_rows)
            logger.info("Relatório de qualidade salvo em: %s (linhas=%d)", csv_path, len(quality_rows))
        except Exception:
            logger.exception("Falha ao salvar CSV de qualidade (opcional).")

    except Exception:
        logger.exception("Falha durante a tradução/salvamento do EPUB")
        raise


def translate_epub_file(src_path: str, dst_path: str) -> None:
    """Wrapper simples para o routes.py"""
    translate_epub(src_path, dst_path)
