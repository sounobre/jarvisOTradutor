# app/ModelRunner.py
import threading
from transformers import AutoTokenizer, AutoModelForSeq2SeqLM
import torch
from .config import settings

class ModelRunner:
    def __init__(self):
        self.model_id = settings.MODEL_ID or "facebook/nllb-200-distilled-1.3B"
        self.device = "cuda" if (torch.cuda.is_available() and settings.DEVICE == "cuda") else "cpu"
        self.dtype = torch.float16 if self.device == "cuda" else torch.float32

        print(f"[ModelRunner] Carregando modelo {self.model_id} em {self.device}...")
        self.tokenizer = AutoTokenizer.from_pretrained(self.model_id)
        self.model = AutoModelForSeq2SeqLM.from_pretrained(
            self.model_id,
            torch_dtype=self.dtype,
            device_map="auto" if self.device == "cuda" else None,
            low_cpu_mem_usage=True
        )
        self.model.to(self.device).eval()

        self.max_input_tokens = settings.MAX_INPUT_TOKENS
        self.max_new_tokens   = settings.MAX_NEW_TOKENS
        self.lock = threading.Lock()

    # === helper robusto já usado ===
    def _resolve_bos_id(self, tgt_lang: str) -> int:
        tok = self.tokenizer
        for attr in ("lang_code_to_id", "langs_to_ids"):
            mapping = getattr(tok, attr, None)
            if isinstance(mapping, dict):
                v = mapping.get(tgt_lang)
                if v is not None:
                    return int(v)
        get_lang_id = getattr(tok, "get_lang_id", None)
        if callable(get_lang_id):
            try:
                return int(get_lang_id(tgt_lang))
            except Exception:
                pass
        candidates = [tgt_lang, f"__{tgt_lang}__", tgt_lang.replace("_","-"), f"<2{tgt_lang}>", f"<<{tgt_lang}>>"]
        for cand in candidates:
            tid = tok.convert_tokens_to_ids(cand)
            if isinstance(tid, list):
                tid = tid[0] if tid else None
            if tid is not None and tid != getattr(tok, "unk_token_id", None):
                return int(tid)
        for sp in (getattr(tok, "additional_special_tokens", []) or []):
            if sp == tgt_lang or tgt_lang in sp:
                tid = tok.convert_tokens_to_ids(sp)
                if isinstance(tid, list):
                    tid = tid[0] if tid else None
                if tid is not None and tid != getattr(tok, "unk_token_id", None):
                    return int(tid)
        raise ValueError(f"Não foi possível resolver forced_bos_token_id para '{tgt_lang}'.")

    def translate(self, text: str, src_lang: str = "eng_Latn", tgt_lang: str = "por_Latn") -> str:
        with self.lock:
            try:
                if hasattr(self.tokenizer, "set_src_lang_special_tokens"):
                    self.tokenizer.set_src_lang_special_tokens(src_lang)
                else:
                    self.tokenizer.src_lang = src_lang
            except Exception:
                pass
            enc = self.tokenizer(text, return_tensors="pt", truncation=True, max_length=self.max_input_tokens)
            enc = {k: v.to(self.device) for k, v in enc.items()}
            bos_id = self._resolve_bos_id(tgt_lang)
            out = self.model.generate(**enc, forced_bos_token_id=bos_id, max_new_tokens=self.max_new_tokens)
            return self.tokenizer.decode(out[0], skip_special_tokens=True)

    def translate_nbest(self, text: str, src_lang: str = "eng_Latn", tgt_lang: str = "por_Latn", k: int = 5) -> list[str]:
        """
        Gera k candidatos (beam search determinístico).
        """
        if k < 1:
            k = 1
        with self.lock:
            try:
                if hasattr(self.tokenizer, "set_src_lang_special_tokens"):
                    self.tokenizer.set_src_lang_special_tokens(src_lang)
                else:
                    self.tokenizer.src_lang = src_lang
            except Exception:
                pass
            enc = self.tokenizer(text, return_tensors="pt", truncation=True, max_length=self.max_input_tokens)
            enc = {k2: v.to(self.device) for k2, v in enc.items()}
            bos_id = self._resolve_bos_id(tgt_lang)

            out = self.model.generate(
                **enc,
                forced_bos_token_id=bos_id,
                num_beams=max(1, k),
                num_return_sequences=max(1, k),
                do_sample=False,
                max_new_tokens=self.max_new_tokens,
                return_dict_in_generate=True
            )
            seqs = [self.tokenizer.decode(s, skip_special_tokens=True) for s in out.sequences]
            return seqs

    def generate(self, prompt: str, **kwargs) -> str:
        p = (prompt or "").strip()
        if p.lower().startswith("translate:"):
            text = p.split("translate:", 1)[1].strip()
            return self.translate(text, "eng_Latn", "por_Latn")
        return self.translate(p, "eng_Latn", "por_Latn")
