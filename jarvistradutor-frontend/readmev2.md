perfeito! atualizei o documento inteiro para refletir o novo desenho com **inbox (bookpair/corpora)**, **staging**, **merge idempotente**, os **novos controllers** e o fluxo de consolidação. Mantive a estrutura simples de ler e acrescentei cURLs e SQL quando ajuda.

---

# 📚 JarvisTradutor – Documentação Técnica (Atualizada)

## 1) Visão Geral

O **JarvisTradutor** é uma plataforma para importar, alinhar e curar pares paralelos (EN↔PT), gerando uma **TM (Translation Memory)** com **embeddings** e **ocorrências** por série/livro.
Agora temos uma camada de **inbox** para curadoria humana antes de consolidar na TM.

**Objetivos principais**

* Importar de **corpora** e de **pares de EPUBs de livros**.
* Permitir **curadoria** (aprovar/rejeitar) antes de entrar na TM.
* Consolidar em `tm`, `tm_embeddings`, `tm_occurrence` com segurança e sem duplicidade.

---

## 2) Arquitetura

### 🟦 Backend (Spring Boot, Java 21)

* Importação de TSV/CSV/EPUBs.
* Staging e **inbox** (novidade).
* Consolidação para TM (upsert/merge).
* Endpoints para curadoria de inbox.

### 🟩 Embeddings API (FastAPI, Python)

* `POST /embed` → SentenceTransformers.
* Suporte a batch e normalização.

### 🐘 Banco (PostgreSQL 15+ com `pgvector`)

* Tabelas centrais (`tm`, `tm_embeddings`, `tm_occurrence`) + **inbox** e **staging**.

---

## 3) Modelo de Dados

### 3.1 Tabelas centrais

```sql
CREATE TABLE tm (
  id BIGSERIAL PRIMARY KEY,
  src TEXT NOT NULL,
  tgt TEXT NOT NULL,
  lang_src TEXT NOT NULL,
  lang_tgt TEXT NOT NULL,
  quality DOUBLE PRECISION,
  created_at TIMESTAMP DEFAULT now(),
  UNIQUE (src, tgt, lang_src, lang_tgt)
);

CREATE TABLE tm_embeddings (
  tm_id BIGINT PRIMARY KEY REFERENCES tm(id),
  emb_src VECTOR,
  emb_tgt VECTOR
);

CREATE TABLE tm_occurrence (
  id BIGSERIAL PRIMARY KEY,
  tm_id BIGINT REFERENCES tm(id),
  series_id BIGINT,
  book_id BIGINT,
  chapter TEXT,
  location TEXT,
  source_tag TEXT,
  quality_at_import DOUBLE PRECISION,
  created_at TIMESTAMP DEFAULT now()
);
```

### 3.2 Inbox (curadoria humana)

```sql
-- Pares vindos de livros (EPUB pair)
CREATE TABLE IF NOT EXISTS tm_bookpair_inbox (
  id          bigserial PRIMARY KEY,
  src         text NOT NULL,
  tgt         text NOT NULL,
  lang_src    text NOT NULL,
  lang_tgt    text NOT NULL,
  quality     double precision,
  series_id   bigint,
  book_id     bigint,
  chapter     text,
  location    text,
  source_tag  text,
  status      text NOT NULL DEFAULT 'pending', -- pending|approved|rejected
  reviewer    text,
  reviewed_at timestamp,
  created_at  timestamp DEFAULT now()
);
-- Índice único lógico (criamos como UNIQUE INDEX por causa das expressões COALESCE)
CREATE UNIQUE INDEX IF NOT EXISTS uk_bookpair_inbox
  ON tm_bookpair_inbox (
    src, tgt, lang_src, lang_tgt,
    (COALESCE(series_id,0)), (COALESCE(book_id,0)), (COALESCE(source_tag,''))
  );

-- Embeddings (staging) dos bookpairs
CREATE TABLE IF NOT EXISTS tm_bookpair_emb_staging (
  src       text NOT NULL,
  tgt       text NOT NULL,
  lang_src  text NOT NULL,
  lang_tgt  text NOT NULL,
  emb_src   vector(384),
  emb_tgt   vector(384),
  quality   double precision,
  created_at timestamp default now()
);
```

```sql
-- Corpora “soltos” (não estruturados por livro)
CREATE TABLE IF NOT EXISTS tm_corpora_inbox (
  id         bigserial PRIMARY KEY,
  src        text NOT NULL,
  tgt        text NOT NULL,
  lang_src   text NOT NULL,
  lang_tgt   text NOT NULL,
  quality    double precision,
  source_tag text,
  status     text NOT NULL DEFAULT 'pending',
  reviewer   text,
  reviewed_at timestamp,
  created_at timestamp DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_corpora_inbox
  ON tm_corpora_inbox (
    src, tgt, lang_src, lang_tgt, (COALESCE(source_tag,''))
  );

CREATE TABLE IF NOT EXISTS tm_corpora_emb_staging (
  src       text NOT NULL,
  tgt       text NOT NULL,
  lang_src  text NOT NULL,
  lang_tgt  text NOT NULL,
  emb_src   vector(384),
  emb_tgt   vector(384),
  quality   double precision,
  created_at timestamp default now()
);
```

### 3.3 Staging temporário (quando import por streaming)

* `tm_staging`, `tm_emb_staging`, `tm_occurrence_staging` (usados principalmente pelo import “clássico” CSV/TSV).
* No fluxo **EPUB pair** usamos **staging específico** do inbox: `tm_bookpair_inbox_staging` (criado/gerido pela `SchemaEnsurer`).

> Observação: índices úteis para UX no painel:

```sql
CREATE INDEX IF NOT EXISTS idx_bp_status ON tm_bookpair_inbox(status);
CREATE INDEX IF NOT EXISTS idx_bp_series_book ON tm_bookpair_inbox(series_id, book_id);
```

---

## 4) Fluxos de Dados

### 4.1 Import de **EPUB pair** → Inbox

1. **Extração** (EpubExtractor): lê XHTML, extrai `paragraph` ou `sentence`, limpa ruído.
2. **Alinhamento**:

   * `mode=length`: pareamento por posição.
   * `mode=embedding`: seleção por maior similaridade (cosine) entre vetores.
3. **Qualidade** (QualityFilter):

   * `ratioMin/ratioMax` (comprimento).
   * `placeholdersPreserved` (marcações preservadas).
   * `score = f(ratio, placeholders)`.
4. **Escrita**:

   * Linhas válidas → `tm_bookpair_inbox_staging` via COPY.
   * Embeddings (se `mode=embedding`) → arquivo temp → `tm_bookpair_emb_staging`.
5. **Merge**:

   * `MERGE` do staging para `tm_bookpair_inbox` (dedupe por (src,tgt,langs,series,book,source_tag), mantém **maior quality**, status default `pending`).

> Nenhum dado entra em `tm`/`tm_embeddings`/`tm_occurrence` ainda — só após aprovação.

### 4.2 Curadoria (Inbox Bookpair)

* Listar `pending`/`approved`/`rejected`.
* Aprovar/rejeitar (opcionalmente setando `reviewer`).
* (Opcional) editar metadados (`chapter`, `location`, `source_tag`) antes de consolidar.

### 4.3 Consolidação (Aprovados → TM)

1. **TM**: upsert do par (pega maior `quality`).
2. **TM_OCCURRENCE**: insere ocorrência com `series_id/book_id/chapter/location/source_tag/quality`.
3. **TM_EMBEDDINGS**: escolhe embeddings do maior `quality` (de `tm_bookpair_emb_staging`) e upsert por `tm_id`.
4. (Opcional) Limpeza do staging de embeddings.

### 4.4 Import por CSV/TSV (corpora)

* Similar ao “clássico” anterior, mas agora **opcionalmente** escrevemos em:

  * `tm_corpora_inbox` + `tm_corpora_emb_staging` (para curadoria), **ou**
  * no “fast-path” antigo (`tm_staging` → `tm`) quando a fonte já é confiável.

---

## 5) Principais Classes (modularizadas)

* `EpubExtractor` → extrai blocos dos EPUBs.
* `EmbeddingService` → cliente do `/embed`.
* `QualityFilter` → valida e calcula score (ratio + placeholders).
* `SchemaEnsurer` → cria/garante tabelas de inbox e staging específicos (bookpair/corpora).
* `InboxWriter` → abre COPY para `tm_bookpair_inbox_staging`, faz `mergeBookpairInboxFromStaging(...)`.
* `EPUBPairImportServiceImpl` → orquestra o fluxo de import de EPUB pair e grava no inbox.
* `BookpairInboxService` (+ Impl) → listar/contar/aprovar/rejeitar/consolidar.
* `BookpairInboxController` → endpoints REST.

---

## 6) Endpoints

### 6.1 Import EPUB Pair

`POST /tm/import/epub-pair`

* Params: `level`, `mode`, `srcLang`, `tgtLang`, `minQuality`, `seriesId`, `bookId`, `sourceTag`
* Files: `fileEn`, `filePt`

```bash
curl -X POST "http://localhost:8080/tm/import/epub-pair?level=sentence&mode=embedding&srcLang=en&tgtLang=pt&minQuality=0.7&seriesId=1&bookId=2&sourceTag=mistborn" \
  -F "fileEn=@/path/en.epub" \
  -F "filePt=@/path/pt.epub"
```

**Resposta:**

```json
{
  "ok": true,
  "inserted": 1234,
  "skipped": 87,
  "avgQuality": 0.81,
  "chapters": 1,
  "examples": [ { "src": "...", "tgt": "...", "quality": 0.9 } ]
}
```

> Observação: os valores acima entram no **inbox**; o `status` default é `pending`.

### 6.2 Inbox Bookpair (curadoria)

Base: `/tm/inbox/bookpair`

* **GET** `/tm/inbox/bookpair?status=pending&seriesId=1&bookId=2&sourceTag=mist&size=50&page=0`
* **POST** `/{id}/approve?reviewer=<nome>`
* **POST** `/{id}/reject?reviewer=<nome>&reason=<texto>`
* **POST** `/consolidate` → consolida **todos os `approved`** para TM.

Exemplos:

```bash
# Listar pendentes
curl "http://localhost:8080/tm/inbox/bookpair?status=pending&page=0&size=50"

# Aprovar item
curl -X POST "http://localhost:8080/tm/inbox/bookpair/123/approve?reviewer=dan"

# Rejeitar item
curl -X POST "http://localhost:8080/tm/inbox/bookpair/124/reject?reviewer=dan&reason=misaligned"

# Consolidar (aprovados -> TM/TM_OCCURRENCE/TM_EMBEDDINGS)
curl -X POST "http://localhost:8080/tm/inbox/bookpair/consolidate"
```

> Extensões fáceis:
>
> * `PATCH /tm/inbox/bookpair/{id}` para editar `chapter`, `location`, `sourceTag`.
> * `POST /tm/inbox/bookpair/bulk-approve` / `bulk-reject` (ids[]).

### 6.3 Import CSV/TSV (clássico)

`POST /tm/import` (mantido)

* Modo upload “rápido” ou **resumível** (checkpoint).
* Pode alimentar diretamente `tm_staging` (fast-path) **ou** escrever no `tm_corpora_inbox` (curadoria).

---

## 7) Fluxo end-to-end (resumo)

1. **Import EPUB pair**
   → `tm_bookpair_inbox_staging` (COPY)
   → `MERGE` → `tm_bookpair_inbox` (`pending`)
   → (opcional) embeddings → `tm_bookpair_emb_staging`.

2. **Curadoria**
   → UI lista `pending`, aprova/rejeita, edita metadados.

3. **Consolidação** (`/tm/inbox/bookpair/consolidate`)
   → `tm` (upsert melhor quality)
   → `tm_occurrence` (series/book/chapter/location/source_tag)
   → `tm_embeddings` (melhor vector por `tm_id`).

4. **Consulta/uso**
   → `tm_query`/lookup/serviços futuros (busca híbrida, glossário, etc.).

---

## 8) Boas Práticas & Notas

* **Idempotência**:

  * Staging → Inbox via `MERGE` (dedupe por (src,tgt,langs,series,book,source_tag) e mantém **maior quality**).
  * Consolidar aprovados é idempotente (usar `ON CONFLICT`/update com `GREATEST`).

* **Erros de duplicidade**:

  * Quando escrevendo **direto** no `tm_bookpair_inbox` sem staging/merge, você pode ver `duplicate key` (por causa do UNIQUE INDEX).
  * Solução: **sempre** escrever no `tm_bookpair_inbox_staging` e chamar o **`mergeBookpairInboxFromStaging`**.

* **Campos nulos na Inbox**:

  * `chapter/location/source_tag/reviewer/reviewed_at` podem iniciar nulos; são preenchidos via UI antes da consolidação (ou ignorados se não aplicáveis).
  * `status` começa **`pending`**.

* **Embedding dimension**: ajuste `vector(384)` conforme o modelo do SentenceTransformers.

---

## 9) Setup Rápido

**`application.yml` (trecho):**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/jarvis
    username: jarvis
    password: jarvis
  jpa:
    open-in-view: false
    show-sql: false
```

**Embeddings API**:

* `uvicorn app:app --host 0.0.0.0 --port 8001`
* Endpoint: `POST /embed`
  Payload: `{ "texts": ["..."], "normalize": true }`

---

## 10) Roadmap (curto prazo)

* **Endpoints Corpora Inbox**: gêmeos do Bookpair Inbox.
* **Bulk actions** (approve/reject).
* **PATCH de metadados** no inbox.
* **Busca híbrida** (texto + embedding) com filtro por série/livro.
* **Jobs** de consolidação programados (opcional).

---

### Anexos (nomes/classes citadas)

* `SchemaEnsurer.ensureBookpairSchemas()` → cria:

  * `tm_bookpair_inbox`
  * `tm_bookpair_inbox_staging`
  * `tm_bookpair_emb_staging`
  * índices e MERGE-friendly structures

* `InboxWriter.openBookpairInboxStagingCopy()` → abre COPY para staging (writer autoclose → `endCopy`).

* `InboxWriter.mergeBookpairInboxFromStaging(jdbc)` → faz o `MERGE` staging → inbox e limpa staging.

* `BookpairInboxService.consolidateApproved()` → TM/TM_OCCURRENCE/TM_EMBEDDINGS.

---

Se quiser, eu já acrescento uma **seção de “Contrato do Frontend”** com os JSONs de request/response dos endpoints de inbox (incluindo PATCH/bulk).
