# --- UPDATED FILE: src/embeddings_api/core/config.py ---
from typing import Optional, Dict, Any
from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import Field


#from pydantic import BaseSettings, Field


class Settings(BaseSettings):
    # --- App / Server ---
    APP_NAME: str = Field(default="embeddings-api")
    HOST: str = Field(default="0.0.0.0")
    PORT: int = Field(default=8001)

    # --- Modelo ---
    EMBED_MODEL: str = Field(default="sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
    DEVICE: str = Field(default="cpu")  # "cpu" | "cuda"

    # NOVOS:
    #QE_MODEL: str = Field(default="Unbabel/wmt20-comet-qe-da")
    QE_MODEL: str = Field(default="wmt20-comet-qe-da")
    QE_MODEL_PATH: str | None = None  # caminho local opcional p/ .ckpt
    DA_MODEL: str = Field(default="Unbabel/wmt22-comet-da")
    BT_MODEL: str = Field(default="Helsinki-NLP/opus-mt-pt-en")
    QE_BATCH: int = Field(default=16)
    BT_MAX_LEN: int = Field(default=512)



    # --- Batch / Perf ---
    BATCH_SIZE: int = Field(default=32)

    # --- Cache ---
    # Agora também aceita "mongodb"
    CACHE_BACKEND: str = Field(default="mongodb")  # "memory" | "redis" | "mongodb"
    CACHE_CAPACITY: int = Field(default=5000)
    CACHE_TTL_SECONDS: int = Field(default=604800)  # 7 dias

    # Redis
    REDIS_URL: Optional[str] = Field(default=None)  # ex: redis://redis:6379/0

    # Mongo
    MONGO_URI: Optional[str] = Field(default=None)  # ex: mongodb://localhost:27017
    MONGO_DB: str = Field(default="jarvis")
    MONGO_COLL: str = Field(default="embed_cache")

    # Versão de chave (para evitar colisões com cache antigo)
    CACHE_KEY_VERSION: str = Field(default="v2raw")

    # --- Logging / Gunicorn ---
    LOG_LEVEL: str = Field(default="INFO")
    WORKERS: int = Field(default=1)
    TIMEOUT: int = Field(default=60)

    model_config = SettingsConfigDict(
        env_prefix="EMB_",
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    def uvicorn_kwargs(self) -> Dict[str, Any]:
        return {"host": self.HOST, "port": self.PORT}


settings = Settings()
