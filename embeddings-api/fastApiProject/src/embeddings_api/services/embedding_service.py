# embeddings_api/services/embedding_service.py
import numpy as np
from sentence_transformers import SentenceTransformer
from ..core.config import settings
from ..utils.cache import get_cache

class EmbeddingService:
    def __init__(self):
        # Carrega o modelo uma vez
        self.model = SentenceTransformer(settings.EMBED_MODEL, device=settings.DEVICE)
        self.cache = get_cache()  # pode ser dict/LRU/Redis

    def warmup(self):
        _ = self.model.encode(["warmup"], normalize_embeddings=False)

    def encode(self, texts: list[str], normalize: bool) -> list[list[float]]:
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

        if to_compute:
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
        # type: ignore: sabemos que None foi preenchido
        return vecs  # list[list[float]]
