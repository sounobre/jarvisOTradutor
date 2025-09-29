# --- NEW FILE: src/embeddings_api/core/middleware.py ---
import time, uuid, logging
from starlette.middleware.base import BaseHTTPMiddleware

logger = logging.getLogger(__name__)

class AccessLogMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request, call_next):
        rid = request.headers.get("x-request-id", str(uuid.uuid4()))
        start = time.perf_counter()

        n_texts = None
        try:
            if request.url.path.endswith("/embed"):
                body = await request.json()
                if isinstance(body, dict) and "texts" in body and isinstance(body["texts"], list):
                    n_texts = len(body["texts"])
        except Exception:
            # corpo pode ter sido consumido antes; ignora
            pass

        response = await call_next(request)
        dur_ms = (time.perf_counter() - start) * 1000.0

        logger.info("rid=%s path=%s n_texts=%s status=%s dur_ms=%.2f",
                    rid, request.url.path, n_texts, response.status_code, dur_ms)
        return response
