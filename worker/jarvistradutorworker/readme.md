# 📚 JarvisTradutor Worker

Worker responsável pela tradução de arquivos **EPUB** no projeto **JarvisTradutor**.  
Ele recebe os arquivos do backend, traduz usando modelos da Hugging Face e gera tanto o **EPUB traduzido** quanto um **relatório de qualidade**.

---

## 🚀 Como rodar

1. Crie e ative o ambiente virtual:
   ```bash
   python -m venv .venv
   .venv\Scripts\activate   # Windows
   source .venv/bin/activate # Linux/Mac
2. Instale as dependências:
   ```bash
   pip install -r requirements.txt
3. Configure o arquivo .env na raiz do worker:
   ```
   BASE_DIR=C:\Users\souno\Desktop\Projects2025\jarvistradutor
   MODEL_NAME=facebook/nllb-200-distilled-600M
   SRC_LANG=en
   TGT_LANG=pt
   NLLB_SRC=eng_Latn
   NLLB_TGT=por_Latn
   DEBUG=True
4. Rode o servidor:
   ```bash
   uvicorn worker.app:app --reload

📂 Estrutura de diretórios
   ```
   worker/
   ├── api/              # Rotas FastAPI
   ├── core/             # Configuração e ciclo de vida
   ├── nlp/              # Carregamento de modelos HF
   ├── quality/          # QualityInspector (métricas)
   ├── services/         # Lógica de tradução (EPUB)
   ├── utils/            # Funções utilitárias (fs, etc.)
   ├── app.py            # Entrada FastAPI
   └── requirements.txt
```
🧩 Arquitetura & Fluxo
Componentes
```
flowchart LR
  subgraph FE[Frontend (React)]
    U[Usuário] --> UP[Upload do arquivo]
    DL[Download traduzido]
  end

  subgraph BE[Backend (Spring Boot)]
    C1[/POST /api/translate/epub/]
    FS[(Disco: storageDir)]
  end

  subgraph WK[Worker (FastAPI)]
    R1[/POST /translate/]
    SVC[translate_epub.py<br/>+ QualityInspector]
    HF[(HuggingFace Models)]
    TP[(Temp / Uploads / Translated)]
  end

  U --> UP --> C1
  C1 -->|multipart/form-data (file)| R1
  R1 --> SVC
  SVC --> HF
  SVC -->|EPUB traduzido| TP
  SVC -->|CSV de qualidade| TP
  TP -->|path do arquivo| C1
  C1 -->|200 + filename| DL
  DL --> U

  BE --- FS
  WK --- TP
  ```
Sequência da tradução
```
sequenceDiagram
  autonumber
  participant User as Usuário (FE)
  participant BE as Backend (Spring Boot)
  participant WK as Worker (FastAPI)
  participant HF as HuggingFace (Model)

  User->>BE: POST /api/translate/epub (file)
  BE->>WK: POST /translate (file)
  WK->>WK: Salva em uploads/
  WK->>WK: parse EPUB (ebooklib + BeautifulSoup)
  WK->>HF: batch por tokens -> generate()
  HF-->>WK: traduções
  WK->>WK: pós-processa + reconstrói NAV/TOC/SPINE
  WK->>WK: escreve tmp .epub e move p/ translated/
  WK->>WK: QualityInspector → métricas
  WK->>WK: grava .quality.csv
  WK-->>BE: 200 {"outputName": "..._ptbr.epub"}
  BE-->>User: retorna filename → FE baixa via /files/{name}
```
📊 Saídas
```
translated/<id>_ptbr.epub → EPUB traduzido
translated/<id>_ptbr.epub.quality.csv → relatório de qualidade
Métricas registradas por segmento:
len_ratio (comprimento relativo)
untrans (tokens não traduzidos)
punct (problemas de pontuação)
sim (similaridade semântica)
avg_logprob (confiança do modelo)
lang (detecção de idioma)
needs_review (se recomenda revisão)
```
🛠️ Tecnologias
```
FastAPI (API Worker)
ebooklib + BeautifulSoup (parsing de EPUB)
pysbd (segmentação de sentenças)
Hugging Face Transformers (tradução NLLB/M2M/Marian)
sentence-transformers (verificação de qualidade)
```