# src/embeddings_api/routers/embed.py
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from ..core.lifecycle import get_service
from ..services.embedding_service import EmbeddingService
from ..core.config import settings

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
        raise HTTPException(400, "texts vazio")
    vecs = svc.encode(req.texts, normalize=req.normalize)
    if not vecs or not vecs[0]:
        raise HTTPException(500, "falha ao gerar embeddings")
    return EmbedResponse(
        model=settings.EMBED_MODEL,
        dims=len(vecs[0]),
        vectors=vecs,
    )
