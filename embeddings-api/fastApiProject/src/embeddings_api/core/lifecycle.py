# src/embeddings_api/core/lifecycle.py
from fastapi import FastAPI
from fastapi import Request
from .logging import setup_logging
from ..services.embedding_service import EmbeddingService

def register_lifecycle(app: FastAPI):
    @app.on_event("startup")
    async def _startup():
        setup_logging()
        svc = EmbeddingService()   # carrega modelo 1x
        svc.warmup()               # aquece
        # guarda a instância na app
        app.state.emb_service = svc  # type: ignore[attr-defined]

    @app.on_event("shutdown")
    async def _shutdown():
        pass

def get_service(request: Request) -> EmbeddingService:
    # helper para injeção via Depends
    return request.app.state.emb_service  # type: ignore[attr-defined]
