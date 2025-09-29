package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.domain.ImportCheckpoint;

import com.dnobretech.jarvistradutorbackend.dto.CheckpointDTO;
import com.dnobretech.jarvistradutorbackend.dto.EmbedResponse;
import com.dnobretech.jarvistradutorbackend.dto.ExamplePair;
import com.dnobretech.jarvistradutorbackend.dto.ResumeResult;
import com.dnobretech.jarvistradutorbackend.repository.ImportCheckpointRepository;
import com.dnobretech.jarvistradutorbackend.service.TMImportService;
import com.dnobretech.jarvistradutorbackend.util.TextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.copy.CopyManager;
import org.postgresql.copy.PGCopyOutputStream;
import org.postgresql.core.BaseConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class TMImportServiceImpl implements TMImportService {

    private final TextNormalizer norm;
    private final DataSource dataSource;
    private final ImportCheckpointRepository checkpointRepo;
    private final JdbcTemplate jdbc; // DDL/merge

    private final WebClient embClient = WebClient.builder()
            .baseUrl("http://localhost:8001")
            .exchangeStrategies(
                    ExchangeStrategies.builder()
                            .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024)) // 64 MB
                            .build()
            )
            .build();

    @Value("${jarvis.tm.ratio-min:0.5}")
    private double ratioMin;
    @Value("${jarvis.tm.ratio-max:2.0}")
    private double ratioMax;



    // ===================== Upload (multipart) -> COPY (tm_staging) =====================

    @Override
    public long importTsvOrCsvStreaming(MultipartFile file, String delimiter) throws Exception {
        ensureTmStagingSchema();

        final String delim = normalizeDelimiter(delimiter);
        log.info("Import upload iniciado: filename='{}', delimiter='{}'",
                file.getOriginalFilename(), printableDelim(delim));

        long rows = 0, seen = 0;

        Connection con = DataSourceUtils.getConnection(dataSource);
        final CopyManager cm = con.unwrap(org.postgresql.PGConnection.class).getCopyAPI();
        final PipedReader pr = new PipedReader(1 << 16);
        final PipedWriter pw = new PipedWriter(pr);

        final AtomicReference<Throwable> copyErr = new AtomicReference<>();
        Thread copyThread = new Thread(() -> {
            try (Reader r = pr) {
                cm.copyIn("COPY tm_staging(src,tgt,lang_src,lang_tgt,quality) FROM STDIN WITH (FORMAT csv, HEADER true)", r);
            } catch (Throwable t) {
                copyErr.set(t);
                log.error("Erro no COPY (upload)", t);
            }
        }, "copy-upload-staging");
        copyThread.start();

        BufferedReader br = null;
        BufferedWriter out = null;
        try {
            br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8), 1 << 16);
            out = new BufferedWriter(pw, 1 << 16);

            out.write("src,tgt,lang_src,lang_tgt,quality\n");

            String line;
            // dedupe leve do lote
            Set<String> seenKeys = new HashSet<>(1 << 14);

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

                String key = src + "\u0001" + tgt + "\u0001" + langSrc + "\u0001" + langTgt;
                if (!seenKeys.add(key)) continue;

                double q = qualityScore(r, true);
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

        // Consolidar staging -> tm (dedupe prévio evita ON CONFLICT double-hit)
        int up = upsertFromTmStagingToTm();
        log.info("[upload] staging→tm upserts/updates={}", up);
        truncateTmStaging();

        // Consolidar ocorrências (se estiver usando esse staging; safe mesmo vazio)
        ensureTmOccurrenceStagingSchema();
        int occ = upsertFromOccurrenceStagingToOccurrence();
        log.info("[upload] staging→tm_occurrence inseridos={}", occ);

        log.info("Import upload finalizado: lidas={} válidas={}", seen, rows);
        return rows;
    }

    // ===================== Resumível (arquivo no disco) =====================

    @Override
    public ResumeResult importTxtResume(String path, String delimiter, String fileKey, int batchLines,
                                        int examples, String embed) throws Exception {
        ensureTmStagingSchema();

        final String delim = normalizeDelimiter(delimiter);
        final String embedMode = (embed == null ? "none" : embed.toLowerCase(Locale.ROOT)); // none|src|both
        final int EMB_BATCH = 512;

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

        // === Conexões dedicadas ao COPY (uma para tm_staging e outra opcional para tm_emb_staging) ===
        Connection conTm = DataSourceUtils.getConnection(dataSource);
        BaseConnection baseTm = conTm.unwrap(BaseConnection.class);
        PGCopyOutputStream pgOutTm = new PGCopyOutputStream(
                baseTm,
                "COPY tm_staging(src,tgt,lang_src,lang_tgt,quality) FROM STDIN WITH (FORMAT csv, HEADER true)"
        );
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(pgOutTm, StandardCharsets.UTF_8), 1 << 16);
        out.write("src,tgt,lang_src,lang_tgt,quality\n");

        final boolean doEmb = !"none".equals(embedMode);
        Connection conEmb = null; BaseConnection baseEmb = null; PGCopyOutputStream pgOutEmb = null; BufferedWriter outEmb = null;
        if (doEmb) {
            ensureEmbeddingsSchema();
            conEmb = DataSourceUtils.getConnection(dataSource);
            baseEmb = conEmb.unwrap(BaseConnection.class);
            pgOutEmb = new PGCopyOutputStream(
                    baseEmb,
                    "COPY tm_emb_staging(src,tgt,lang_src,lang_tgt,emb_src,emb_tgt,quality) FROM STDIN WITH (FORMAT csv, HEADER true)"
            );
            outEmb = new BufferedWriter(new OutputStreamWriter(pgOutEmb, StandardCharsets.UTF_8), 1 << 16);
            outEmb.write("src,tgt,lang_src,lang_tgt,emb_src,emb_tgt,quality\n");
        }

        RandomAccessFile raf = null;
        long newOffset;

        final int maxExamples = Math.min(Math.max(0, examples), 50);
        final List<ExamplePair> examplesList = new ArrayList<>(maxExamples);

        final List<String> bufSrc = new ArrayList<>(EMB_BATCH);
        final List<String> bufTgt = new ArrayList<>(EMB_BATCH);
        final List<String> bufLangSrc = new ArrayList<>(EMB_BATCH);
        final List<String> bufLangTgt = new ArrayList<>(EMB_BATCH);
        final List<Double> bufQ = new ArrayList<>(EMB_BATCH);

        // dedupe leve por par no lote
        Set<String> seen = new HashSet<>(batchLines * 2);

        try {
            raf = new RandomAccessFile(f, "r");

            if (startOffset > 0 && startOffset < fileSize) {
                raf.seek(startOffset);
            } else if (startOffset >= fileSize) {
                log.info("Nada a fazer: offset >= fileSize ({} >= {})", startOffset, fileSize);
                // Finaliza corretamente os COPY vazios
                out.flush();
                pgOutTm.endCopy();
                DataSourceUtils.releaseConnection(conTm, dataSource);

                if (doEmb && outEmb != null) {
                    outEmb.flush();
                    pgOutEmb.endCopy();
                    DataSourceUtils.releaseConnection(conEmb, dataSource);
                }

                int up = upsertFromTmStagingToTm();
                log.info("[resume] staging→tm upserts/updates (EOF caso offset>=size) = {}", up);
                truncateTmStaging();
                if (doEmb) consolidateEmbeddingsFromStaging();

                return new ResumeResult(0, startOffset, 0, List.of());
            }

            int linesThisBatch = 0;
            int count = 0;
            while (true) {
                log.info("Count: {}", count);
                count++;
                String line = readLineUtf8(raf);
                if (line == null) break;

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
                            String key = src + "\u0001" + tgt + "\u0001" + langSrc + "\u0001" + langTgt;
                            if (seen.add(key)) {
                                double q = qualityScore(r, ph);
                                writeCsvLine(out, src, tgt, langSrc, langTgt, String.valueOf(q));
                                totalCopied++;

                                if (examplesList.size() < maxExamples) {
                                    examplesList.add(new ExamplePair(src, tgt, q));
                                }

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
                }

                if ((processedLines % 100_000) == 0) {
                    long pos = raf.getFilePointer();
                    log.info("[resume:{}] lidas(lote+total)={}+{}, válidas={}, offset={}",
                            fileKey, linesThisBatch, processedLines, totalCopied, pos);
                }

                if (linesThisBatch >= batchLines) break;
            }

            newOffset = raf.getFilePointer();

            out.flush();
            if (doEmb && !bufSrc.isEmpty()) {
                flushEmbeddingsBuffer(outEmb, bufSrc, bufTgt, bufLangSrc, bufLangTgt, bufQ, embedMode);
            }

        } finally {
            // Fechamentos em ordem: writers → endCopy → release connections
            try { out.flush(); } catch (IOException ignore) {}
            try { pgOutTm.endCopy(); } catch (Exception e) { log.warn("endCopy tm_staging: {}", e.toString()); }
            DataSourceUtils.releaseConnection(conTm, dataSource);

            if (doEmb && outEmb != null) {
                try { outEmb.flush(); } catch (IOException ignore) {}
                try { pgOutEmb.endCopy(); } catch (Exception e) { log.warn("endCopy tm_emb_staging: {}", e.toString()); }
                if (conEmb != null) DataSourceUtils.releaseConnection(conEmb, dataSource);
            }

            if (raf != null) try { raf.close(); } catch (IOException ignore) {}
        }

        // Consolidar staging → tm
        int up = upsertFromTmStagingToTm();
        log.info("[resume] staging→tm upserts/updates={}", up);
        truncateTmStaging();

        // Consolidar embeddings
        if (!"none".equals(embedMode)) {
            int merged = consolidateEmbeddingsFromStaging();
            log.info("Embeddings consolidados do staging → tm_embeddings: {}", merged);
        }

        // (opcional) consolidar ocorrências se estiver usando esse staging neste fluxo
        ensureTmOccurrenceStagingSchema();
        int occ = upsertFromOccurrenceStagingToOccurrence();
        log.info("[resume] staging→tm_occurrence inseridos={}", occ);

        // salvar checkpoint
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

    // ===================== Checkpoint utils =====================

    @Override
    public CheckpointDTO getCheckpoint(String fileKey) {
        return checkpointRepo.findById(fileKey)
                .map(ck -> {
                    Long fileSize = null;
                    try {
                        File f = new File(ck.getPath());
                        if (f.exists()) fileSize = f.length();
                    } catch (Exception ignore) {}
                    return new CheckpointDTO(ck.getFileKey(), ck.getPath(), ck.getByteOffset(), ck.getLineCount(), fileSize);
                })
                .orElse(null);
    }

    @Override
    public void resetCheckpoint(String fileKey) {
        checkpointRepo.deleteById(fileKey);
        log.warn("Checkpoint resetado para fileKey='{}'", fileKey);
    }

    // ===================== Helpers comuns =====================

    private static String readLineUtf8(RandomAccessFile raf) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        int b;
        boolean seen = false;
        while ((b = raf.read()) != -1) {
            seen = true;
            if (b == '\n') break;
            if (b == '\r') {
                long pos = raf.getFilePointer();
                int next = raf.read();
                if (next != '\n') raf.seek(pos);
                break;
            }
            baos.write(b);
        }
        if (!seen && b == -1) return null;
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }
    private static Integer writeCsvLineCount = 0;
    private static void writeCsvLine(Writer out, String src, String tgt, String langSrc, String langTgt, String quality) throws IOException {

        log.info("writeCsvLineCount: " + writeCsvLineCount);
        writeCsvLineCount++;
        if (writeCsvLineCount == 511)
            log.info("chegou");

        out.write('"');
        out.write(esc(src));
        out.write('"');
        out.write(',');
        out.write('"');
        out.write(esc(tgt));
        out.write('"');
        out.write(',');
        out.write('"');
        out.write(esc(langSrc));
        out.write('"');
        out.write(',');
        out.write('"');
        out.write(esc(langTgt));
        out.write('"');
        out.write(',');
        if (quality != null && !quality.isBlank())
            out.write(quality);
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

    private static String printableDelim(String d) { return "\t".equals(d) ? "\\t" : d; }

    private static String percent(long a, long b) { return (b == 0) ? "0" : String.format("%.1f", (100.0 * a / b)); }

    private double qualityScore(double r, boolean placeholdersOk) {
        double rs;
        if (r <= ratioMin || r >= ratioMax) rs = 0.0;
        else if (r <= 1.0) rs = 1.0 - ((1.0 - r) / (1.0 - ratioMin));
        else rs = 1.0 - ((r - 1.0) / (ratioMax - 1.0));
        rs = Math.max(0.0, Math.min(1.0, rs));
        double ps = placeholdersOk ? 1.0 : 0.0;
        double q = 0.7 * rs + 0.3 * ps;
        return Math.round(q * 1000.0) / 1000.0;
    }

    // ========= Embeddings helpers =========

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

    private void flushEmbeddingsBuffer(
            Writer outEmb,
            List<String> bufSrc, List<String> bufTgt,
            List<String> bufLangSrc, List<String> bufLangTgt,
            List<Double> bufQ,
            String embedMode
    ) throws IOException {
        if (outEmb == null) return; // seguro

        List<double[]> vecSrc = embedTexts(bufSrc, true);
        List<double[]> vecTgt = "both".equals(embedMode) ? embedTexts(bufTgt, true) : List.of();

        // (opcional) logar dimensão dos vetores para sanity-check
        if (!vecSrc.isEmpty()) log.info("emb_src dim={}", vecSrc.get(0).length);
        if (!vecTgt.isEmpty()) log.info("emb_tgt dim={}", vecTgt.get(0).length);

        for (int i = 0; i < bufSrc.size(); i++) {
            String src = bufSrc.get(i);
            String tgt = bufTgt.get(i);
            String ls = bufLangSrc.get(i);
            String lt = bufLangTgt.get(i);
            Double q  = bufQ.get(i);

            String embSrc = vecSrc.isEmpty() ? "" : toVectorLiteral(vecSrc.get(i));
            String embTgt = (!vecTgt.isEmpty()) ? toVectorLiteral(vecTgt.get(i)) : "";

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

            if ((i & 0xFF) == 0) outEmb.flush(); // flush leve a cada 256 linhas
        }

        bufSrc.clear(); bufTgt.clear(); bufLangSrc.clear(); bufLangTgt.clear(); bufQ.clear();
    }

    // ========= Staging + Consolidação =========

    private void ensureTmStagingSchema() {
        jdbc.execute("""
      CREATE TABLE IF NOT EXISTS tm_staging (
        src       text NOT NULL,
        tgt       text NOT NULL,
        lang_src  text NOT NULL,
        lang_tgt  text NOT NULL,
        quality   double precision,
        created_at timestamp default now()
      )
    """);

        // Em versões que não suportam ADD CONSTRAINT IF NOT EXISTS, use DO $$ ... $$
        jdbc.execute("""
      DO $$
      BEGIN
        IF NOT EXISTS (
          SELECT 1
          FROM pg_constraint
          WHERE conname = 'tm_unique_pair'
            AND conrelid = 'tm'::regclass
        ) THEN
          ALTER TABLE tm
            ADD CONSTRAINT tm_unique_pair
            UNIQUE (src, tgt, lang_src, lang_tgt);
        END IF;
      END
      $$;
    """);
    }

    private int upsertFromTmStagingToTm() {
        String sql = """
        WITH dedup AS (
          SELECT DISTINCT ON (src, tgt, lang_src, lang_tgt)
                 src, tgt, lang_src, lang_tgt, quality
          FROM tm_staging
          ORDER BY src, tgt, lang_src, lang_tgt, quality DESC
        )
        INSERT INTO tm (src, tgt, lang_src, lang_tgt, quality)
        SELECT src, tgt, lang_src, lang_tgt, quality
        FROM dedup
        ON CONFLICT (src, tgt, lang_src, lang_tgt)
        DO UPDATE
          SET quality = GREATEST(EXCLUDED.quality, tm.quality)
    """;
        int update = jdbc.update(sql);
        clearTmStaging();
        return update;
    }

    private void truncateTmStaging() { jdbc.execute("TRUNCATE tm_staging"); }

    private void ensureEmbeddingsSchema() {
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
        jdbc.execute("CREATE TABLE IF NOT EXISTS tm_embeddings (tm_id bigint PRIMARY KEY)");
        jdbc.execute("ALTER TABLE tm_embeddings ADD COLUMN IF NOT EXISTS emb_src vector");
        jdbc.execute("ALTER TABLE tm_embeddings ADD COLUMN IF NOT EXISTS emb_tgt vector");
    }

    private int consolidateEmbeddingsFromStaging() {
        String sql = """
        WITH dedup AS (
          SELECT DISTINCT ON (src, tgt, lang_src, lang_tgt)
                 src, tgt, lang_src, lang_tgt,
                 emb_src, emb_tgt, quality
          FROM tm_emb_staging
          ORDER BY src, tgt, lang_src, lang_tgt, quality DESC, created_at DESC
        )
        INSERT INTO tm_embeddings (tm_id, emb_src, emb_tgt)
        SELECT t.id, d.emb_src, d.emb_tgt
          FROM tm t
          JOIN dedup d
            ON t.src = d.src
           AND t.tgt = d.tgt
           AND t.lang_src = d.lang_src
           AND t.lang_tgt = d.lang_tgt
        ON CONFLICT (tm_id) DO UPDATE
          SET emb_src = EXCLUDED.emb_src,
              emb_tgt = COALESCE(EXCLUDED.emb_tgt, tm_embeddings.emb_tgt)
    """;
        int update = jdbc.update(sql);
        clearEmbStaging();
        return update;
    }

    private void clearTmStaging() {
        jdbc.execute("TRUNCATE tm_staging");
    }

    private void clearEmbStaging() {
        jdbc.execute("TRUNCATE tm_emb_staging");
    }

    // ======== Ocorrências (preparado para seriesId/bookId/sourceTag) ========

    private void ensureTmOccurrenceStagingSchema() {
        jdbc.execute("""
        CREATE TABLE IF NOT EXISTS tm_occurrence_staging (
          src       text NOT NULL,
          tgt       text NOT NULL,
          lang_src  text NOT NULL,
          lang_tgt  text NOT NULL,
          series_id bigint,
          book_id   bigint,
          chapter   text,
          location  text,
          quality   double precision,
          source_tag text,
          created_at timestamp default now()
        )
    """);
    }

    private int upsertFromOccurrenceStagingToOccurrence() {
        String sql = """
        INSERT INTO tm_occurrence (tm_id, series_id, book_id, chapter, location, quality_at_import, source_tag)
        SELECT t.id, s.series_id, s.book_id, s.chapter, s.location, s.quality, s.source_tag
        FROM tm_occurrence_staging s
        JOIN tm t
          ON t.src = s.src
         AND t.tgt = s.tgt
         AND t.lang_src = s.lang_src
         AND t.lang_tgt = s.lang_tgt
    """;
        int inserted = jdbc.update(sql);
        jdbc.execute("TRUNCATE tm_occurrence_staging");
        return inserted;
    }

    // ========= Fim =========
}
