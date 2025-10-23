# app/config.py
import os
from dotenv import load_dotenv

# carrega .env na raiz do projeto
dotenv_path = os.path.join(os.path.dirname(__file__), "..", ".env")
load_dotenv(dotenv_path)

class Settings:
    MODEL_ID = os.getenv("MODEL_ID")
    DEVICE = os.getenv("DEVICE", "cpu")
    HF_HUB_OFFLINE = os.getenv("HF_HUB_OFFLINE", "0")

    MAX_INPUT_TOKENS = int(os.getenv("MAX_INPUT_TOKENS", "1024"))
    MAX_NEW_TOKENS   = int(os.getenv("MAX_NEW_TOKENS", "256"))

    PORT = int(os.getenv("PORT", "8010"))

    # Mongo
    MONGO_URI  = os.getenv("MONGO_URI", "mongodb://localhost:27017")
    MONGO_DB   = os.getenv("MONGO_DB", "jarvis_revisor")
    MONGO_COLL = os.getenv("MONGO_COLL", "translations_cache")
    MONGO_TTL_SECONDS = int(os.getenv("MONGO_TTL_SECONDS", "0"))

settings = Settings()
