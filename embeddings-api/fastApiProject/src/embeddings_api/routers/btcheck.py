# src/embeddings_api/routers/btcheck.py
from fastapi import APIRouter
from ..models.schemas import BTBatch, BTResponse
from ..services.bt_service import backtranslate_tgt_to_src, chrf_scores

router = APIRouter(prefix="/btcheck", tags=["btcheck"])

@router.post("", response_model=BTResponse)
def btcheck(batch: BTBatch):
    tgt_texts = [p.tgt for p in batch.pairs]
    hyp_srcs = backtranslate_tgt_to_src(tgt_texts)
    ref_srcs = [p.src for p in batch.pairs]
    scores = chrf_scores(hyp_srcs, ref_srcs)
    return {"chrf": scores}
