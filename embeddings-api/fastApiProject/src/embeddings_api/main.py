# --- UPDATED FILE: src/embeddings_api/main.py ---
from fastapi import FastAPI
from .core.lifecycle import register_lifecycle
from .routers import health, embed #, qe_router, bt_router
from .core.config import settings
from .core.middleware import AccessLogMiddleware
from .routers.qe import router as qe_router
from .routers.btcheck import router as bt_router

def create_app() -> FastAPI:
    app = FastAPI(title=settings.APP_NAME, version="1.0.0")
    register_lifecycle(app)
    app.add_middleware(AccessLogMiddleware)  # <--- aqui
    app.include_router(health.router)
    app.include_router(embed.router)
    app.include_router(qe_router)
    app.include_router(bt_router)
    return app

app = create_app()


# if __name__ == "__main__":
#     import uvicorn
#     uvicorn.run("src.embeddings_api.main:app", **settings.uvicorn_kwargs(), reload=True)
