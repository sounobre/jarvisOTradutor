# worker/app.py
from fastapi import FastAPI
from .core.logging import setup_logging
from .core.lifecycle import register_lifecycle
from .api.routes import router
from fastapi.middleware.cors import CORSMiddleware

logger = setup_logging()

app = FastAPI(title="Translator Worker", version="1.0.0")
register_lifecycle(app)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # ajuste se quiser restringir
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(router)
