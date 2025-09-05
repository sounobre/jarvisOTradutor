# worker/translate_epub.py
from ebooklib import epub, ITEM_DOCUMENT
from bs4 import BeautifulSoup
from transformers import MarianMTModel, MarianTokenizer
import pysbd
import logging, os, tempfile, shutil, re, time
from collections import defaultdict

logger = logging.getLogger("jarvis-tradutor")

# ==== CONFIG DO BATCHING POR TOKENS ====
MAX_TOKENS_PER_BATCH = 320
MAX_SENTS_PER_BATCH  = 32

# === PÓS-PROCESSAMENTO (PT-BR) ===
_punct_fix_re = re.compile(r"\s+([,.;:!?])")
_space_fix_re = re.compile(r"\s{2,}")
_quote_open_re = re.compile(r'``|“|”')
_dash_fix_re = re.compile(r"\s+—\s+")
_space_before_pct_re = re.compile(r"\s+%")
_space_before_quotes_re = re.compile(r'\s+"')

def postprocess_pt(text: str) -> str:
    text = _quote_open_re.sub('"', text)
    text = _punct_fix_re.sub(r"\1", text)
    text = _space_before_pct_re.sub("%", text)
    text = _space_before_quotes_re.sub('"', text)
    text = _dash_fix_re.sub(" — ", text)
    text = (text
            .replace(" .", ".").replace(" ,", ",")
            .replace(" !", "!").replace(" ?", "?")
            .replace(" ;", ";").replace(" :", ":"))
    text = re.sub(r"([.!?])([A-Za-zÀ-ÿ0-9])", r"\1 \2", text)
    return _space_fix_re.sub(" ", text).strip()

def estimate_tokens(tok: MarianTokenizer, text: str) -> int:
    ids = tok(text, add_special_tokens=False,
              return_attention_mask=False,
              return_token_type_ids=False).input_ids
    if isinstance(ids[0], list):
        return sum(len(x) for x in ids)
    return len(ids)

def batch_by_tokens(tok: MarianTokenizer, sentences, max_tokens=MAX_TOKENS_PER_BATCH, max_items=MAX_SENTS_PER_BATCH):
    batch, cur_tokens = [], 0
    for s in sentences:
        s = s.strip()
        if not s:
            continue
        tks = estimate_tokens(tok, s)
        overflow = (batch and (cur_tokens + tks > max_tokens)) or (len(batch) >= max_items)
        if overflow:
            yield batch
            batch, cur_tokens = [], 0
        batch.append(s); cur_tokens += tks
        if tks >= max_tokens:
            yield batch
            batch, cur_tokens = [], 0
    if batch:
        yield batch

# ============ CACHE IN-MEMORY ============

class SimpleCache:
    """Cache bem simples em memória (processo local)."""
    __slots__ = ("store", "hits", "misses")
    def __init__(self):
        self.store: dict[str, str] = {}
        self.hits = 0
        self.misses = 0
    def norm_key(self, s: str) -> str:
        # normalização leve pra aumentar chance de hit
        return re.sub(r"\s+", " ", s.strip())
    def get(self, s: str):
        k = self.norm_key(s)
        v = self.store.get(k)
        if v is None: self.misses += 1
        else: self.hits += 1
        return v
    def put(self, s: str, t: str):
        self.store[self.norm_key(s)] = t

# cache global por processo (reinicia ao reiniciar o worker)
_CACHE = SimpleCache()

def translate_epub(
    src_path: str,
    dst_path: str,
    model_name: str = "Helsinki-NLP/opus-mt-tc-big-en-pt",
    batch_size: int = 12,  # legado, não usado
    tok=None,
    model=None
):
    logger.info("Iniciando tradução do EPUB: %s", src_path)
    t0_job = time.perf_counter()
    try:
        if tok is None or model is None:
            tok = MarianTokenizer.from_pretrained(model_name)
            model = MarianMTModel.from_pretrained(model_name)

        out_dir = os.path.dirname(os.path.abspath(dst_path))
        os.makedirs(out_dir, exist_ok=True)

        book = epub.read_epub(src_path)
        seg = pysbd.Segmenter(language="en", clean=True)

        docs = list(book.get_items_of_type(ITEM_DOCUMENT))
        logger.info("Total de documentos XHTML: %d", len(docs))

        # métricas globais
        total_nodes, total_sents = 0, 0
        total_batches, total_translated = 0, 0
        cache_hits_start, cache_misses_start = _CACHE.hits, _CACHE.misses

        for item in docs:
            t0 = time.perf_counter()
            logger.info("Traduzindo doc id=%s file=%s", item.get_id(), item.file_name)
            try:
                soup = BeautifulSoup(item.get_content(), "lxml")
            except Exception:
                soup = BeautifulSoup(item.get_content(), "html.parser")

            text_nodes = list(soup.find_all(string=True))
            nodes_in_doc = len(text_nodes)
            total_nodes += nodes_in_doc

            # Coleciona todas as sentenças do item para deduplicação
            pending_map = []  # (node_index, [sent1, sent2, ...])
            unique_to_translate = []  # sentenças que NÃO estão no cache
            # Também guardamos índice de cada sentença pra remontar
            sentence_positions = []  # (node_index, local_sent_index)

            for idx, node in enumerate(text_nodes):
                original = (node or "").strip()
                if not original:
                    continue
                sents = seg.segment(original)
                total_sents += len(sents)

                # verifica cache por sentença
                local_pending = []
                for j, s in enumerate(sents):
                    cached = _CACHE.get(s)
                    if cached is None:
                        unique_to_translate.append(s)
                        local_pending.append(s)
                        sentence_positions.append((idx, len(local_pending)-1))
                    else:
                        # marca posição mesmo com cache hit (reconstruiremos depois)
                        local_pending.append(None)  # placeholder
                pending_map.append((idx, local_pending))

            # traduz somente “unique_to_translate”
            translated_dict = {}
            if unique_to_translate:
                for batch in batch_by_tokens(tok, unique_to_translate, MAX_TOKENS_PER_BATCH, MAX_SENTS_PER_BATCH):
                    total_batches += 1
                    enc = tok(batch, return_tensors="pt", padding=True, truncation=True)
                    gen = model.generate(**enc, max_length=512)
                    dec = tok.batch_decode(
                        gen,
                        skip_special_tokens=True,
                        clean_up_tokenization_spaces=False
                    )
                    dec = [postprocess_pt(x) for x in dec]
                    for src_s, tgt_s in zip(batch, dec):
                        translated_dict[src_s] = tgt_s
                        _CACHE.put(src_s, tgt_s)
                    total_translated += len(batch)

            # Reconstrói o texto de cada nó (cache hits + recém-traduzidos)
            for idx, local_pending in pending_map:
                # recupera o original para ressegmentar na mesma ordem
                original = (text_nodes[idx] or "").strip()
                if not original:
                    continue
                sents = seg.segment(original)
                rebuilt = []
                k = 0
                for s in sents:
                    cached = _CACHE.get(s)
                    if cached is not None:
                        rebuilt.append(cached)
                    else:
                        # veio do batch recém-traduzido
                        rebuilt.append(translated_dict.get(s, s))
                    k += 1
                text_nodes[idx].replace_with(" ".join(rebuilt))

            item.set_content(str(soup).encode("utf-8"))
            t1 = time.perf_counter()
            logger.info(
                "Doc id=%s OK | nós=%d | sents acumuladas=%d | batches acum=%d | tempo=%.2fs",
                item.get_id(), nodes_in_doc, total_sents, total_batches, (t1 - t0)
            )

        # NAV/NCX + TOC + spine (igual à sua versão)
        try: book.add_item(epub.EpubNav())
        except Exception: pass
        try: book.add_item(epub.EpubNcx())
        except Exception: pass

        toc = book.toc
        if isinstance(toc, epub.Link): toc = [toc]
        elif not isinstance(toc, (list, tuple)): toc = [toc]
        valid = [i for i in toc if isinstance(i, (epub.Link, epub.Section))]
        if not valid:
            links = []
            for doc in book.get_items_of_type(ITEM_DOCUMENT):
                title = getattr(doc, "title", None) or doc.get_id() or doc.file_name
                href = doc.file_name or (doc.get_id() + ".xhtml")
                links.append(epub.Link(href, title, doc.get_id()))
            valid = tuple(links)
        book.toc = valid

        spine = ["nav"] + [doc for doc in book.get_items_of_type(ITEM_DOCUMENT)]
        book.spine = [s for s in spine if s == "nav" or getattr(s, "file_name", None)]

        tmp_fd, tmp_path = tempfile.mkstemp(suffix=".epub"); os.close(tmp_fd)
        logger.info("Gravando EPUB temporário: %s", tmp_path)
        epub.write_epub(tmp_path, book)
        size = os.path.getsize(tmp_path)
        if size <= 0: raise RuntimeError("EPUB gerado com tamanho 0")
        shutil.move(tmp_path, dst_path)

        t1_job = time.perf_counter()
        # métricas de cache
        hits = _CACHE.hits - cache_hits_start
        misses = _CACHE.misses - cache_misses_start
        hit_rate = (hits / max(hits + misses, 1)) * 100.0

        logger.info(
            "Arquivo escrito com sucesso (%d bytes): %s | tempo total=%.2fs",
            size, dst_path, (t1_job - t0_job)
        )
        logger.info(
            "Métricas: nós=%d | sentenças=%d | traduções feitas=%d | batches=%d | cache hits=%d | misses=%d | hit-rate=%.1f%%",
            total_nodes, total_sents, total_translated, total_batches, hits, misses, hit_rate
        )

    except Exception:
        logger.exception("Falha durante a tradução/salvamento do EPUB")
        raise
