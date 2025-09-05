import logging

from transformers import AutoTokenizer, AutoModelForSeq2SeqLM, M2M100Tokenizer, M2M100ForConditionalGeneration, \
    MarianTokenizer, MarianMTModel

logger = logging.getLogger("jarvis-tradutor")

_tok = _model = _prepare_inputs = _decode_kwargs = None

def load_model(model_name: str, src_lang: str, tgt_lang: str, nllb_src: str, nllb_tgt: str):
    global _tok, _model, _prepare_inputs, _decode_kwargs

    name = model_name.lower()
    logger.info(
        "Carregando modelo: %s | src_lang=%s tgt_lang=%s nllb_src=%s nllb_tgt=%s",
        model_name, src_lang, tgt_lang, nllb_src, nllb_tgt
    )

    if "opus-mt" in name or "marian" in name:
        tok = MarianTokenizer.from_pretrained(model_name)
        model = MarianMTModel.from_pretrained(model_name)

        def prepare_inputs(texts):
            enc = tok(texts, return_tensors="pt", padding=True, truncation=True)
            return enc, {}

        decode_kwargs = dict(skip_special_tokens=True, clean_up_tokenization_spaces=False)

    elif "m2m100" in name:
        tok = M2M100Tokenizer.from_pretrained(model_name)
        model = M2M100ForConditionalGeneration.from_pretrained(model_name)

        def prepare_inputs(texts):
            # M2M100 aceita src_lang na call
            enc = tok(texts, return_tensors="pt", padding=True, truncation=True, src_lang=src_lang)
            gen_extra = {"forced_bos_token_id": tok.get_lang_id(tgt_lang)}
            return enc, gen_extra

        decode_kwargs = dict(skip_special_tokens=True)

    else:
        # NLLB
        tok = AutoTokenizer.from_pretrained(model_name, use_fast=True)
        model = AutoModelForSeq2SeqLM.from_pretrained(model_name)

        # >>> AQUI ESTÁ O PULO DO GATO <<<
        # PARA NLLB: defina a língua de origem no atributo,
        # NÃO passe src_lang=... na chamada do tokenizer.
        tok.src_lang = nllb_src
        forced_bos = tok.convert_tokens_to_ids(nllb_tgt)

        def prepare_inputs(texts):
            enc = tok(texts, return_tensors="pt", padding=True, truncation=True)
            gen_extra = {"forced_bos_token_id": forced_bos}
            return enc, gen_extra

        decode_kwargs = dict(skip_special_tokens=True)

    _tok, _model = tok, model
    _prepare_inputs, _decode_kwargs = prepare_inputs, decode_kwargs
    logger.info("Modelo carregado: %s", model_name)
    return _tok, _model, _prepare_inputs, _decode_kwargs
