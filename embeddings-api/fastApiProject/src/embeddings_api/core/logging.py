# --- UPDATED FILE: src/embeddings_api/core/logging.py ---
import logging, sys
from ..core.config import settings

def setup_logging():
    root = logging.getLogger()
    level = getattr(logging, settings.LOG_LEVEL.upper(), logging.INFO)
    root.setLevel(level)
    h = logging.StreamHandler(sys.stdout)
    fmt = logging.Formatter('%(asctime)s | %(levelname)s | %(name)s | %(message)s')
    h.setFormatter(fmt)
    root.handlers = [h]
