# Guia do Estagiário – Importação de Pares de Livros (EPUB EN/PT)

> Versão: **JarvisTradutor – fluxo de inbox/aprovação/consolidação**

---

## 0) Pré-requisitos

* **Backend Spring** rodando em `http://localhost:8080`
* **Embeddings API** (FastAPI/Python) rodando em `http://localhost:8001`
* **PostgreSQL ≥ 15** com extensão `pgvector`
* **Catálogo** já cadastrado via `/api/catalog/import-excel`

  > Tudo que subir pelo catálogo fica marcado como **Baixado=true** (downloaded).

---

## 1) Importar os dois EPUBs paralelos

**Endpoint**

```
POST /import/epub-pair
```

**Query params**

* `level`: `paragraph` | `sentence`
* `mode`: `length` | `embedding`
* `srcLang` / `tgtLang` (ex.: `en` / `pt`)
* `minQuality` (ex.: `0.7`)
* `seriesId`, `bookId` (contexto do catálogo)
* `sourceTag` (ex.: `gdrive/lotr-v1`)

**cURL**

```bash
curl -X POST "http://localhost:8080/import/epub-pair?level=sentence&mode=embedding&srcLang=en&tgtLang=pt&minQuality=0.7&seriesId=1&bookId=3&sourceTag=teste_lote_01" \
  -H "Content-Type: multipart/form-data" \
  -F "fileEn=@/caminho/english.epub" \
  -F "filePt=@/caminho/portugues.epub"
```

**O que acontece por baixo:**

1. Extração de blocos (epublib + Jsoup)
2. Alinhamento (`length` ou `embedding`)
3. Filtro de qualidade (razão de tamanho + placeholders)
4. Escrita nas tabelas:

   * `tm_bookpair_inbox` → **status = `pending`**
   * `tm_bookpair_emb_staging` → **vetores** (`emb_src`, `emb_tgt`, `quality`)

> Campos como `chapter`, `location`, `reviewer`, `reviewed_at` e `source_tag` podem ficar nulos se não forem informados no import. `status` entra como `pending`.

---

## 2) Ver o que caiu no Inbox

**Endpoint**

```
GET /tm/inbox/bookpair
```

**Filtros**

* `status`: `pending` | `approved` | `rejected`
* `seriesId`, `bookId`
* `sourceTag`
* `page`, `size`

**cURL**

```bash
curl -G "http://localhost:8080/tm/inbox/bookpair" \
  --data-urlencode "status=pending" \
  --data-urlencode "seriesId=1" \
  --data-urlencode "bookId=3" \
  --data-urlencode "page=0" \
  --data-urlencode "size=50"
```

**Tabelas após o import**

* `tm_bookpair_inbox` → preenchida com **PENDENTES**
* `tm_bookpair_emb_staging` → preenchida com **embeddings**

> Nenhuma tabela “final” é alterada ainda.

---

## 3) Aprovar ou Rejeitar Itens

**Aprovar**

```
POST /tm/inbox/bookpair/{id}/approve?reviewer=seu_nome
```

```bash
curl -X POST "http://localhost:8080/tm/inbox/bookpair/123/approve?reviewer=joao"
```

**Rejeitar**

```
POST /tm/inbox/bookpair/{id}/reject?reviewer=seu_nome&reason=motivo_opcional
```

```bash
curl -X POST "http://localhost:8080/tm/inbox/bookpair/123/reject?reviewer=joao&reason=alinhamento_ruim"
```

> Efeito: atualiza `status`, `reviewer`, `reviewed_at`.
> Se quiser armazenar `reason`, adicione coluna `reason TEXT` em `tm_bookpair_inbox`.

---

## 4) Consolidar os **Aprovados** (mover para tabelas finais)

**Endpoint**

```
POST /tm/inbox/bookpair/consolidate
```

**cURL**

```bash
curl -X POST "http://localhost:8080/tm/inbox/bookpair/consolidate"
```

**Efeitos da consolidação**

* **`tm`**: upsert do par (`src`,`tgt`,`lang_src`,`lang_tgt`), mantendo **maior `quality`**
* **`tm_occurrence`**: `INSERT` por aprovação (guarda `series_id`, `book_id`, `chapter`, `location`, `source_tag`, `quality_at_import`)
* **`tm_embeddings`**: upsert por `tm_id`, selecionando vetores do aprovado com **maior `quality`** (fonte: `tm_bookpair_emb_staging`)

**Após consolidar**

* `tm` → tem os pares aprovados
* `tm_occurrence` → 1 linha por aprovação com contexto série/livro
* `tm_embeddings` → vetores vinculados ao `tm.id`

> `tm_bookpair_inbox` permanece com o histórico (`approved`/`rejected`).
> `tm_bookpair_emb_staging` continua como buffer; pode ser limpo em manutenção.

---

## 5) SQLs de Conferência

Pendências por status:

```sql
SELECT status, COUNT(*) FROM tm_bookpair_inbox GROUP BY status;
```

Aprovados prontos para consolidar:

```sql
SELECT COUNT(*) FROM tm_bookpair_inbox WHERE status = 'approved';
```

Totais nas tabelas finais:

```sql
SELECT COUNT(*) FROM tm;               -- pares na TM
SELECT COUNT(*) FROM tm_occurrence;    -- ocorrências
SELECT COUNT(*) FROM tm_embeddings;    -- embeddings consolidados
```

---

## 6) Troubleshooting

### `DataBufferLimitException: Exceeded limit on max bytes to buffer : 67108864`

* **Causa**: resposta JSON do `/embed` muito grande em uma chamada.
* **Solução**: batching no `EmbeddingService` (ex.: 128–256 itens por batch) e aumentar o `maxInMemorySize`.

Exemplo de config (Spring):

```yaml
jarvis:
  embed:
    batch-size: 256          # reduza para 128 se necessário
    timeout-seconds: 60

# Se montar o WebClient manualmente, também dá para aumentar:
# ExchangeStrategies.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(128 * 1024 * 1024))
```

### Duplicata ao inserir no inbox

* O fluxo usa **staging + MERGE** para deduplicar por
  `(src,tgt,lang_src,lang_tgt,COALESCE(series_id,0),COALESCE(book_id,0),COALESCE(source_tag,''))`,
  ficando com o maior `quality`.
* Se inserir direto no inbox, você pode violar a unique key; prefira o fluxo padrão.

---

## 7) Resumo Rápido

1. **Importar EPUBs** → `POST /import/epub-pair`
   *Preenche* `tm_bookpair_inbox (pending)` e `tm_bookpair_emb_staging`
2. **Listar pendentes** → `GET /tm/inbox/bookpair?status=pending`
3. **Aprovar/Rejeitar** → `POST /tm/inbox/bookpair/{id}/approve|reject`
4. **Consolidar aprovados** → `POST /tm/inbox/bookpair/consolidate`
   *Atualiza* `tm`, `tm_occurrence`, `tm_embeddings`

---

## 8) Dicas

* Use `sourceTag` para diferenciar lotes (ex.: `drive/lotr_v2`).
* `level=sentence` funciona melhor com `mode=embedding` para textos longos.
* `minQuality` = 0.7–0.8 costuma filtrar ruído sem perder muito recall.
* Se for importar muitos capítulos, vá por lotes menores para facilitar revisão.

---

**Pronto!** Cole esse `.md` no repositório (ex.: `docs/guia-import-epub.md`) e compartilhe com a equipe.
