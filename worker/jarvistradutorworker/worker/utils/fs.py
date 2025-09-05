# worker/utils/fs.py
from pathlib import Path
from ..core.config import settings

def ensure_dirs():
    settings.base_dir.mkdir(parents=True, exist_ok=True)
    (settings.base_dir / settings.uploads_dirname).mkdir(parents=True, exist_ok=True)
    (settings.base_dir / settings.translated_dirname).mkdir(parents=True, exist_ok=True)

def uploads_dir() -> Path:
    return settings.base_dir / settings.uploads_dirname

def translated_dir() -> Path:
    return settings.base_dir / settings.translated_dirname
