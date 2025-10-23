import json, re
from typing import Optional
import unicodedata

_STATUS_RE = re.compile(r'\b(good|suspect|bad)\b', re.I)
_SCORE_RE  = re.compile(r'(?<!\d)(?:1(?:\.0+)?|0(?:\.\d+)?)(?!\d)')  # 1, 1.0, 0.x

_PLACEHOLDER_RE = re.compile(r"(\{[^}]+\}|%s|%d|<[^>]+>|\$\{[^}]+\})")
_NUM_RE = re.compile(r"\d+([.,]\d+)?")
_JSON_KEYS_RE = re.compile(r'\"?\b(status|score|comment)\b\"?\s*:.*', re.I)

_ROMAN_RE = re.compile(r'\b(M{0,4}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3}))\b', re.I)
_DIGIT_RE = re.compile(r'\d+')
_STOPWORDS_CH = {
    "chapter", "chap", "ch", "capitulo", "capítulo", "cap", "capit", "capitulo.", "capítulo."
}

def extract_json_obj(text: str) -> Optional[dict]:
    """Extrai o primeiro objeto JSON bem-formado do texto (se existir)."""
    if not text:
        return None
    a = text.find('{'); b = text.rfind('}')
    if a == -1 or b == -1 or b <= a:
        return None
    snippet = text[a:b+1]
    try:
        return json.loads(snippet)
    except Exception:
        # tenta variações menores
        for i in range(a, b):
            if text[i] != '{': continue
            for j in range(b, i, -1):
                if text[j] != '}': continue
                try:
                    return json.loads(text[i:j+1])
                except Exception:
                    continue
    return None

def extract_json_or_fallback(text: str) -> dict:
    """
    Tenta JSON; se falhar, usa heurística:
    - status pelo primeiro match (good/suspect/bad) no texto (default 'suspect')
    - score pelo primeiro número [0..1] (default por status)
    - comment: texto todo resumido
    """
    js = extract_json_obj(text)
    if isinstance(js, dict) and "status" in js and "score" in js:
        return js

    # fallback heurístico
    status = "suspect"
    m = _STATUS_RE.search(text or "")
    if m:
        status = m.group(1).lower()

    score_defaults = {"good": 0.85, "suspect": 0.50, "bad": 0.15}
    score = score_defaults.get(status, 0.5)
    m2 = _SCORE_RE.search(text or "")
    if m2:
        try:
            val = float(m2.group(0))
            if 0.0 <= val <= 1.0:
                score = val
        except Exception:
            pass

    # comment: primeiro pedaço limpo
    comment = (text or "").strip()
    if len(comment) > 280:
        comment = comment[:277] + "..."

    return {"status": status, "score": float(score), "comment": comment or None}

def _has_placeholder_diff(a: str, b: str) -> bool:
    a_set = set(m.group(0) for m in _PLACEHOLDER_RE.finditer(a or ""))
    b_set = set(m.group(0) for m in _PLACEHOLDER_RE.finditer(b or ""))
    return a_set != b_set

def _has_number_diff(a: str, b: str) -> bool:
    def norm(nums):
        # normaliza 1.000/1,000 -> 1000; 7pm fica 7
        out = []
        for n in nums:
            n2 = n.replace(".", "").replace(",", "")
            try:
                out.append(int(n2))
            except Exception:
                out.append(n)
        return out
    a_nums = norm([m.group(0) for m in _NUM_RE.finditer(a or "")])
    b_nums = norm([m.group(0) for m in _NUM_RE.finditer(b or "")])
    return a_nums != b_nums

def make_comment(status: str, src: str, tgt: str,
                 chapter_en: str = "", chapter_pt: str = "",
                 raw_comment: str | None = None) -> str:
    """
    Limpa comentários 'eco' (ex.: "status":"good",...) e, se vazio/ruim,
    gera um comentário útil com heurísticas.
    """
    # 1) tenta usar o que o modelo mandou, limpando lixo
    if raw_comment:
        c = raw_comment.strip()
        # remove linhas com chaves JSON comuns
        c = "\n".join(line for line in c.splitlines() if not _JSON_KEYS_RE.search(line))
        # tira aspas duplas soltas
        c = c.strip(' "\'')
        # se sobrou algo que não seja só good/ok/etc, usa
        if c and c.lower() not in {"good", "ok", "válido", "valido"} and "status" not in c.lower():
            return c[:120]

        # 2) fallback heurístico
        if _chapters_mismatch(chapter_en, chapter_pt):
            return "capítulos possivelmente diferentes"

        if _has_placeholder_diff(src or "", tgt or ""):
            return "placeholders divergentes"

        if _has_number_diff(src or "", tgt or ""):
            return "números diferentes"

        # comentários por status
        if status == "good":
            return "equivalente e fluente"
        if status == "bad":
            return "alinhamento incorreto ou perda de sentido"
        return "tradução próxima com possíveis perdas"

def _strip_accents(s: str) -> str:
    return ''.join(c for c in unicodedata.normalize('NFD', s or '') if unicodedata.category(c) != 'Mn')

def _normalize_tokens(s: str) -> str:
    s = _strip_accents(s or '').lower()
    # remove stopwords típicas de rótulo
    for w in _STOPWORDS_CH:
        s = s.replace(w, ' ')
    s = re.sub(r'[^a-z0-9 ivxlcdm]+', ' ', s)  # deixa algarismos/romanos
    return ' '.join(s.split())

def _roman_to_int(roman: str) -> int | None:
    if not roman:
        return None
    vals = {'I':1,'V':5,'X':10,'L':50,'C':100,'D':500,'M':1000}
    roman = roman.upper()
    total = 0
    prev = 0
    for ch in reversed(roman):
        v = vals.get(ch, 0)
        if v < prev: total -= v
        else:
            total += v
            prev = v
    return total or None

def _extract_chapter_marker(s: str) -> int | None:
    """
    Extrai um 'número de capítulo' como inteiro, a partir de dígitos ou algarismos romanos.
    Ex.: 'Chapter 12' -> 12 ; 'Capítulo IV' -> 4 ; 'Parte 1 — Cap. 3' -> 3
    """
    if not s:
        return None
    norm = _normalize_tokens(s)
    # 1) procure dígitos primeiro
    m = _DIGIT_RE.search(norm)
    if m:
        try:
            return int(m.group(0))
        except Exception:
            pass
    # 2) procure romano
    m2 = _ROMAN_RE.search(norm)
    if m2 and m2.group(0):
        try:
            v = _roman_to_int(m2.group(0))
            if v and v > 0:
                return v
        except Exception:
            pass
    return None

def _chapters_mismatch(ch_en: str, ch_pt: str) -> bool:
    """
    Retorna True só quando identificamos marcadores de capítulo diferentes (ex.: 5 vs 6).
    Se não conseguirmos extrair número/romano de ambos, NÃO acusa mismatch (evita falso positivo).
    """
    n_en = _extract_chapter_marker(ch_en)
    n_pt = _extract_chapter_marker(ch_pt)
    if n_en is not None and n_pt is not None:
        return n_en != n_pt
    # sem números claros: assume que não dá pra afirmar diferença
    return False
