from typing import Optional, Dict, Any
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    # --- App / Server ---
    APP_NAME: str = Field(default="embeddings-api")
    HOST: str = Field(default="0.0.0.0")   # EMB_HOST
    PORT: int = Field(default=8001)        # EMB_PORT

    # --- Modelo de embeddings ---
    EMBED_MODEL: str = Field(
        default="sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
    )  # EMB_EMBED_MODEL
    DEVICE: str = Field(default="cpu")     # "cpu" ou "cuda"  (EMB_DEVICE)

    # --- Cache ---
    CACHE_BACKEND: str = Field(default="memory")  # "memory" | "redis" (EMB_CACHE_BACKEND)
    CACHE_CAPACITY: int = Field(default=5000)     # LRU max entries (EMB_CACHE_CAPACITY)
    REDIS_URL: Optional[str] = Field(default=None)  # ex.: redis://redis:6379/0 (EMB_REDIS_URL)

    # --- Gunicorn (se usar em produção) ---
    WORKERS: int = Field(default=1)        # EMB_WORKERS
    TIMEOUT: int = Field(default=60)       # EMB_TIMEOUT

    # Configurações do Pydantic Settings (v2)
    model_config = SettingsConfigDict(
        env_prefix="EMB_",           # Variáveis de ambiente começam com EMB_
        env_file=".env",             # Carrega .env se existir
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",              # Ignora chaves extras no env
    )

    # Helper: kwargs prontos para uvicorn.run(...)
    def uvicorn_kwargs(self) -> Dict[str, Any]:
        return {
            "host": self.HOST,
            "port": self.PORT,
            # você pode incluir mais flags aqui se quiser:
            # "reload": True,
            # "log_level": "info",
        }


# instância única para usar pelo app
settings = Settings()
