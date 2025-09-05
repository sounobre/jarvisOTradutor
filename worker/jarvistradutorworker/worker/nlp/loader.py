# worker/nlp/loader.py
from transformers import (
    AutoTokenizer, AutoModelForSeq2SeqLM,
)
from typing import Tuple

def load_model(model_name: str, src_lang: str, tgt_lang: str,
               nllb_src: str, nllb_tgt: str):
    """
    Suporta M2M100, NLLB, Marian (fallback).
    Retorna (tokenizer, model, prepare_inputs_fn, decode_kwargs)
    """
    tok = AutoTokenizer.from_pretrained(model_name, use_fast=True)
    model = AutoModelForSeq2SeqLM.from_pretrained(model_name)

    name = model_name.lower()
    decode_kwargs = dict(skip_special_tokens=True, clean_up_tokenization_spaces=False)

    if "m2m100" in name:
        # M2M100 usa forced_bos_token_id para definir o alvo
        tgt_token_id = tok.get_lang_id(tgt_lang)
        def prepare_inputs(texts):
            enc = tok(texts, return_tensors="pt", padding=True, truncation=True)
            return enc, dict(forced_bos_token_id=tgt_token_id)
        return tok, model, prepare_inputs, decode_kwargs

    if "nllb" in name:
        # NLLB requer src_lang/tgt_lang em ids de idioma
        def prepare_inputs(texts):
            enc = tok(texts, return_tensors="pt", padding=True, truncation=True, src_lang=nllb_src)
            gen_extra = dict(forced_bos_token_id=tok.convert_tokens_to_ids(nllb_tgt))
            return enc, gen_extra
        return tok, model, prepare_inputs, decode_kwargs

    # Marian (fallback)
    def prepare_inputs(texts):
        enc = tok(texts, return_tensors="pt", padding=True, truncation=True)
        return enc, {}
    return tok, model, prepare_inputs, decode_kwargs
