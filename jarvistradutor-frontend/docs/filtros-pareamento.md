bom dia! 👋 segue um passo-a-passo do que o **pipeline de importação de pares** faz hoje — do EPUB até cair na `tm_bookpair_inbox`, incluindo onde cada filtro entra e por que existe.

# 1) Extração dos blocos do EPUB

**Classe:** `EpubExtractor`

* **Lê cada recurso XHTML** do EPUB e pega texto de `p`, `li` e `div` “de texto”.
* **Normalização “leve” de pontuação/Unicode** (aspas, travessão, múltiplos espaços).
* **Heurísticas de diálogo/narração**:

  * `glueDialogueNarration(...)`: se um parágrafo curto narrativo (com “disse/perguntou/said...”) vem logo antes de fala, junta os dois.
  * `splitDialogueIfMixed(...)`: se um parágrafo tem narração + fala, tenta separar em 2 blocos.
* **sentence vs paragraph**:

  * `level="paragraph"`: cada bloco é um parágrafo.
  * `level="sentence"`: quebra por sentenças com regex `(?<=[.!?…])\s+`.
* **Ruído e duplicatas**:

  * `looksFooter(...)`: remove lixo curtíssimo (numeração, cabeçalhos/rodapés).
  * `dedupeConsecutive(...)`: tira duplicados consecutivos (mantendo posição).
* **Sai como `List<Block>`** com **posição** (`spineIdx`, `blockIdx`, `sentIdx`) e **`chapterTitle`** (tentado via TOC).

> Por que isso importa? A qualidade do alinhamento lá na frente depende de blocos “bem formados” (fala e narração separadas quando possível) e da **posição/capítulo** para restringir candidatos.

# 2) Alinhamento EN↔PT

Você escolhe o modo:

* **`length`** → `LengthAligner`: pareia na ordem (i==j), **sim=0.0** (sem similaridade).
* **`embedding`** → `EmbeddingAlignerHungarian`: otimização global com custo.

## EmbeddingAlignerHungarian (modo recomendado)

**Classe:** `EmbeddingAlignerHungarian`

* **Embeddings** (via `EmbeddingService` com batching):

  * unitário (cada bloco) e **de contexto** (prev+curr+next).
* **Matriz de custo `N x M`**:

  * **Regra dura de capítulo:** se `chapterTitle` normalizado difere → **custo gigante (BIG)** (proíbe cruzar capítulos).
  * **Banding por posição:** se `|spineSrc - spineTgt| > BAND_SPINE` (ex.: 3) → **BIG**.
  * **Similaridade**: `simBlend = 0.7*simUnit + 0.3*simCtx` (cosseno, clamp 0..1).
  * **Penalidade posicional “fina”**: `posPenalty(...)` mistura diferença de capítulo (0/1), distância de spine (0..1) e de bloco (0..1).
  * **Custo final**: `W_SIM*(1 - simBlend) + W_POS*pos`.
* **Hungarian** resolve o emparelhamento 1-para-1 globalmente ótimo.
* **Pós-processo opcional**: `AlignmentRepairer.repair(...)`

  * Para falas fracas, tenta **fundir** vizinhos 1→2 ou 2→1 se a similaridade de embeddings melhorar.
* **Filtro final de par**: aceita apenas com `sim ≥ MIN_SIM` (ex.: 0.70).
* **Saída**: `List<AlignedPair>` com textos, posições e `sim`.

> Filtros nesta etapa:
>
> * **Capítulo igual** (regra dura).
> * **Banding por posição** (spine).
> * **Limiar de similaridade** (MIN_SIM).

# 3) Pré-filtros “baratos” (ratio/placeholders)

**Classe:** `EPUBPairImportServiceImpl` usando `QualityFilter`
Para cada `AlignedPair` aceito no passo 2:

* **Normalização de diálogo** (`TextNormalizer.normalizeDialogue`) para tirar variações de aspas/travessão.
* **Dedupe intra-lote** por chave `(src,tgt,langs,location)`.
* **`lengthRatio` (b/a)**: exige **`ratioMin ≤ r ≤ ratioMax`** (defaults 0.5–2.0).
* **`placeholdersPreserved`**: exige que placeholders `{x}`, `%s`, `<tag>`, `${x}` do `src` **apareçam no `tgt`**.
* **`qualityScore`** (0..1): mistura 70% do quão perto o ratio ficou de 1.0 + 30% de placeholders OK.

Se reprovar em qualquer um → **conta em “rejeitados nos filtros baratos”** e não segue.

# 4) Enriquecimento de qualidade (QE/BT) — inline e/ou pós-merge

Para os pares que passaram:

* **QE inline** (`enrichWithQE`) — sempre que ligado:

  * Chama seu **`QeClient`** (`/qe`) em lote (batched).
  * **Normaliza** o score pra 0..1 (`normalizeQeTo01`), pois alguns modelos devolvem [-1..1] ou [0..1].
  * **`final_score`** (0..1) = `0.45*sim + 0.35*qe + 0.20*qRule`.
* **BT inline (opcional)** (`enrichWithBT`):

  * Se `jarvis.bt.inline=true`, chama **`BtClient`**:

    * `/btcheck/score` → **chrF (0..100)** direto entre `src` e `tgt`.
    * (Você também tem endpoint de **backtranslate** se quiser checar `chrF(src, BT(tgt→src))`).
  * Preenche `bt_chrf` no staging.

> Se a máquina está sem GPU/é lenta, normalmente deixamos **BT inline desligado** e rodamos só o **job pós-merge** (item 7) com filtro de “apenas final_score baixo”.

# 5) Escrita no **staging** (COPY)

* Abrimos um `COPY` para **`tm_bookpair_inbox_staging`** e **streamamos CSV** com:

  * `src, tgt, lang_src, lang_tgt, quality, series_id, book_id, chapter, location, source_tag, qe_score, bt_chrf, final_score`.
* **Embeddings** (se modo `embedding`):

  * janelamos em buffer (`bufSrc/Tgt/Q`) e chamamos `EmbeddingService.flushEmbeddingsToFile(...)` que:

    * chama `/embed` (batched) e escreve um **arquivo temporário CSV** para `tm_bookpair_emb_staging`.
  * **Gate opcional** `embedOnlyApproved`: só gera embedding se `final_score ≥ 0.55` (ou desliga e gera de todos).

# 6) **Merge** do staging → inbox

**Classe:** `InboxWriter.mergeBookpairInboxFromStaging`

* Deduplica staging em `_bp_dedup` por `(src,tgt,lang_src,lang_tgt, series_id, book_id, source_tag)`, preferindo maior `quality`/`created_at`.
* **MERGE** na **`tm_bookpair_inbox`**:

  * **INSERT** com `status` calculado:

    * `good` se `final_score ≥ goodMin` **e** `qe_score ≥ qeGoodMin`.
    * `suspect` se `final_score ≥ suspectMin`.
    * senão `bad`.
  * **UPDATE** se já existia:

    * `quality = GREATEST(existing, new)`.
    * **Promove `status`** se novos scores justificarem (nunca rebaixa “good”).
    * Preenche/atualiza `qe_score`, `bt_chrf`, `final_score` quando vierem nulos.
* Limpa staging.

> Aqui os **thresholds** vêm de config:
>
> * `goodMin` (ex.: 0.80), `suspectMin` (ex.: 0.55), `qeGoodMin` (ex.: 0.75).

# 7) Pós-passo: marcar livro + **BTCheck** por DB

* Marca `book.pairs_imported=true`.
* Se `jarvis.btcheck.auto=true`, chama `BtCheckService.runBtCheck(bookId, limit)`.

  * **Filtro para rodar BT só onde precisa** (economia de CPU/GPU):

    * `bt_chrf IS NULL` **e** `status IN ('suspect','pending')`.
    * Se `jarvis.btcheck.only-low-final=true`, acrescenta: `final_score IS NULL OR final_score ≤ final-threshold` (ex.: 0.65).
  * Processo:

    1. Faz **backtranslate** `tgt → srcLang` **em lote** (`BtClient.backtranslateBatch`).
    2. Calcula **chrF** (`Chrf.chrf(src, backtranslation)`) → 0..100.
    3. **Ajusta levemente o `final_score`** sem refazer embeddings:

       * `newFinal = 0.8*final_score + 0.2*(chrF/100)` (se `final_score` nulo, vira `chrF/100`).
    4. **Atualiza `bt_chrf`, `final_score`, `status`**:

       * `good` se `newFinal ≥ goodMin`, `qe_score ≥ qeGoodMin` **e** `chrF ≥ chrfMin` (ex.: 55.0).
       * `suspect` se `newFinal ≥ suspectMin` **ou** `chrF ≥ chrfMin`.
       * senão `bad`.

# 8) Logs e métricas

* Você vê no log:

  * Quantos **alinhados**.
  * Quantos **gravados no staging** e **rejeitados nos filtros baratos**.
  * Quantos **mergados** no inbox.
  * E, quando habilitado, quantos **atualizados pelo btcheck**.

# 9) Parâmetros principais (ajustáveis)

* **Extração/normalização:** você já usa defaults internos.
* **Alinhamento (embedding):**

  * `MIN_SIM` (ex.: 0.70), `BAND_SPINE=3`, `W_SIM=0.75`, `W_POS=0.25`.
  * Regra dura: **mesmo capítulo** (capítulo normalizado).
* **Filtros baratos:** `jarvis.tm.ratio-min/max` (ex.: 0.5–2.0), placeholders obrigatórios.
* **Score final:** `final_score = 0.45*sim + 0.35*qe + 0.20*qRule`.
* **Classificação:** `goodMin`, `suspectMin`, `qeGoodMin`.
* **Embeddings:** `jarvis.embeddings.only-approved` (true/false).
* **BT inline:** `jarvis.bt.inline` (false recomendado sem GPU).
* **BT pós-merge:**

  * `jarvis.btcheck.auto`, `jarvis.btcheck.limit-per-run`.
  * **Economia:** `jarvis.btcheck.only-low-final`, `jarvis.btcheck.final-threshold`.
  * Corte de promoção BT: `jarvis.bt.promote.chrf-min` (ex.: 55.0).

---

## Em resumo

1. **Extrai e higieniza** blocos com posição e capítulo.
2. **Alinha** por embeddings com regra dura de **mesmo capítulo**, banding por **spine**, e **Hungarian**.
3. Passa nos **filtros baratos** (ratio/placeholders).
4. **Enriquece** com **QE** (sempre) e **BT** (opcional inline).
5. **Copia** para **staging** (e embeddings para staging próprio), depois **MERGE** no **inbox** com **status**.
6. Marca o livro e (opcional) roda **BTCheck** do banco, **só** nos pares “fracos”, calculando **chrF** de backtranslation e **refinando** o `final_score`/`status`.

Se quiser, eu resumo isso em um diagrama/README pra deixar no repositório.
