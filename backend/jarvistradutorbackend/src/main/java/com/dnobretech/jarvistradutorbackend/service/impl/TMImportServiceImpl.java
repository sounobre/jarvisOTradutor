package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.domain.ImportCheckpoint;
import com.dnobretech.jarvistradutorbackend.dto.EmbedResponse;
import com.dnobretech.jarvistradutorbackend.dto.ExamplePair;
import com.dnobretech.jarvistradutorbackend.dto.ResumeResult;
import com.dnobretech.jarvistradutorbackend.repository.ImportCheckpointRepository;
import com.dnobretech.jarvistradutorbackend.service.TMImportService;
import com.dnobretech.jarvistradutorbackend.util.TextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.copy.CopyManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class TMImportServiceImpl implements TMImportService {

    private final TextNormalizer norm;
    private final DataSource dataSource;
    private final ImportCheckpointRepository checkpointRepo;
    private final JdbcTemplate jdbc; // para DDL/merge rápidos

    // Embeddings API client (ajuste host/porta se necessário)
    private final WebClient embClient = WebClient.builder()
            .baseUrl("http://localhost:8001")
            .build();

    @Value("${jarvis.tm.ratio-min:0.5}")
    private double ratioMin;
    @Value("${jarvis.tm.ratio-max:2.0}")
    private double ratioMax;

    // ------------------------------------------------------------------------------------
    // Upload normal (multipart) — streaming -> COPY (sem arquivo temporário)
    // ------------------------------------------------------------------------------------
    @Override
    public long importTsvOrCsvStreaming(MultipartFile file, String delimiter) throws Exception {
        final String delim = normalizeDelimiter(delimiter);
        log.info("Import upload iniciado: filename='{}', delimiter='{}'",
                file.getOriginalFilename(), printableDelim(delim));

        long rows = 0, seen = 0;

        // Conexão + COPY + pipe
        Connection con = DataSourceUtils.getConnection(dataSource);
        final CopyManager cm = con.unwrap(org.postgresql.PGConnection.class).getCopyAPI();
        final PipedReader pr = new PipedReader(1 << 16);
        final PipedWriter pw = new PipedWriter(pr);

        final AtomicReference<Throwable> copyErr = new AtomicReference<>();
        Thread copyThread = new Thread(() -> {
            try (Reader r = pr) {
                cm.copyIn("COPY tm(src,tgt,lang_src,lang_tgt,quality) FROM STDIN WITH (FORMAT csv, HEADER true)", r);
            } catch (Throwable t) {
                copyErr.set(t);
                log.error("Erro na thread do COPY (upload)", t);
            }
        }, "copy-upload");
        copyThread.start();

        BufferedReader br = null;
        BufferedWriter out = null;
        try {
            br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8), 1 << 16);
            out = new BufferedWriter(pw, 1 << 16);

            out.write("src,tgt,lang_src,lang_tgt,quality\n");

            String line;
            while ((line = br.readLine()) != null) {
                seen++;
                String[] cols = line.split(delim, -1);
                if (cols.length < 2) continue;

                String src = norm.normalize(cols[0]);
                String tgt = norm.normalize(cols[1]);
                if (src.isBlank() || tgt.isBlank()) continue;

                double r = norm.lengthRatio(src, tgt);
                if (r < ratioMin || r > ratioMax) continue;
                if (!norm.placeholdersPreserved(src, tgt)) continue;

                String langSrc = (cols.length > 2 && !cols[2].isBlank()) ? cols[2] : "en";
                String langTgt = (cols.length > 3 && !cols[3].isBlank()) ? cols[3] : "pt";

                double q = qualityScore(r, /* placeholders ok */ true);
                writeCsvLine(out, src, tgt, langSrc, langTgt, String.valueOf(q));
                rows++;

                if ((seen % 100_000) == 0) {
                    log.info("[upload] lidas={} válidas={} ({}%)", seen, rows, percent(rows, seen));
                }
            }
            out.flush();
        } finally {
            if (out != null) {
                try { out.close(); } catch (IOException e) {
                    log.debug("Ignorando IOException ao fechar 'out' (upload): {}", e.toString());
                }
            }
            try { copyThread.join(120_000); } catch (InterruptedException ignore) {}
            DataSourceUtils.releaseConnection(con, dataSource);
            if (br != null) try { br.close(); } catch (IOException ignore) {}
        }

        if (copyErr.get() != null) throw new RuntimeException("COPY (upload) falhou", copyErr.get());

        log.info("Import upload finalizado: lidas={} válidas={}", seen, rows);
        return rows;
    }

    // ------------------------------------------------------------------------------------
    // Resumível (checkpoint por byte offset) — lê arquivo do disco do servidor em lotes
    // ------------------------------------------------------------------------------------
    @Override
    public ResumeResult importTxtResume(String path, String delimiter, String fileKey, int batchLines,
                                        int examples, String embed) throws Exception {
        final String delim = normalizeDelimiter(delimiter);
        final String embedMode = (embed == null ? "none" : embed.toLowerCase(Locale.ROOT)); // none|src|both
        final int EMB_BATCH = 512;

        // carrega/cria checkpoint
        ImportCheckpoint ck = checkpointRepo.findById(fileKey)
                .orElseGet(() -> ImportCheckpoint.builder()
                        .fileKey(fileKey).path(path).byteOffset(0L).lineCount(0L).build());

        if (ck.getPath() == null || !ck.getPath().equals(path)) {
            log.warn("Atualizando path do checkpoint: {} -> {}", ck.getPath(), path);
            ck.setPath(path);
        }

        File f = new File(path);
        if (!f.exists() || !f.isFile()) throw new FileNotFoundException("Arquivo não encontrado: " + path);

        long startOffset = ck.getByteOffset();
        long fileSize = f.length();

        log.info("Import resume iniciado: fileKey='{}', path='{}', fromOffset={} fileSize={} batchLines={} delimiter='{}' embed={}",
                fileKey, path, startOffset, fileSize, batchLines, printableDelim(delim), embedMode);

        long processedLines = 0L;
        long totalCopied = 0L;

        // Conexão/COPY/pipe TM
        Connection con = DataSourceUtils.getConnection(dataSource);
        final CopyManager cm = con.unwrap(org.postgresql.PGConnection.class).getCopyAPI();
        final PipedReader pr = new PipedReader(1 << 16);
        final PipedWriter pw = new PipedWriter(pr);
        final AtomicReference<Throwable> copyErr = new AtomicReference<>();

        Thread copyThread = new Thread(() -> {
            try (Reader r = pr) {
                cm.copyIn("COPY tm(src,tgt,lang_src,lang_tgt,quality) FROM STDIN WITH (FORMAT csv, HEADER true)", r);
            } catch (Throwable t) {
                copyErr.set(t);
                log.error("Erro na thread do COPY (resume)", t);
            }
        }, "copy-resume");
        copyThread.start();

        // 2º COPY opcional: staging de embeddings
        final boolean doEmb = !"none".equals(embedMode);
        PipedReader prEmb;
        PipedWriter pwEmb = null;
        BufferedWriter outEmb = null;
        Thread copyEmbThread = null;
        final AtomicReference<Throwable> copyEmbErr = new AtomicReference<>();

        if (doEmb) {
            ensureEmbeddingsSchema();
            prEmb = new PipedReader(1 << 16);
            pwEmb = new PipedWriter(prEmb);
            outEmb = new BufferedWriter(pwEmb, 1 << 16);

            copyEmbThread = new Thread(() -> {
                try (Reader r = prEmb) {
                    cm.copyIn("""
                        COPY tm_emb_staging(src,tgt,lang_src,lang_tgt,emb_src,emb_tgt,quality)
                        FROM STDIN WITH (FORMAT csv, HEADER true)
                    """, r);
                } catch (Throwable t) {
                    copyEmbErr.set(t);
                    log.error("Erro na thread do COPY (emb_staging)", t);
                }
            }, "copy-resume-emb");
            copyEmbThread.start();

            outEmb.write("src,tgt,lang_src,lang_tgt,emb_src,emb_tgt,quality\n");
        } else {
            prEmb = null;
        }

        RandomAccessFile raf = null;
        BufferedWriter out = null;
        long newOffset;

        // examples (até 50)
        final int maxExamples = Math.min(Math.max(0, examples), 50);
        final List<ExamplePair> examplesList = new ArrayList<>(maxExamples);

        // buffers para embeddings (somente pares ACEITOS)
        final List<String> bufSrc = new ArrayList<>(EMB_BATCH);
        final List<String> bufTgt = new ArrayList<>(EMB_BATCH);
        final List<String> bufLangSrc = new ArrayList<>(EMB_BATCH);
        final List<String> bufLangTgt = new ArrayList<>(EMB_BATCH);
        final List<Double> bufQ = new ArrayList<>(EMB_BATCH);

        try {
            raf = new RandomAccessFile(f, "r");

            if (startOffset > 0 && startOffset < fileSize) {
                raf.seek(startOffset);
            } else if (startOffset >= fileSize) {
                log.info("Nada a fazer: offset >= fileSize ({} >= {})", startOffset, fileSize);
                out = new BufferedWriter(pw, 1 << 16);
                out.write("src,tgt,lang_src,lang_tgt,quality\n");
                out.flush();
                try { out.close(); } catch (IOException ignore) {}
                try { copyThread.join(120_000); } catch (InterruptedException ignore) {}
                DataSourceUtils.releaseConnection(con, dataSource);

                // também fechar emb_staging se aberto
                if (doEmb && outEmb != null) {
                    try { outEmb.flush(); } catch (IOException ignore) {}
                    try { outEmb.close(); } catch (IOException ignore) {}
                    try { copyEmbThread.join(120_000); } catch (InterruptedException ignore) {}
                }

                return new ResumeResult(0, startOffset, 0, List.of());
            }

            out = new BufferedWriter(pw, 1 << 16);
            out.write("src,tgt,lang_src,lang_tgt,quality\n");

            int linesThisBatch = 0;
            while (true) {
                String line = readLineUtf8(raf);
                if (line == null) break; // EOF

                processedLines++;
                linesThisBatch++;

                String[] cols = line.split(delim, -1);
                if (cols.length >= 2) {
                    String src = norm.normalize(cols[0]);
                    String tgt = norm.normalize(cols[1]);
                    if (!src.isBlank() && !tgt.isBlank()) {
                        double r = norm.lengthRatio(src, tgt);
                        boolean ph = norm.placeholdersPreserved(src, tgt);
                        if (r >= ratioMin && r <= ratioMax && ph) {
                            String langSrc = (cols.length > 2 && !cols[2].isBlank()) ? cols[2] : "en";
                            String langTgt = (cols.length > 3 && !cols[3].isBlank()) ? cols[3] : "pt";
                            double q = qualityScore(r, ph);

                            // escreve em tm
                            writeCsvLine(out, src, tgt, langSrc, langTgt, String.valueOf(q));
                            totalCopied++;

                            // examples
                            if (examplesList.size() < maxExamples) {
                                examplesList.add(new ExamplePair(src, tgt, q));
                            }

                            // buffer embeddings
                            if (doEmb) {
                                bufSrc.add(src);
                                bufTgt.add(tgt);
                                bufLangSrc.add(langSrc);
                                bufLangTgt.add(langTgt);
                                bufQ.add(q);

                                if (bufSrc.size() >= EMB_BATCH) {
                                    flushEmbeddingsBuffer(outEmb, bufSrc, bufTgt, bufLangSrc, bufLangTgt, bufQ, embedMode);
                                }
                            }
                        }
                    }
                }

                if ((processedLines % 100_000) == 0) {
                    long pos = raf.getFilePointer();
                    log.info("[resume:{}] lidas(lote+total)={}+{}, válidas={}, offset={}",
                            fileKey, linesThisBatch, processedLines, totalCopied, pos);
                }

                if (linesThisBatch >= batchLines) {
                    break; // encerra o lote
                }
            }

            // offset após a última linha lida neste lote
            newOffset = raf.getFilePointer();

            // flushes finais
            out.flush();
            if (doEmb && !bufSrc.isEmpty()) {
                flushEmbeddingsBuffer(outEmb, bufSrc, bufTgt, bufLangSrc, bufLangTgt, bufQ, embedMode);
            }
            if (doEmb) {
                outEmb.flush();
            }
        } finally {
            // encerra TM
            if (out != null) {
                try { out.close(); } catch (IOException e) {
                    log.debug("Ignorando IOException ao fechar 'out' (resume): {}", e.toString());
                }
            }
            try { copyThread.join(120_000); } catch (InterruptedException ignore) {}
            DataSourceUtils.releaseConnection(con, dataSource);
            if (raf != null) try { raf.close(); } catch (IOException ignore) {}

            // encerra staging
            if (doEmb && outEmb != null) {
                try { outEmb.close(); } catch (IOException ignore) {}
                if (copyEmbThread != null) {
                    try { copyEmbThread.join(120_000); } catch (InterruptedException ignore) {}
                }
            }
        }



        if (copyErr.get() != null) throw new RuntimeException("COPY (resume) falhou", copyErr.get());
        if (doEmb && copyEmbErr.get() != null) throw new RuntimeException("COPY (emb_staging) falhou", copyEmbErr.get());

        if (!"none".equals(embedMode)) {
            int merged = consolidateEmbeddingsFromStaging();
            log.info("Embeddings consolidados do staging → tm_embeddings: merged={} (upserts)", merged);
        }

        // Persistência do checkpoint — fora do ciclo do COPY (outra conexão do pool)
        ck.setByteOffset(newOffset);
        ck.setLineCount(ck.getLineCount() + processedLines);
        ck.setPath(path);
        ck.setUpdatedAt(LocalDateTime.now());

        checkpointRepo.save(ck);
        log.info("Checkpoint salvo: key='{}' offset={} addLines={} copiedThisBatch={}",
                fileKey, newOffset, processedLines, totalCopied);

        log.info("Import resume finalizado: fileKey='{}' processedLines={} totalCopied={} newOffset={}",
                fileKey, processedLines, totalCopied, newOffset);

        return new ResumeResult(processedLines, ck.getByteOffset(), totalCopied, examplesList);
    }

    // ------------------------------------------------------------------------------------
    // Consultas de checkpoint
    // ------------------------------------------------------------------------------------
    @Override
    public CheckpointDTO getCheckpoint(String fileKey) {
        Optional<ImportCheckpoint> opt = checkpointRepo.findById(fileKey);
        if (opt.isEmpty()) return null;
        ImportCheckpoint ck = opt.get();
        Long fileSize = null;
        try {
            File f = new File(ck.getPath());
            if (f.exists()) fileSize = f.length();
        } catch (Exception ignore) {}
        return new CheckpointDTO(ck.getFileKey(), ck.getPath(), ck.getByteOffset(), ck.getLineCount(), fileSize);
    }

    @Override
    public void resetCheckpoint(String fileKey) {
        checkpointRepo.deleteById(fileKey);
        log.warn("Checkpoint resetado para fileKey='{}'", fileKey);
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------

    // lê uma linha em UTF-8 a partir do RandomAccessFile (respeita LF e CRLF)
    private static String readLineUtf8(RandomAccessFile raf) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        int b;
        boolean seen = false;
        while ((b = raf.read()) != -1) {
            seen = true;
            if (b == '\n') break;                 // LF
            if (b == '\r') {                      // CR (possível CRLF)
                long pos = raf.getFilePointer();
                int next = raf.read();
                if (next != '\n') raf.seek(pos);  // não era CRLF → volta 1 byte
                break;
            }
            baos.write(b);
        }
        if (!seen && b == -1) return null;        // EOF sem bytes
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeCsvLine(Writer out, String src, String tgt, String langSrc, String langTgt, String quality) throws IOException {
        out.write('"'); out.write(esc(src)); out.write('"'); out.write(',');
        out.write('"'); out.write(esc(tgt)); out.write('"'); out.write(',');
        out.write('"'); out.write(esc(langSrc)); out.write('"'); out.write(',');
        out.write('"'); out.write(esc(langTgt)); out.write('"'); out.write(',');
        if (quality != null && !quality.isBlank()) out.write(quality);
        out.write('\n');
    }

    private static String esc(String s) {
        return s.replace("\"", "\"\"");
    }

    private static String normalizeDelimiter(String delimiter) {
        if (delimiter == null) return "\t";
        String d = delimiter;
        if ("\\t".equals(d)) d = "\t";
        if ("%09".equalsIgnoreCase(d)) d = "\t";
        return d;
    }

    private static String printableDelim(String d) {
        return "\t".equals(d) ? "\\t" : d;
    }

    private static String percent(long a, long b) {
        return (b == 0) ? "0" : String.format("%.1f", (100.0 * a / b));
    }

    private double qualityScore(double r, boolean placeholdersOk) {
        double rs;
        if (r <= ratioMin || r >= ratioMax) {
            rs = 0.0;
        } else if (r <= 1.0) {
            rs = 1.0 - ((1.0 - r) / (1.0 - ratioMin));
        } else {
            rs = 1.0 - ((r - 1.0) / (ratioMax - 1.0));
        }
        rs = Math.max(0.0, Math.min(1.0, rs));
        double ps = placeholdersOk ? 1.0 : 0.0;
        double q = 0.7 * rs + 0.3 * ps;
        return Math.round(q * 1000.0) / 1000.0;
    }

    // ========= Embeddings helpers =========

    // chama /embed com lista de textos; retorna lista de vetores (double[])
    private List<double[]> embedTexts(List<String> texts, boolean normalize) {
        if (texts.isEmpty()) return List.of();
        var payload = Map.of("texts", texts, "normalize", normalize);
        EmbedResponse resp = embClient.post()
                .uri("/embed")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(EmbedResponse.class)
                .block();
        return (resp != null && resp.vectors() != null)
                ? resp.vectors()
                : List.of();
    }

    // serializa vetor em literal pgvector: "[0.1,0.2,...]"
    private static String toVectorLiteral(double[] v) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Double.toString(v[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    // flush do buffer de embeddings para o COPY de tm_emb_staging
    private void flushEmbeddingsBuffer(Writer outEmb,
                                       List<String> bufSrc,
                                       List<String> bufTgt,
                                       List<String> bufLangSrc,
                                       List<String> bufLangTgt,
                                       List<Double> bufQ,
                                       String embedMode) throws IOException {

        // sempre embeda src
        List<double[]> vecSrc = embedTexts(bufSrc, true);
        // embeda tgt se solicitado
        List<double[]> vecTgt = "both".equals(embedMode) ? embedTexts(bufTgt, true) : List.of();

        for (int i = 0; i < bufSrc.size(); i++) {
            String src = bufSrc.get(i);
            String tgt = bufTgt.get(i);
            String ls = bufLangSrc.get(i);
            String lt = bufLangTgt.get(i);
            Double q  = bufQ.get(i);

            String embSrc = vecSrc.isEmpty() ? "" : toVectorLiteral(vecSrc.get(i));
            String embTgt = (!vecTgt.isEmpty()) ? toVectorLiteral(vecTgt.get(i)) : "";

            // CSV (aspas nos textos; emb_* entre aspas pq têm vírgulas internas)
            outEmb.write('"'); outEmb.write(esc(src)); outEmb.write('"'); outEmb.write(',');
            outEmb.write('"'); outEmb.write(esc(tgt)); outEmb.write('"'); outEmb.write(',');
            outEmb.write('"'); outEmb.write(esc(ls));  outEmb.write('"'); outEmb.write(',');
            outEmb.write('"'); outEmb.write(esc(lt));  outEmb.write('"'); outEmb.write(',');
            if (!embSrc.isEmpty()) { outEmb.write('"'); outEmb.write(embSrc); outEmb.write('"'); }
            outEmb.write(',');
            if (!embTgt.isEmpty()) { outEmb.write('"'); outEmb.write(embTgt); outEmb.write('"'); }
            outEmb.write(',');
            outEmb.write(Double.toString(q));
            outEmb.write('\n');
        }

        // limpa buffers
        bufSrc.clear();
        bufTgt.clear();
        bufLangSrc.clear();
        bufLangTgt.clear();
        bufQ.clear();
    }

    /** Garante que as tabelas/colunas necessárias existem (sem Flyway), de forma idempotente. */
    private void ensureEmbeddingsSchema() {
        // staging para texto + vetores
        jdbc.execute("""
        CREATE TABLE IF NOT EXISTS tm_emb_staging (
          src       text NOT NULL,
          tgt       text NOT NULL,
          lang_src  text NOT NULL,
          lang_tgt  text NOT NULL,
          emb_src   vector,
          emb_tgt   vector,
          quality   double precision,
          created_at timestamp default now()
        )
    """);

        // tabela final (se ainda não tiver as colunas)
        jdbc.execute("CREATE TABLE IF NOT EXISTS tm_embeddings (tm_id bigint PRIMARY KEY)");
        jdbc.execute("ALTER TABLE tm_embeddings ADD COLUMN IF NOT EXISTS emb_src vector");
        jdbc.execute("ALTER TABLE tm_embeddings ADD COLUMN IF NOT EXISTS emb_tgt vector");
    }

    /** Consolida do staging para tm_embeddings usando tm.id; retorna linhas upsertadas. */
    private int consolidateEmbeddingsFromStaging() {
        ensureEmbeddingsSchema(); // idempotente

        // Faz o upsert. Observação: se houver MUITO staging, você pode fatiar com LIMIT… (cursor)
        String sql = """
        INSERT INTO tm_embeddings (tm_id, emb_src, emb_tgt)
        SELECT t.id, s.emb_src, s.emb_tgt
          FROM tm t
          JOIN tm_emb_staging s
            ON t.src = s.src
           AND t.tgt = s.tgt
           AND t.lang_src = s.lang_src
           AND t.lang_tgt = s.lang_tgt
        ON CONFLICT (tm_id) DO UPDATE
        SET emb_src = EXCLUDED.emb_src,
            emb_tgt = COALESCE(EXCLUDED.emb_tgt, tm_embeddings.emb_tgt)
    """;
        return jdbc.update(sql);
    }
}
