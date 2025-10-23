# app/prompt.py
from textwrap import dedent

def build_review_prompt(src: str, tgt: str, chapter_en: str, chapter_pt: str, final_score: float | None) -> str:
    return dedent(f"""
    Tarefa: classificar par EN→PT como "good", "suspect" ou "bad".
    Regras:
    - Compare sentido e fidelidade; penalize placeholders divergentes ({{x}}, %s, <tag>, ${{var}}) e números muito diferentes.
    - Considere pistas de capítulo: se chapter_en e chapter_pt indicam trechos diferentes, suspeite.
    - Use score entre 0 e 1 (real), maior = melhor.
    - Responda SOMENTE um JSON válido, sem texto extra.

    Campos:
    - status: "good" | "suspect" | "bad"
    - score: número entre 0 e 1
    - comment: motivo breve em pt-BR (máx 10 palavras), NÃO pode ser "good"/"ok"/"válido".

    Exemplos:
    Input fictício:
    EN: "Hello!"
    PT: "Olá!"
    -> Saída:
    {{"status":"good","score":0.92,"comment":"tradução direta e correta"}}

    Input fictício:
    EN: "He left at 7 pm."
    PT: "Ele saiu às 9h."
    -> Saída:
    {{"status":"bad","score":0.15,"comment":"horário divergente"}}

    Agora avalie:
    EN: \"\"\"{src}\"\"\"
    PT: \"\"\"{tgt}\"\"\"
    chapter_en: \"\"\"{chapter_en}\"\"\"
    chapter_pt: \"\"\"{chapter_pt}\"\"\"
    final_score_pipeline: {final_score if final_score is not None else 'null'}

    Saída JSON:
    """).strip()
