from transformers import MarianMTModel, MarianTokenizer
import pysbd


def translate_txt(src_path, dst_path, model_name="Helsinki-NLP/opus-mt-en-pt", batch_size=16, tok=None, model=None):
    if tok is None or model is None:
        tok = MarianTokenizer.from_pretrained(model_name)
        model = MarianMTModel.from_pretrained(model_name)
    text = open(src_path, "r", encoding="utf-8", errors="ignore").read()
    seg = pysbd.Segmenter(language="en", clean=True)
    sents = seg.segment(text)
    out = []
    for i in range(0, len(sents), batch_size):
        batch = sents[i:i + batch_size]
        enc = tok(batch, return_tensors="pt", padding=True, truncation=True)
        gen = model.generate(**enc, max_length=512)
        out.extend(tok.batch_decode(gen, skip_special_tokens=True))
    open(dst_path, "w", encoding="utf-8").write(" ".join(out))
