# src/embeddings_api/routers/embed.py
import logging
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from ..core.lifecycle import get_service
from ..services.embedding_service import EmbeddingService
from ..core.config import settings

logger = logging.getLogger(__name__)  # logger específico deste módulo

class EmbedRequest(BaseModel):
    texts: list[str]
    normalize: bool = True

class EmbedResponse(BaseModel):
    model: str
    dims: int
    vectors: list[list[float]]

router = APIRouter()

@router.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest, svc: EmbeddingService = Depends(get_service)):
    if not req.texts:
        logger.warning("Requisição recebida sem textos (400)")
        raise HTTPException(400, "texts vazio")
    logger.info("Requisição /embed com %d textos (normalize=%s)", len(req.texts), req.normalize)
    vecs = svc.encode(req.texts, normalize=req.normalize)
    if not vecs or not vecs[0]:
        logger.error("Falha ao gerar embeddings para %d textos", len(req.texts))
        raise HTTPException(500, "falha ao gerar embeddings")
    logger.debug("Primeiro vetor gerado: %s...", str(vecs[0])[:60])
    return EmbedResponse(
        model=settings.EMBED_MODEL,
        dims=len(vecs[0]),
        vectors=vecs,
    )
