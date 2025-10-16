# src/embeddings_api/services/bt_service.py
from functools import lru_cache
from ..core.config import settings

@lru_cache(maxsize=1)
def _load_marian():
    from transformers import MarianMTModel, MarianTokenizer
    tok = MarianTokenizer.from_pretrained(settings.BT_MODEL)
    mt  = MarianMTModel.from_pretrained(settings.BT_MODEL)
    return tok, mt

def backtranslate_tgt_to_src(tgts: list[str]) -> list[str]:
    tok, mt = _load_marian()
    import torch
    device = torch.device(settings.DEVICE)
    mt = mt.to(device)
    batch = tok(tgts, return_tensors="pt", padding=True, truncation=True, max_length=settings.BT_MAX_LEN)
    batch = {k: v.to(device) for k, v in batch.items()}
    out = mt.generate(**batch, max_length=settings.BT_MAX_LEN)
    return tok.batch_decode(out, skip_special_tokens=True)

def chrf_scores(hyp_srcs: list[str], ref_srcs: list[str]) -> list[float]:
    import sacrebleu
    # chrF é 0..100
    return [sacrebleu.corpus_chrf([h], [[r]]).score for h, r in zip(hyp_srcs, ref_srcs)]
