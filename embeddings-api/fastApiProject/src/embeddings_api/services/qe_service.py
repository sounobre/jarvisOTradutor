from typing import List, Dict, Optional
from comet import download_model, load_from_checkpoint
from ..core.config import settings
import torch
import os

_model = None

def _normalize(name: Optional[str]) -> str:
    if not name:
        return "wmt20-comet-qe-da"
    # aceita "Unbabel/wmt20-comet-qe-da" ou só "wmt20-comet-qe-da"
    return name.split("/")[-1].strip()

def comet_qe_model():
    """
    Carrega COMET QE (ref-free). Suporta:
    - settings.QE_MODEL_PATH apontando para um checkpoint local
    - settings.QE_MODEL com id 'wmt20-comet-qe-da' (baixado com cache do HF/COMET)
    """
    global _model
    if _model is not None:
        return _model

    # 1) Caminho local tem prioridade
    if getattr(settings, "QE_MODEL_PATH", None):
        path = settings.QE_MODEL_PATH
        if os.path.exists(path):
            m = load_from_checkpoint(path)
            m.eval()
            if settings.DEVICE.lower() == "cpu":
                m.to(torch.device("cpu"))
            _model = m
            return m

    # 2) Baixa por nome
    name = _normalize(getattr(settings, "QE_MODEL", None))
    ckpt = download_model(name)  # ex: "wmt20-comet-qe-da"
    m = load_from_checkpoint(ckpt)
    m.eval()
    if settings.DEVICE.lower() == "cpu":
        m.to(torch.device("cpu"))
    _model = m
    return m

def _extract_segment_scores(out, n_items: int) -> List[float]:
    """
    Lida com diferentes formatos de saída do COMET:
    - {'system_score': float, 'segments': [floats]}
    - {'system_score': float, 'segment_scores': [floats]}
    - {'scores': [floats]}
    - {'system_score': float}  -> replica o system_score para cada item
    - [floats]                 -> já é a lista
    """
    # 1) Se for lista/tupla já de floats
    if isinstance(out, (list, tuple)):
        return list(map(float, out))

    # 2) Se for dict, tenta as chaves mais comuns
    if isinstance(out, dict):
        for key in ("segments", "segment_scores", "scores"):
            if key in out and isinstance(out[key], (list, tuple)):
                return list(map(float, out[key]))

        # último fallback: só veio o score do sistema
        if "system_score" in out:
            try:
                sys_score = float(out["system_score"])
            except Exception:
                sys_score = 0.0
            # replica o score por item (melhor que quebrar)
            return [sys_score] * max(1, n_items)

    # 3) Desconhecido → falha explícita
    raise ValueError(f"Formato de saída do COMET inesperado: {type(out)} with keys={list(out.keys()) if isinstance(out, dict) else 'n/a'}")

def score_ref_free(pairs: List[Dict[str, str]]) -> List[float]:
    """
    pairs: [{"src": "...", "mt": "...", "tgt": "...?"}, ...]
    Para QE ref-free (wmt20-comet-qe-da), o campo usado é MT (hipótese).
    Se vier 'tgt', ignoramos neste endpoint.
    """
    model = comet_qe_model()

    # Normaliza entradas para o formato esperado pelo QE ref-free
    data = []
    for p in pairs:
        src = p.get("src") or ""
        mt  = p.get("mt") or p.get("tgt") or ""   # se não vier mt, usa tgt como fallback
        data.append({"src": src, "mt": mt})

    # COMET cuida de batching; gpus=0 força CPU
    with torch.no_grad():
        out = model.predict(
            data,
            batch_size=getattr(settings, "QE_BATCH", 8),
            gpus=0,
            progress_bar=False
        )

    # LOG de debug: formato e amostra
    import logging
    log = logging.getLogger(__name__)
    try:
        if isinstance(out, dict):
            log.info(f"[QE] predict() keys: {list(out.keys())}")
        log.info(f"[QE] predict() raw: {str(out)[:600]}...")
    except Exception:
        pass

    # Extrai scores por segmento de forma robusta
    scores = _extract_segment_scores(out, n_items=len(data))
    return scores
