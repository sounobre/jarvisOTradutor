# worker/services/models.py
from __future__ import annotations
from transformers import (
    MarianMTModel, MarianTokenizer,
    M2M100ForConditionalGeneration, M2M100Tokenizer,
    AutoModelForSeq2SeqLM, AutoTokenizer,
)
from typing import Callable, Dict, Tuple

# cache simples em memória
_cache: Dict[str, object] = {}

def load_model(
    model_name: str,
    src_lang: str = "en",
    tgt_lang: str = "pt",
    nllb_src: str = "eng_Latn",
    nllb_tgt: str = "por_Latn",
):
    """
    Retorna (tokenizer, model, prepare_inputs_fn, decode_kwargs) conforme o modelo.
    - Marian: Helsinki-NLP/opus-...
    - M2M100: facebook/m2m100_418M
    - NLLB: facebook/nllb-200-xxx
    """
    # já em cache?
    if _cache.get("name") == model_name:
        return (
            _cache["tok"],
            _cache["model"],
            _cache["prepare"],
            _cache["decode"],
        )

    # ----- M2M100 -----
    if "m2m100" in model_name.lower():
        tok = M2M100Tokenizer.from_pretrained(model_name)
        model = M2M100ForConditionalGeneration.from_pretrained(model_name)

        def prepare(batch):
            # seta idioma de origem e BOS do alvo
            tok.src_lang = src_lang
            enc = tok(batch, return_tensors="pt", padding=True, truncation=True)
            forced_bos = tok.get_lang_id(tgt_lang)
            extra = {"forced_bos_token_id": forced_bos}
            return enc, extra

        decode = {
            "skip_special_tokens": True,
            "clean_up_tokenization_spaces": False,  # deixamos o pós-processo cuidar
        }

    # ----- NLLB-200 -----
    elif "nllb" in model_name.lower():
        tok = AutoTokenizer.from_pretrained(model_name)
        model = AutoModelForSeq2SeqLM.from_pretrained(model_name)

        def prepare(batch):
            enc = tok(batch, return_tensors="pt", padding=True, truncation=True)
            forced_bos = tok.lang_code_to_id[nllb_tgt]
            extra = {"forced_bos_token_id": forced_bos}
            return enc, extra

        decode = {
            "skip_special_tokens": True,
            "clean_up_tokenization_spaces": False,
        }

    # ----- Marian (default) -----
    else:
        tok = MarianTokenizer.from_pretrained(model_name)
        model = MarianMTModel.from_pretrained(model_name)

        def prepare(batch):
            enc = tok(batch, return_tensors="pt", padding=True, truncation=True)
            extra = {}
            return enc, extra

        decode = {
            "skip_special_tokens": True,
            "clean_up_tokenization_spaces": True,  # Marian lida bem; pode ajustar depois
        }

    _cache["name"] = model_name
    _cache["tok"] = tok
    _cache["model"] = model
    _cache["prepare"] = prepare
    _cache["decode"] = decode
    return tok, model, prepare, decode

# worker/services/models.py
from worker.core.config import settings



def get_model():
    """
    Retorna (tok, model, prepare_inputs, decode_kwargs) do cache.
    Se ainda não houver cache, carrega usando Settings.
    """
    if _cache.get("tok") is None or _cache.get("model") is None:
        return load_model(
            settings.model_name,
            settings.src_lang,
            settings.tgt_lang,
            settings.nllb_src,
            settings.nllb_tgt,
        )
    return _cache["tok"], _cache["model"], _cache["prepare"], _cache["decode"]
