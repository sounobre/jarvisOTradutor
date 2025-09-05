# worker/api/routes.py
from fastapi import APIRouter, UploadFile, File, HTTPException
from ..core.config import settings
from ..utils.fs import ensure_dirs, uploads_dir, translated_dir
from ..services.translate_epub import translate_epub_file
from ..services.models import load_model, get_model
from pathlib import Path
import uuid, shutil, logging

log = logging.getLogger("jarvis-tradutor")
router = APIRouter()

@router.get("/health")
def health():
    return {"status": "ok"}

@router.get("/version")
def version():
    return {"version": "1.0.0"}

@router.post("/translate")
async def translate(file: UploadFile = File(...)):
    ensure_dirs()
    ext = Path(file.filename).suffix.lower()
    tmpname = f"{uuid.uuid4()}{ext}"
    src = uploads_dir() / tmpname
    with open(src, "wb") as f:
        shutil.copyfileobj(file.file, f)

    # carrega/garante modelo
    tok, model = get_model()

    outname = tmpname.replace(ext, f"_ptbr{ext}")
    dst = translated_dir() / outname

    try:
        if ext == ".epub":
            size = translate_epub_file(str(src), str(dst))
        else:
            # TODO: plugar translate_txt
            shutil.copyfile(src, dst)
            size = dst.stat().st_size
        return {"status": "ok", "outputName": outname, "size": size}
    except Exception as e:
        log.exception("Falha ao processar arquivo")
        raise HTTPException(status_code=500, detail=str(e))
