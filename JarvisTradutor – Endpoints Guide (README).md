# 📚 JarvisTradutor – Endpoints Guide

Este README reúne todos os endpoints das três APIs usadas atualmente, com resumo, payloads, exemplos `curl`, observações e dicas.  
**Ajuste as portas/hosts conforme seu ambiente.**

---

## 🟦 Backend Java (Spring Boot)

**Base URL:** `http://localhost:8080`  
**Auth:** Não configurado  
**Content-Type:** JSON para CRUD, multipart para import

---

### 1. `POST /glossary/bulk`

**Descrição:** Upsert em lote de termos do glossário.

| Campo      | Tipo     | Obrigatório | Observação                                    |
|------------|----------|-------------|-----------------------------------------------|
| src        | string   | Sim         | termo origem (único por casefold)             |
| tgt        | string   | Sim         | termo destino                                 |
| note       | string   | Não         |                                               |
| approved   | boolean  | Não         | default: true                                 |
| priority   | int      | Não         | default: 0. Maior prioridade “ganha” conflitos|

**Exemplo curl:**
```
curl -X POST http://localhost:8080/glossary/bulk \
  -H "Content-Type: application/json" \
  -d '[
    { "src": "dragon rider", "tgt": "cavaleiro de dragão", "priority": 10 },
    { "src": "outpost", "tgt": "guarnição" }
  ]'
```

**Resposta:**
```json
{ "ok": true, "affected": 2 }
```

---

### 2. `POST /tm/import`

**Descrição:** Importa corpus paralelo (EN↔PT) em arquivo TSV/CSV.  
Aplica filtros de qualidade no servidor.

| Parâmetro   | Tipo   | Obrigatório | Observação                  |
|-------------|--------|-------------|-----------------------------|
| delimiter   | string | Sim         | `%09` para TAB, `,` para CSV|

**Exemplo curl:**
```
curl -X POST "http://localhost:8080/tm/import?delimiter=%09" \
  -H "Content-Type: multipart/form-data" \
  -F "file=@/caminho/corpus.tsv"
```

**Resposta:**
```json
{ "ok": true, "rows": 15000 }
```

**Formato esperado por linha:**  
`src <delim> tgt [lang_src] [lang_tgt] [quality]`  
Colunas 3–5 são opcionais (default: lang_src=en, lang_tgt=pt).

---

### 3. `GET /tm/lookup`

**Descrição:** Retorna a melhor tradução existente para o texto `src`.

| Parâmetro | Tipo   | Obrigatório | Observação      |
|-----------|--------|-------------|-----------------|
| src       | string | Sim         | frase origem    |

**Exemplo curl:**
```
curl -G "http://localhost:8080/tm/lookup" \
  --data-urlencode "src=The dragon flies at dawn."
```

**Resposta:**
```json
{ "src": "The dragon flies at dawn.", "tgt": "O dragão voa ao amanhecer." }
```
Se não encontrar acima do limiar:
```json
{ "src": "The dragon flies at dawn.", "tgt": null }
```

---

### 4. `POST /tm/learn`

**Descrição:** Aprendizado online – insere nova dupla src → tgt na TM e grava o embedding.

| Parâmetro | Tipo    | Obrigatório | Observação      |
|-----------|---------|-------------|-----------------|
| src       | string  | Sim         |                 |
| tgt       | string  | Sim         |                 |
| quality   | double  | Não         |                 |

**Exemplo curl:**
```
curl -X POST "http://localhost:8080/tm/learn" \
  --data-urlencode "src=Thank you" \
  --data-urlencode "tgt=Obrigado" \
  --data-urlencode "quality=0.95"
```

**Resposta:**
```json
{ "ok": true }
```

---

## 🟩 Embeddings API (FastAPI)

**Base URL:** `http://localhost:8001`  
**Auth:** Não configurado  
**Observação:** Use `uvicorn` no Windows; em Docker/Linux pode usar `gunicorn` + `uvicorn` workers.

---

### 1. `GET /healthz`

**Descrição:** Health check simples (modelo e device).

**Exemplo curl:**
```
curl http://localhost:8001/healthz
```

**Resposta:**
```json
{ "ok": true, "model": "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2", "device": "cpu" }
```

---

### 2. `GET /readyz`

**Descrição:** Readiness probe (útil em K8s / balanceadores).

**Exemplo curl:**
```
curl http://localhost:8001/readyz
```

**Resposta:**
```json
{ "ready": true }
```

---

### 3. `POST /embed`

**Descrição:** Gera embeddings (vetores semânticos) para uma lista de textos.

| Campo      | Tipo           | Obrigatório | Observação                  |
|------------|----------------|-------------|-----------------------------|
| texts      | array de string| Sim         |                             |
| normalize  | bool           | Não         | Se true, normaliza L2       |

**Exemplo curl:**
```
curl -X POST http://localhost:8001/embed \
  -H "Content-Type: application/json" \
  -d '{
    "texts": ["The dragon flies at dawn.", "O dragão voa ao amanhecer."],
    "normalize": true
  }'
```

**Resposta:**
```json
{
  "model": "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
  "dims": 384,
  "vectors": [[...], [...]]
}
```

---

## 🐍 Worker Python – Tradução (FastAPI) (opcional / referência)

**Status:** Se você manteve o Worker apenas para tradução, este endpoint é o principal.  
Glossário e TM agora vivem no Backend Java.

**Base URL:** `http://localhost:8002` (ajuste conforme seu setup)

---

### `POST /translate/epub`

**Descrição:** Recebe um `.epub`, traduz capítulo por capítulo, aplica Glossário/TM (se seu Worker estiver consultando o Java), roda QualityInspector e, se habilitado, faz retradução seletiva nos piores trechos.

| Campo           | Tipo   | Obrigatório | Observação         |
|-----------------|--------|-------------|--------------------|
| file            | arquivo| Sim         | EPUB               |
| retranslate_bad | bool   | Não         | default: true      |

**Exemplo curl:**
```
curl -X POST "http://localhost:8002/translate/epub" \
  -H "Content-Type: multipart/form-data" \
  -F "file=@/caminho/livro.epub" \
  -F "retranslate_bad=true"
```

**Resposta:**
```json
{ "ok": true, "output": "/outputs/livro.pt.epub", "report_rows": 1243 }
```

---

> **Dica:** Mantenha coerência entre os serviços.  
> Glossário e TM agora vivem no Backend Java.

---