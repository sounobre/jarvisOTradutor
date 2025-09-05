# worker/quality/inspector.py
from __future__ import annotations
import logging, math, re
from dataclasses import dataclass
from typing import Dict, Optional

# deps opcionais
try:
    from sentence_transformers import SentenceTransformer, util as st_util
    HAS_ST = True
except Exception:
    HAS_ST = False

try:
    from langdetect import detect
    HAS_LANGDETECT = True
except Exception:
    HAS_LANGDETECT = False


logger = logging.getLogger("jarvis-tradutor")


_EN_PLACEHOLDER_WORDS = {
    # palavras comuns que deveriam ser traduzidas em ficção
    "chapter", "prologue", "epilogue", "acknowledgments", "preface",
    "contents", "the", "and", "or", "of", "to", "for", "with",
}
_PUNCT_WEIRD_SPACES = re.compile(r"\s+([,.;:!?])")
_PUNCT_MISSING_SPACE = re.compile(r"([,.;:!?])(\S)")

@dataclass
class QualityThresholds:
    min_len_ratio: float = 0.6      # traduzido >= 60% do tamanho do original
    max_len_ratio: float = 1.6      # traduzido <= 160% do tamanho do original
    max_untranslated_hits: int = 2  # quantas palavras inglesas podem sobrar
    max_punct_issues: int = 3       # número de “problemas” de pontuação tolerados
    min_semantic_sim: float = 0.68  # similaridade mínima (0..1)
    min_avg_logprob: float = -2.5   # logprob médio por token (mais alto é melhor). Se None, ignora.


class QualityInspector:
    """
    Avalia trechos traduzidos e decide se precisam de revisão.
    """

    def __init__(
        self,
        thresholds: QualityThresholds = QualityThresholds(),
        enable_semantic: bool = True,
        semantic_model_name: str = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
        tokenizer=None,
        model=None,
        prepare_inputs=None,
        decode_kwargs: Optional[Dict] = None,
        src_lang: str = "en",
        tgt_lang: str = "pt",
    ):
        self.th = thresholds
        self.src_lang = src_lang
        self.tgt_lang = tgt_lang

        self.tok = tokenizer
        self.model = model
        self.prepare_inputs = prepare_inputs
        self.decode_kwargs = decode_kwargs or {}

        self.enable_semantic = enable_semantic and HAS_ST
        self.emb = None
        if self.enable_semantic:
            try:
                self.emb = SentenceTransformer(semantic_model_name)
                logger.info("QualityInspector: embeddings carregados: %s", semantic_model_name)
            except Exception as e:
                logger.warning("QualityInspector: falha ao carregar embeddings (%s). Desabilitando. Err: %s",
                               semantic_model_name, e)
                self.enable_semantic = False

    # ---------- Sinais individuais ----------

    def _length_ratio(self, orig: str, trans: str) -> float:
        lo = max(1, len(orig.strip()))
        lt = max(1, len(trans.strip()))
        return lt / lo

    def _untranslated_hits(self, trans: str) -> int:
        t = trans.lower()
        hits = sum(1 for w in _EN_PLACEHOLDER_WORDS if f" {w} " in f" {t} ")
        return hits

    def _punct_issues(self, trans: str) -> int:
        issues = 0
        issues += len(_PUNCT_WEIRD_SPACES.findall(trans))          # espaço antes de pontuação
        issues += len(_PUNCT_MISSING_SPACE.findall(trans))         # falta espaço depois
        # parenteses/aspas desequilibradas
        if trans.count("(") != trans.count(")"): issues += 1
        if trans.count("[") != trans.count("]"): issues += 1
        if trans.count('"') % 2 != 0: issues += 1
        return issues

    def _semantic_similarity(self, orig: str, trans: str) -> Optional[float]:
        if not self.enable_semantic or not self.emb:
            return None
        try:
            embs = self.emb.encode([orig, trans], normalize_embeddings=True, convert_to_tensor=True)
            sim = float(st_util.cos_sim(embs[0], embs[1]).item())
            return sim
        except Exception as e:
            logger.debug("QualityInspector: erro ao calcular similaridade: %s", e)
            return None

    def _avg_logprob(self, src: str, tgt: str) -> Optional[float]:
        """
        Tenta calcular log-prob média/token do alvo sob o modelo seq2seq (teacher forcing).
        Se não der, retorna None sem falhar.
        """
        if not (self.tok and self.model and self.prepare_inputs):
            return None
        try:
            # encoder inputs (com prepare_inputs, que já configura idiomas para NLLB/M2M)
            enc, gen_extra = self.prepare_inputs([src])
            # labels (alvo)
            lab = self.tok([tgt], return_tensors="pt", padding=True, truncation=True)
            # alguns modelos usam forced_bos_token_id no generate; para loss não precisa,
            # mas podemos setar decoder_start_token_id, se existir em gen_extra
            if "forced_bos_token_id" in gen_extra:
                self.model.config.forced_bos_token_id = gen_extra["forced_bos_token_id"]

            loss_out = self.model(**enc, labels=lab["input_ids"])
            loss = float(loss_out.loss.item())
            # loss = NLL/token (aprox. cross-entropy), logprob médio = -loss
            return -loss
        except Exception as e:
            logger.debug("QualityInspector: erro ao calcular avg_logprob: %s", e)
            return None

    def _lang_flags(self, text: str) -> Optional[str]:
        if not HAS_LANGDETECT:
            return None
        try:
            lang = detect(text)
            return lang
        except Exception:
            return None

    # ---------- Orquestrador ----------

    def analyze_paragraph(self, original: str, translated: str) -> Dict:
        """
        Retorna um dicionário com métricas e se precisa de revisão.
        """
        original = (original or "").strip()
        translated = (translated or "").strip()

        len_ratio = self._length_ratio(original, translated)
        untrans_hits = self._untranslated_hits(translated)
        punct_issues = self._punct_issues(translated)
        sim = self._semantic_similarity(original, translated)
        avg_lp = self._avg_logprob(original, translated)
        lang_guess = self._lang_flags(translated)

        # decisão
        needs = False
        reasons = []

        if len_ratio < self.th.min_len_ratio or len_ratio > self.th.max_len_ratio:
            needs = True; reasons.append(f"length_ratio={len_ratio:.2f}")

        if untrans_hits > self.th.max_untranslated_hits:
            needs = True; reasons.append(f"untranslated_hits={untrans_hits}")

        if punct_issues > self.th.max_punct_issues:
            needs = True; reasons.append(f"punct_issues={punct_issues}")

        if sim is not None and sim < self.th.min_semantic_sim:
            needs = True; reasons.append(f"semantic_sim={sim:.2f}")

        if avg_lp is not None and avg_lp < self.th.min_avg_logprob:
            needs = True; reasons.append(f"avg_logprob={avg_lp:.2f}")

        if lang_guess and lang_guess not in ("pt", "pt-br", "pt-PT"):
            # se detectou “en” ou outra, vale ligar alerta
            reasons.append(f"lang_detect={lang_guess}")
            needs = True

        metrics = {
            "length_ratio": len_ratio,
            "untranslated_hits": untrans_hits,
            "punct_issues": punct_issues,
            "semantic_sim": sim,
            "avg_logprob": avg_lp,
            "lang_detect": lang_guess,
            "needs_review": needs,
            "reasons": reasons,
        }

        # logs amigáveis
        logger.info(
            "QI: needs_review=%s | reasons=%s | len_ratio=%.2f | untrans=%d | punct=%d | sim=%s | avg_lp=%s | lang=%s",
            needs, ",".join(reasons) or "-", len_ratio, untrans_hits, punct_issues,
            f"{sim:.3f}" if sim is not None else "None",
            f"{avg_lp:.3f}" if avg_lp is not None else "None",
            lang_guess or "None"
        )

        return metrics
