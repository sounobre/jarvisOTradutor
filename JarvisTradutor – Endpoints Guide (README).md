# 📚 JarvisTradutor – Documentação Técnica Completa

## 1. Introdução

O **JarvisTradutor** é uma plataforma para tradução assistida de textos e livros, com foco em literatura fantástica (romances, séries longas, glossários específicos por obra).
Seu objetivo é **criar, alinhar e armazenar memórias de tradução (TM)** e **vetores semânticos (embeddings)** para:

* Auxiliar traduções automáticas de alta qualidade.
* Manter consistência em glossários específicos de cada série/livro.
* Permitir análise futura de qualidade comparando traduções automáticas com versões oficiais.
* Servir de base para **fine-tuning** de modelos de tradução.

---

## 2. Arquitetura Técnica

O sistema é dividido em **3 camadas principais**:

### 🟦 Backend Java (Spring Boot)

* Orquestração, regras de negócio e banco de dados.
* Serviços:

  * Importação de corpus paralelo (TSV, CSV, EPUBs).
  * Deduplicação, filtragem por qualidade.
  * Consolidação staging → TM principal.
  * Gestão de glossário, séries e livros.

### 🟩 Embeddings API (FastAPI, Python)

* Geração de embeddings (vetores semânticos) usando **SentenceTransformers**.
* Comunicação via HTTP (`/embed`).
* Permite alinhamento semântico de frases.

### 🐍 Worker Python (opcional)

* Focado na tradução direta de EPUBs.
* Integra com o backend Java para aplicar TM e glossários.
* Pode aplicar técnicas como retradução seletiva e pós-edição.

### 🔗 Banco de Dados (PostgreSQL + pgvector)

Tabelas principais:

* `tm` → memória de tradução consolidada.
* `tm_embeddings` → embeddings associados a pares da TM.
* `tm_staging` / `tm_emb_staging` → tabelas temporárias para importação.
* `tm_occurrence` → ocorrência dos pares, associando série, livro, capítulo e posição.
* `series` e `books` → catálogo de séries e livros, para manter glossários específicos.

---

## 3. Fluxo de Importação

1. **Recebe arquivos paralelos (TSV/CSV/EPUBs).**
2. **Normaliza textos:** remove ruídos, aplica regras de placeholders.
3. **Filtra por qualidade:** razão de comprimento, placeholders preservados, score final.
4. **Escreve em staging:**

   * `tm_staging` (pares texto).
   * `tm_emb_staging` (se embeddings forem gerados).
   * `tm_occurrence_staging` (associação série/livro).
5. **Consolida:**

   * `tm` recebe os pares únicos.
   * `tm_embeddings` recebe os vetores.
   * `tm_occurrence` recebe as ligações contextuais.

---

## 4. Classes Principais

### 📌 `TMImportServiceImpl`

Responsável pela importação de **corpora paralelos (TSV/CSV)**.
Funções principais:

* `importTsvOrCsvStreaming` → streaming via COPY.
* `importTxtResume` → importação resumível (checkpoint).
* Deduplicação por lote.
* Score de qualidade (`ratio + placeholders`).
* Envio de embeddings para API.

Trecho chave (flush embeddings):

```java
private void flushEmbeddingsBuffer(Writer outEmb,
                                   List<String> bufSrc,
                                   List<String> bufTgt,
                                   List<String> bufLangSrc,
                                   List<String> bufLangTgt,
                                   List<Double> bufQ,
                                   String embedMode) throws IOException {
    List<double[]> vecSrc = embedTexts(bufSrc, true);
    List<double[]> vecTgt = "both".equals(embedMode) ? embedTexts(bufTgt, true) : List.of();

    for (int i = 0; i < bufSrc.size(); i++) {
        String src = bufSrc.get(i);
        String tgt = bufTgt.get(i);
        String ls = bufLangSrc.get(i);
        String lt = bufLangTgt.get(i);
        Double q  = bufQ.get(i);

        String embSrc = vecSrc.isEmpty() ? "" : toVectorLiteral(vecSrc.get(i));
        String embTgt = (!vecTgt.isEmpty()) ? toVectorLiteral(vecTgt.get(i)) : "";

        outEmb.write(String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",%f\n",
                esc(src), esc(tgt), esc(ls), esc(lt), embSrc, embTgt, q));
    }
    bufSrc.clear(); bufTgt.clear(); bufLangSrc.clear(); bufLangTgt.clear(); bufQ.clear();
}
```

---

### 📌 `EPUBPairImportServiceImpl`

Importa e alinha **EPUBs paralelos (EN/PT)**.
Destaques:

* Extração de blocos com **epublib** + **Jsoup**.
* Alinhamento por **comprimento** ou **similaridade semântica (embeddings)**.
* Uso de **arquivos temporários** para staging de embeddings (evita backpressure).
* Integração com `tm_occurrence` (salva série/livro/capítulo).



Quer que eu continue agora até fechar **Controllers + Endpoints (com todos os exemplos curl)** ou prefere que eu já finalize este bloco e gere o **PDF parcial** para você dar uma olhada?


Show, meu amigo 🚀
Vou então complementar a documentação com os **Controllers + Endpoints (cURLs)** e fechar o documento completo em **Markdown**. Depois gero o **PDF final**.


---

## 5. Controllers e Endpoints

### 🔹 `GlossaryController`

#### `POST /glossary/bulk`

* Upsert em lote de termos do glossário.
* Resolve conflitos por prioridade (`priority` mais alto vence).

**Exemplo cURL:**

```bash
curl -X POST http://localhost:8080/glossary/bulk \
  -H "Content-Type: application/json" \
  -d '[
    { "src": "dragon rider", "tgt": "cavaleiro de dragão", "priority": 10 },
    { "src": "outpost", "tgt": "guarnição" }
  ]'
```

---

### 🔹 `TMImportController`

#### `POST /tm/import`

* Importa corpus paralelo via **arquivo TSV/CSV**.
* Aplica dedupe, score e staging → TM.

**Exemplo cURL:**

```bash
curl -X POST "http://localhost:8080/tm/import?delimiter=%09" \
  -H "Content-Type: multipart/form-data" \
  -F "file=@/caminho/corpus.tsv"
```

---

#### `GET /tm/lookup`

* Consulta tradução existente.

**Exemplo cURL:**

```bash
curl -G "http://localhost:8080/tm/lookup" \
  --data-urlencode "src=The dragon flies at dawn."
```

---

#### `POST /tm/learn`

* Aprendizado online – insere par src/tgt com embedding.

**Exemplo cURL:**

```bash
curl -X POST "http://localhost:8080/tm/learn" \
  --data-urlencode "src=Thank you" \
  --data-urlencode "tgt=Obrigado" \
  --data-urlencode "quality=0.95"
```

---

### 🔹 `EPUBPairImportController`

#### `POST /import/epub-pair`

* Importa dois EPUBs paralelos (ex: inglês/português).
* Gera staging em:

  * `tm_staging`
  * `tm_emb_staging` (opcional, se `mode=embedding`)
  * `tm_occurrence_staging` (contexto: série/livro/capítulo)

**Parâmetros:**

* `level` → `paragraph` ou `sentence`.
* `mode` → `length` ou `embedding`.
* `srcLang`, `tgtLang` → línguas (default: en/pt).
* `minQuality` → score mínimo.
* `seriesId`, `bookId`, `sourceTag` → metadados de ocorrência.

**Exemplo cURL:**

```bash
curl -X POST "http://localhost:8080/import/epub-pair?level=sentence&mode=embedding&srcLang=en&tgtLang=pt&minQuality=0.7&seriesId=1&bookId=3&sourceTag=fantasy_test" \
  -H "Content-Type: multipart/form-data" \
  -F "fileEn=@/livros/original.epub" \
  -F "filePt=@/livros/traducao.epub"
```

---

### 🔹 `SeriesController`

#### `POST /series`

* Cria uma nova série (ex: *Stormlight Archive*).

**cURL:**

```bash
curl -X POST http://localhost:8080/series \
  -H "Content-Type: application/json" \
  -d '{"name": "Stormlight Archive", "description": "Saga de alta fantasia de Brandon Sanderson"}'
```

---

#### `GET /series`

* Lista todas as séries.

```bash
curl http://localhost:8080/series
```

---

### 🔹 `BookController`

#### `POST /books`

* Cadastra um livro vinculado a uma série.

```bash
curl -X POST http://localhost:8080/books \
  -H "Content-Type: application/json" \
  -d '{"title": "The Way of Kings", "seriesId": 1, "orderIndex": 1, "year": 2010}'
```

---

#### `GET /books/by-series/{seriesId}`

* Lista livros de uma série.

```bash
curl http://localhost:8080/books/by-series/1
```

---

## 6. Banco de Dados (Schemas Simplificados)

```sql
CREATE TABLE series (
  id BIGSERIAL PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT,
  created_at TIMESTAMP DEFAULT now()
);

CREATE TABLE books (
  id BIGSERIAL PRIMARY KEY,
  series_id BIGINT REFERENCES series(id),
  title TEXT NOT NULL,
  order_index INT,
  year INT,
  created_at TIMESTAMP DEFAULT now()
);

CREATE TABLE tm (
  id BIGSERIAL PRIMARY KEY,
  src TEXT NOT NULL,
  tgt TEXT NOT NULL,
  lang_src TEXT NOT NULL,
  lang_tgt TEXT NOT NULL,
  quality DOUBLE PRECISION,
  created_at TIMESTAMP DEFAULT now(),
  UNIQUE(src,tgt,lang_src,lang_tgt)
);

CREATE TABLE tm_embeddings (
  tm_id BIGINT PRIMARY KEY REFERENCES tm(id),
  emb_src VECTOR,
  emb_tgt VECTOR
);

CREATE TABLE tm_occurrence (
  id BIGSERIAL PRIMARY KEY,
  tm_id BIGINT REFERENCES tm(id),
  series_id BIGINT REFERENCES series(id),
  book_id BIGINT REFERENCES books(id),
  chapter TEXT,
  location TEXT,
  quality_at_import DOUBLE PRECISION,
  source_tag TEXT,
  created_at TIMESTAMP DEFAULT now()
);
```

---


# 7. Setup & Deploy

### 🔧 Requisitos

* **Java 21** + **Spring Boot 3.x**
* **PostgreSQL ≥ 15** com extensão `pgvector`
* **Python 3.10+**
* **Docker** (opcional, mas recomendado para subir os serviços juntos)

### 📦 Instalação Backend Java

```bash
cd jarvistradutor-backend
./mvnw clean package
java -jar target/jarvistradutor-backend.jar
```

Configuração em `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/jarvis
    username: jarvis
    password: jarvis
  jpa:
    hibernate.ddl-auto: update
```

---

### 📦 Instalação Embeddings API

```bash
cd embeddings-api
pip install -r requirements.txt
uvicorn src.embeddings_api.main:app --host 0.0.0.0 --port 8001 --reload
```

---

### 📦 Instalação Worker Python (opcional)

```bash
cd worker
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8002 --reload
```

---

### 🚀 Docker Compose - não está sendo utilizado no momento estou levantando tudo na mão (Exemplo)

```yaml
version: "3.8"
services:
  db:
    image: postgres:15
    environment:
      POSTGRES_DB: jarvis
      POSTGRES_USER: jarvis
      POSTGRES_PASSWORD: jarvis
    ports:
      - "5432:5432"
  backend:
    build: ./jarvistradutor-backend
    ports:
      - "8080:8080"
    depends_on:
      - db
  embeddings:
    build: ./embeddings-api
    ports:
      - "8001:8001"
  worker:
    build: ./worker
    ports:
      - "8002:8002"
```

---

# 8. Técnicas Avançadas

### ⚡ Batching

* Processamos embeddings em **lotes** (`EMB_BATCH=128` ou `512`).
* Reduz chamadas HTTP → maior throughput.
* Evita **OOM (out-of-memory)** no Python.

### 💾 Flush

* `flush()` é usado para aliviar o buffer e liberar dados pro **pipe** ou pro **arquivo temporário**.
* No staging de embeddings, só flush por lote.
* Fechamento (`close()`) é o sinal de EOF pro PostgreSQL `COPY`.

### 🔄 Backpressure

* Conceito: se o **consumidor** (PostgreSQL COPY) não consegue ler na mesma velocidade que o **produtor** (Java escrevendo CSV), o buffer enche e trava.
* Resolvemos isso:

  * com `flush()` periódico (a cada 8–16 linhas).
  * com **arquivo temporário** em disco (em vez de pipe).

---

# 9. Roadmap Futuro 🚀

1. **Fine-tuning** com corpus específico (ex: fantasia épica).

   * Usar LoRA em GPUs alugadas (Colab, Paperspace).
   * Treinar embeddings adaptados ao gênero.

2. **Avaliação de Qualidade**

   * Comparar nossas traduções com versões oficiais.
   * Armazenar métricas BLEU, TER e BERTScore na tabela `tm_occurrence`.

3. **UI Admin (React + Tailwind + Shadcn)**

   * CRUD de séries/livros.
   * Dashboard de importações.
   * Relatórios de qualidade.

4. **Integração com LLM pós-edição**

   * Retradução automática dos 10% piores pares.
   * Aplicação de glossário **constrained decoding**.

---

👉 Agora sim, meu amigo, temos uma **documentação completíssima**:

* Arquitetura
* APIs com cURLs
* Banco de dados
* Controllers
* Setup & Deploy
* Técnicas avançadas
* Roadmap

