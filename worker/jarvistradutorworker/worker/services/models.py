# worker/services/models.py
from __future__ import annotations
from typing import Callable, Dict, Tuple, Any
import logging

from transformers import (
    MarianTokenizer, MarianMTModel,
    M2M100ForConditionalGeneration, M2M100Tokenizer,
    AutoTokenizer, AutoModelForSeq2SeqLM,
)

from ..core.config import settings

logger = logging.getLogger("jarvis-tradutor")

# cache simples se você quiser manter modelo/tokenizer singletons:
_cache: Dict[str, Any] = {}


def load_model(
    model_name: str,
    src_lang: str | None = None,
    tgt_lang: str | None = None,
    nllb_src: str | None = None,
    nllb_tgt: str | None = None,
):
    """
    Carrega modelo + tokenizer conforme o tipo.
    Retorna (tokenizer, model, prepare_inputs(batch)->(enc, gen_extra), decode_kwargs).
    """
    logger.info(
        "Carregando modelo: %s | src_lang=%s tgt_lang=%s nllb_src=%s nllb_tgt=%s",
        model_name, src_lang, tgt_lang, nllb_src, nllb_tgt
    )

    name = model_name.lower()

    # ---- Marian (Helsinki-NLP/opus-mt-*) ----
    if "marian" in name or "opus-mt" in name:
        tok = MarianTokenizer.from_pretrained(model_name)
        model = MarianMTModel.from_pretrained(model_name)

        def prepare_inputs(batch):
            enc = tok(batch, return_tensors="pt", padding=True, truncation=True)
            gen_extra = {}
            return enc, gen_extra

        decode_kwargs = dict(skip_special_tokens=True, clean_up_tokenization_spaces=False)

    # ---- M2M100 (facebook/m2m100_*) ----
    elif "m2m100" in name:
        tok = M2M100Tokenizer.from_pretrained(model_name)
        model = M2M100ForConditionalGeneration.from_pretrained(model_name)

        if not src_lang or not tgt_lang:
            raise ValueError("M2M100 requer SRC_LANG e TGT_LANG definidos.")

        def prepare_inputs(batch):
            enc = tok(
                batch,
                return_tensors="pt",
                padding=True,
                truncation=True,
                src_lang=src_lang,  # obrigatório
            )
            gen_extra = {"forced_bos_token_id": tok.get_lang_id(tgt_lang)}
            return enc, gen_extra

        decode_kwargs = dict(skip_special_tokens=True)

    # ---- NLLB (facebook/nllb-200-*) ----
    elif "nllb" in name or "facebook/nllb-200" in name:
        tok = AutoTokenizer.from_pretrained(model_name, use_fast=True)
        model = AutoModelForSeq2SeqLM.from_pretrained(model_name)

        if not nllb_src or not nllb_tgt:
            raise ValueError("NLLB requer NLLB_SRC e NLLB_TGT definidos.")

        def prepare_inputs(batch):
            enc = tok(
                batch,
                return_tensors="pt",
                padding=True,
                truncation=True,
                src_lang=nllb_src,  # obrigatório
            )
            gen_extra = {"forced_bos_token_id": tok.convert_tokens_to_ids(nllb_tgt)}
            return enc, gen_extra

        decode_kwargs = dict(skip_special_tokens=True)

    else:
        raise ValueError(f"Modelo não suportado: {model_name}")

    logger.info("Modelo carregado: %s", model_name)
    return tok, model, prepare_inputs, decode_kwargs


def get_model():
    """
    Compat: retorna (tok, model) para código antigo.
    Para M2M/NLLB passamos também as línguas do settings.
    """
    tok, model, _, _ = load_model(
        settings.model_name,
        src_lang=settings.src_lang,
        tgt_lang=settings.tgt_lang,
        nllb_src=settings.nllb_src,
        nllb_tgt=settings.nllb_tgt,
    )
    return tok, model
