# --- UPDATED FILE: src/embeddings_api/routers/embed.py ---
import logging
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from ..core.lifecycle import get_service
from ..services.embedding_service import EmbeddingService
from ..core.config import settings

logger = logging.getLogger(__name__)

class EmbedRequest(BaseModel):
    texts: list[str]
    normalize: bool = True

class EmbedResponse(BaseModel):
    model: str
    dims: int
    vectors: list[list[float]]

router = APIRouter()

MAX_CHARS = 4000

@router.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest, svc: EmbeddingService = Depends(get_service)):
    if not req.texts:
        logger.warning("Requisição sem textos")
        raise HTTPException(400, "texts vazio")

    # Sanitização simples (evita strings vazias e textos gigantes)
    texts = [t[:MAX_CHARS] for t in req.texts if t and t.strip()]
    if not texts:
        raise HTTPException(400, "sem textos válidos")

    logger.info("/embed n_texts=%d normalize=%s", len(texts), req.normalize)
    vecs = svc.encode(texts, normalize=req.normalize)

    if not vecs or not vecs[0]:
        logger.error("Falha ao gerar embeddings (vetor vazio)")
        raise HTTPException(500, "falha ao gerar embeddings")

    return EmbedResponse(
        model=settings.EMBED_MODEL,
        dims=len(vecs[0]),
        vectors=vecs,
    )
