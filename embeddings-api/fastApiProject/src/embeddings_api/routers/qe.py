from fastapi import APIRouter
from typing import List, Optional
from pydantic import BaseModel
from ..services.qe_service import score_ref_free

router = APIRouter()

class QEItem(BaseModel):
    src: str
    mt: Optional[str] = None
    tgt: Optional[str] = None

class QERequest(BaseModel):
    items: List[QEItem]

@router.post("/qe")
def qe(req: QERequest):
    pairs = [{"src": it.src, "mt": it.mt, "tgt": it.tgt} for it in req.items]
    scores = score_ref_free(pairs)
    return {
        "scores": scores,
        "mean": (sum(scores) / len(scores)) if scores else 0.0
    }
