# worker/services/models.py
from transformers import MarianMTModel, MarianTokenizer

_cache = {"tok": None, "model": None, "name": None}

def load_model(model_name: str):
    if _cache["model"] is None or _cache["name"] != model_name:
        _cache["tok"] = MarianTokenizer.from_pretrained(model_name)
        _cache["model"] = MarianMTModel.from_pretrained(model_name)
        _cache["name"] = model_name
    return _cache["tok"], _cache["model"]

def get_model():
    if _cache["model"] is None:
        return load_model("Helsinki-NLP/opus-mt-tc-big-en-pt")
    return _cache["tok"], _cache["model"]
