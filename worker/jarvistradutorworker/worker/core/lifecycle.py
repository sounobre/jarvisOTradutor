import logging

import os
import torch
from fastapi import FastAPI

from ..core.config import settings
from ..services.models import load_model

logger = logging.getLogger("jarvis-tradutor")

torch.set_num_threads(int(os.getenv("OMP_NUM_THREADS", "8")))
torch.set_num_interop_threads(1)

def register_lifecycle(app: FastAPI):
    @app.on_event("startup")
    async def startup():

        logger.info("Warmup do modelo: %s", settings.model_name)
        load_model(
            settings.model_name,
            src_lang=settings.src_lang,
            tgt_lang=settings.tgt_lang,
            nllb_src=settings.nllb_src,
            nllb_tgt=settings.nllb_tgt,

        )


    @app.on_event("shutdown")
    async def shutdown():
        pass
