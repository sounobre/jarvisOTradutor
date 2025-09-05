# worker/api/routes.py
from fastapi import APIRouter, UploadFile, File
from pathlib import Path
import uuid, os

from ..utils.fs import ensure_dirs
from ..core.config import settings
from ..services.translate_epub import translate_epub_file

router = APIRouter()

@router.post("/translate")
async def translate(file: UploadFile = File(...)):
    # garante diretórios de trabalho
    ensure_dirs()

    # gera nome único para evitar conflito
    ext = os.path.splitext(file.filename)[1].lower()
    fname = f"{uuid.uuid4()}{ext}"
    src = settings.uploads_path / fname
    dst = settings.translated_path / f"{src.stem}_ptbr.epub"

    # salva upload
    with open(src, "wb") as f:
        f.write(await file.read())

    # traduz e salva no destino
    size = translate_epub_file(str(src), str(dst))

    return {
        "status": "ok",
        "outputName": dst.name,
        "size": size
    }
