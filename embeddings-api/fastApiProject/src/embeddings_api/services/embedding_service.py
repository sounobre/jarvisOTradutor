import logging, time
import numpy as np
from sentence_transformers import SentenceTransformer
from ..core.config import settings
from ..utils.cache import get_cache, canonicalize_for_key

logger = logging.getLogger(__name__)

def _sha1_hex(s: str) -> str:
    import hashlib
    try:
        # compat com builds que suportam usedforsecurity
        return hashlib.sha1(s.encode("utf-8"), usedforsecurity=False).hexdigest()
    except TypeError:
        return hashlib.sha1(s.encode("utf-8")).hexdigest()

def _stable_key(text: str) -> str:
    # chave = versão + modelo + sha1(texto canônico)
    can = canonicalize_for_key(text)
    h = _sha1_hex(can)
    return f"emb:{settings.CACHE_KEY_VERSION}:{settings.EMBED_MODEL}:{h}"

class EmbeddingService:
    def __init__(self):
        logger.info("Carregando modelo: %s (device=%s)", settings.EMBED_MODEL, settings.DEVICE)
        self.model = SentenceTransformer(settings.EMBED_MODEL, device=settings.DEVICE)
        self.cache = get_cache()

    def warmup(self):
        logger.info("Warmup do modelo")
        _ = self.model.encode(["warmup"], normalize_embeddings=False, show_progress_bar=False)
        logger.info("Warmup concluído")

    def _normalize_rows(self, arr: np.ndarray) -> np.ndarray:
        norms = np.linalg.norm(arr, axis=1, keepdims=True)
        norms[norms == 0] = 1.0
        return arr / norms

    def encode(self, texts: list[str], normalize: bool) -> list[list[float]]:
        start = time.perf_counter()
        hits = 0

        vecs: list[list[float] | None] = [None] * len(texts)
        missing_idx: list[int] = []
        # map p/ não recomputar duplicatas dentro do mesmo request
        unique_map: dict[str, list[int]] = {}

        for i, t in enumerate(texts):
            key = _stable_key(t)
            v = self.cache.get(key)
            if v is None:
                missing_idx.append(i)
                unique_map.setdefault(t, []).append(i)
            else:
                hits += 1
                vecs[i] = v  # vetor RAW

        computed = 0
        if missing_idx:
            unique_texts = list(unique_map.keys())
            logger.info("Cache MISS: %d textos (unique=%d) a calcular (batch_size=%d)",
                        len(missing_idx), len(unique_texts), settings.BATCH_SIZE)

            arr = self.model.encode(
                unique_texts,
                normalize_embeddings=False,  # sempre RAW no cache
                batch_size=settings.BATCH_SIZE,
                show_progress_bar=False,
            ).astype(np.float32)

            for j, t in enumerate(unique_texts):
                row_raw = arr[j].tolist()
                key = _stable_key(t)
                self.cache.set(key, row_raw)
                for i in unique_map[t]:
                    vecs[i] = row_raw
                    computed += 1

        if normalize:
            np_arr = np.array(vecs, dtype=np.float32)
            np_arr = self._normalize_rows(np_arr)
            vecs = np_arr.astype(float).tolist()

        dur_ms = (time.perf_counter() - start) * 1000.0
        logger.info("encode(): textos=%d hits=%d computed=%d dur_ms=%.1f normalize=%s",
                    len(texts), hits, computed, dur_ms, normalize)
        return vecs  # type: ignore
