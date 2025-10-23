# app/cache.py
import hashlib
import time
from typing import Optional, Dict, Any
from motor.motor_asyncio import AsyncIOMotorClient
from .config import settings

class MongoCache:
    def __init__(self):
        self.client: AsyncIOMotorClient | None = None
        self.col = None

    async def connect(self):
        self.client = AsyncIOMotorClient(settings.MONGO_URI)
        db = self.client[settings.MONGO_DB]
        self.col = db[settings.MONGO_COLL]
        # índices úteis
        await self.col.create_index("key", unique=True)
        await self.col.create_index("created_at")
        # TTL opcional
        if settings.MONGO_TTL_SECONDS > 0:
            # cria/atualiza índice TTL em created_at
            try:
                await self.col.create_index("created_at", expireAfterSeconds=settings.MONGO_TTL_SECONDS)
            except Exception:
                pass

    async def close(self):
        if self.client:
            self.client.close()

    @staticmethod
    def make_key(text: str, src_lang: str, tgt_lang: str, model_id: str, max_new_tokens: int) -> str:
        payload = f"{text}\n|{src_lang}|{tgt_lang}|{model_id}|{max_new_tokens}"
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()

    async def get(self, key: str) -> Optional[Dict[str, Any]]:
        doc = await self.col.find_one({"key": key}, {"_id": 0})
        return doc

    async def set(self, key: str, result_text: str, meta: Dict[str, Any]):
        doc = {
            "key": key,
            "text": result_text,
            "meta": meta,
            "created_at": int(time.time())
        }
        try:
            await self.col.update_one({"key": key}, {"$set": doc}, upsert=True)
        except Exception:
            pass

cache = MongoCache()
