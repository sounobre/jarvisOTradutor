import time, re, unicodedata, json, logging
from datetime import datetime, timezone
from ..core.config import settings
from pymongo.errors import OperationFailure

logger = logging.getLogger(__name__)

def canonicalize_for_key(s: str) -> str:
    """
    Normalização leve só para a CHAVE:
    - NFKC
    - trim + colapso de whitespace
    """
    s = unicodedata.normalize("NFKC", s)
    s = re.sub(r"\s+", " ", s.strip())
    return s

class _LRUCacheTTL:
    """LRU em memória com TTL (segundos)."""
    def __init__(self, capacity: int = 5000, ttl_seconds: int = 604800):
        from collections import OrderedDict
        self.cap = capacity
        self.ttl = ttl_seconds
        self.od = OrderedDict()  # k -> (v, expiry)

    def _purge_expired(self):
        now = time.time()
        to_delete = []
        for k, (_, exp) in list(self.od.items()):
            if exp < now:
                to_delete.append(k)
        for k in to_delete:
            self.od.pop(k, None)
        while len(self.od) > self.cap:
            self.od.popitem(last=False)

    def get(self, k):
        self._purge_expired()
        item = self.od.pop(k, None)
        if not item:
            return None
        v, exp = item
        if exp < time.time():
            return None
        # move para o fim (MRU)
        self.od[k] = (v, exp)
        return v

    def set(self, k, v):
        self._purge_expired()
        if k in self.od:
            self.od.pop(k)
        elif len(self.od) >= self.cap:
            self.od.popitem(last=False)
        self.od[k] = (v, time.time() + self.ttl)

def _ensure_ttl_index(coll, ttl_seconds: int):
    """Garante um índice TTL em created_at. Se já existir um índice em created_at,
    verifica expiracao; se estiver diferente ou sem TTL, dropa e recria."""
    # Procura qualquer índice cujo key seja exatamente {created_at: 1}
    existing = None
    for idx in coll.list_indexes():
        # idx["key"] é um OrderedDict/SON, convertemos em lista de pares
        if list(idx.get("key", {}).items()) == [("created_at", 1)]:
            existing = idx
            break

    if existing:
        current_ttl = existing.get("expireAfterSeconds")
        if current_ttl == ttl_seconds:
            # Está ok, não faz nada
            return
        # TTL diferente ou inexistente → dropar e recriar
        try:
            coll.drop_index(existing["name"])
        except OperationFailure as e:
            # Sem permissão ou corrida de condição: loga e tenta seguir
            logger.warning("Falha ao dropar índice %s: %s", existing["name"], e)

    # Cria o índice TTL com um nome estável
    coll.create_index(
        [("created_at", 1)],
        name="created_at_ttl",
        expireAfterSeconds=int(ttl_seconds),
    )

def get_cache():
    # ... Redis inalterado ...

    if settings.CACHE_BACKEND == "mongodb" and settings.MONGO_URI:
        try:
            from pymongo import MongoClient
        except Exception:
            logger.warning("pymongo não instalado; fallback para cache em memória. "
                           "Instale com: pip install 'pymongo>=4.7,<5'")
            return _LRUCacheTTL(settings.CACHE_CAPACITY, settings.CACHE_TTL_SECONDS)

        client = MongoClient(settings.MONGO_URI)
        coll = client[settings.MONGO_DB][settings.MONGO_COLL]

        # ✅ Usa a função que evita o conflito de nome/TTL
        _ensure_ttl_index(coll, settings.CACHE_TTL_SECONDS)

        class MongoWrap:
            def get(self, k):
                doc = coll.find_one({"_id": k}, {"_id": 0, "v": 1})
                return None if not doc else doc["v"]
            def set(self, k, v):
                coll.update_one(
                    {"_id": k},
                    {"$set": {"v": v, "created_at": datetime.now(timezone.utc)}},
                    upsert=True
                )
        logger.info("Cache backend: MongoDB")
        return MongoWrap()


    # MongoDB
    if settings.CACHE_BACKEND == "mongodb" and settings.MONGO_URI:
        try:
            from pymongo import MongoClient, ASCENDING
        except Exception:
            logger.warning("pymongo não instalado; fallback para cache em memória. "
                           "Instale com: pip install 'pymongo>=4.7,<5'")
            return _LRUCacheTTL(settings.CACHE_CAPACITY, settings.CACHE_TTL_SECONDS)

        client = MongoClient(settings.MONGO_URI)
        coll = client[settings.MONGO_DB][settings.MONGO_COLL]

        # TTL só em created_at (NÃO crie índice em _id; já existe e é único)
        coll.create_index(
            [("created_at", ASCENDING)],
            name="created_at_ttl",
            expireAfterSeconds=settings.CACHE_TTL_SECONDS,
        )

        class MongoWrap:
            def get(self, k):
                doc = coll.find_one({"_id": k}, {"_id": 0, "v": 1})
                return None if not doc else doc["v"]
            def set(self, k, v):
                coll.update_one(
                    {"_id": k},
                    {"$set": {"v": v, "created_at": datetime.now(timezone.utc)}},
                    upsert=True
                )
        logger.info("Cache backend: MongoDB")
        return MongoWrap()

    # Memória local (default)
    logger.info("Cache backend: InMemory LRU")
    return _LRUCacheTTL(settings.CACHE_CAPACITY, settings.CACHE_TTL_SECONDS)

__all__ = ["get_cache", "canonicalize_for_key"]
