from ..core.config import settings

# cache memória simples (LRU)
class _LRUCache:
    def __init__(self, capacity: int = 5000):
        from collections import OrderedDict
        self.cap = capacity
        self.od = OrderedDict()
    def get(self, k):
        if k not in self.od: return None
        v = self.od.pop(k); self.od[k] = v; return v
    def set(self, k, v):
        if k in self.od: self.od.pop(k)
        elif len(self.od) >= self.cap: self.od.popitem(last=False)
        self.od[k] = v

def get_cache():
    if settings.CACHE_BACKEND == "redis" and settings.REDIS_URL:
        import redis, json
        r = redis.Redis.from_url(settings.REDIS_URL, decode_responses=True)
        class RedisWrap:
            def get(self, k):
                v = r.get(k)
                return None if v is None else json.loads(v)
            def set(self, k, v):
                r.set(k, json.dumps(v))
        return RedisWrap()
    return _LRUCache(settings.CACHE_CAPACITY)
