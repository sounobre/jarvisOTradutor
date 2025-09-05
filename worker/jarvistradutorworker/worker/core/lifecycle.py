# worker/core/lifecycle.py
from fastapi import FastAPI
from .config import settings
from ..services.models import load_model

def register_lifecycle(app: FastAPI):
    @app.on_event("startup")
    async def startup():
        # carrega em background (opcional: sem await, se preferir preguiçoso)
        load_model(settings.model_name)

    @app.on_event("shutdown")
    async def shutdown():
        pass
