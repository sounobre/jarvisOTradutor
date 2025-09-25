# embeddings_api/services/embedding_service.py
import logging

import numpy as np
from sentence_transformers import SentenceTransformer
from ..core.config import settings
from ..utils.cache import get_cache

logger = logging.getLogger(__name__)

class EmbeddingService:
    def __init__(self):
        logger.info("Carregando modelo de embeddings: %s (device=%s)", settings.EMBED_MODEL, settings.DEVICE)
        # Carrega o modelo uma vez
        self.model = SentenceTransformer(settings.EMBED_MODEL, device=settings.DEVICE)
        self.cache = get_cache()  # pode ser dict/LRU/Redis
        logger.info("Cache backend inicializado: %s", type(self.cache).__name__)

    def warmup(self):
        logger.info("Executando warmup do modelo")
        _ = self.model.encode(["warmup"], normalize_embeddings=False)
        logger.info("Warmup concluído")

    def encode(self, texts: list[str], normalize: bool) -> list[list[float]]:
        logger.debug("Iniciando encode: %d textos (normalize=%s)", len(texts), normalize)
        vecs: list[list[float] | None] = []
        to_compute: list[str] = []

        for t in texts:
            key = f"emb:{settings.EMBED_MODEL}:{t}"
            v = self.cache.get(key)
            if v is None:
                to_compute.append(t)
                vecs.append(None)
            else:
                vecs.append(v)
                logger.debug("Cache HIT para texto='%s...'", t[:30])

        if to_compute:
            logger.info("Cache MISS: %d textos a calcular", len(to_compute))
            arr = self.model.encode(to_compute, normalize_embeddings=False)
            if normalize:
                norms = np.linalg.norm(arr, axis=1, keepdims=True)
                norms[norms == 0] = 1.0
                arr = arr / norms
            j = 0
            for i in range(len(vecs)):
                if vecs[i] is None:
                    row = arr[j].astype(float).tolist()
                    vecs[i] = row
                    key = f"emb:{settings.EMBED_MODEL}:{texts[i]}"
                    self.cache.set(key, row)
                    j += 1
            logger.info("Embeddings calculados e armazenados em cache")
        # type: ignore: sabemos que None foi preenchido
        return vecs  # list[list[float]]
