# src/embeddings_api/routers/health.py
from fastapi import APIRouter, Depends
from ..core.lifecycle import get_service
from ..services.embedding_service import EmbeddingService

router = APIRouter()

@router.get("/health")
def health(svc: EmbeddingService = Depends(get_service)):
    dims = None
    try:
        dims = svc.model.get_sentence_embedding_dimension()
    except Exception:
        pass
    return {"ok": True, "model": type(svc.model).__name__, "dims": dims}
