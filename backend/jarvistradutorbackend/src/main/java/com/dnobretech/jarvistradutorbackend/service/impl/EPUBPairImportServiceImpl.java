package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.dto.ExamplePair;
import com.dnobretech.jarvistradutorbackend.dto.Result;
import com.dnobretech.jarvistradutorbackend.service.EPUBPairImportService;
import com.dnobretech.jarvistradutorbackend.util.TextNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.siegmann.epublib.domain.Book;
import nl.siegmann.epublib.domain.Resource;
import nl.siegmann.epublib.epub.EpubReader;
import nl.siegmann.epublib.service.MediatypeService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.postgresql.copy.CopyManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class EPUBPairImportServiceImpl implements EPUBPairImportService {

    private final DataSource dataSource;
    private final TextNormalizer norm;
    private final JdbcTemplate jdbc;

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

    @Override
    public Result importParallelEPUB(MultipartFile fileEn,
                                     MultipartFile filePt,
                                     String level,       // "paragraph"|"sentence"
                                     String mode,        // "length"|"embedding"
                                     String srcLang,
                                     String tgtLang,
                                     double minQuality   // descarta abaixo
    ) throws Exception {

        // 1) Extrair blocos dos EPUBs
        List<String> blocksEn = extractBlocks(fileEn, level);
        List<String> blocksPt = extractBlocks(filePt, level);

        // 2) Alinhar (simples por tamanho; ou por embedding)
        List<Pair> aligned = "embedding".equalsIgnoreCase(mode)
                ? alignByEmbedding(blocksEn, blocksPt)
                : alignByLength(blocksEn, blocksPt);

        // 3) Preparar COPY para tm_staging e (opcional) tm_emb_staging
        ensureTmStagingSchema(); // garante staging + UNIQUE na tm

        Connection con = DataSourceUtils.getConnection(dataSource);
        final CopyManager cm = con.unwrap(org.postgresql.PGConnection.class).getCopyAPI();

        // COPY -> tm_staging (via pipe, pois linhas são pequenas)
        final PipedReader pr = new PipedReader(1 << 16);
        final PipedWriter pw = new PipedWriter(pr);
        final AtomicReference<Throwable> copyErr = new AtomicReference<>();
        Thread copyThread = new Thread(() -> {
            try (Reader r = pr) {
                cm.copyIn("COPY tm_staging(src,tgt,lang_src,lang_tgt,quality) FROM STDIN WITH (FORMAT csv, HEADER true)", r);
            } catch (Throwable t) {
                copyErr.set(t);
                log.error("Erro COPY tm_staging (epub-pair)", t);
            }
        }, "copy-epub-tm-staging");
        copyThread.start();

        // Para embeddings: **sem pipe** → arquivo temporário
        final boolean doEmb = "embedding".equalsIgnoreCase(mode); // pode virar flag embed=src|both
        File embTmpFile = null;
        BufferedWriter embFileWriter = null; // escreve CSV de staging em disco
        if (doEmb) {
            ensureEmbeddingsSchema();
            embTmpFile = Files.createTempFile("tm_emb_staging_", ".csv").toFile();
            // abre em UTF-8
            embFileWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(embTmpFile), StandardCharsets.UTF_8), 1 << 20);
            embFileWriter.write("src,tgt,lang_src,lang_tgt,emb_src,emb_tgt,quality\n");
        }

        long inserted = 0, skipped = 0;
        double sumQ = 0.0;
        int chapters = 1; // placeholder; se quiser, detecte via toc
        List<ExamplePair> examples = new ArrayList<>(Math.min(10, aligned.size()));

        try (BufferedWriter out = new BufferedWriter(pw, 1 << 16)) {
            out.write("src,tgt,lang_src,lang_tgt,quality\n");

            final int BATCH = 512;
            List<String> bufSrc = new ArrayList<>(BATCH);
            List<String> bufTgt = new ArrayList<>(BATCH);
            List<Double> bufQ = new ArrayList<>(BATCH);

            // dedupe leve por par (src,tgt) dentro do lote
            Set<String> seen = new HashSet<>(aligned.size() * 2);

            for (Pair p : aligned) {
                String src = norm.normalize(p.src());
                String tgt = norm.normalize(p.tgt());
                if (src.isBlank() || tgt.isBlank()) { skipped++; continue; }

                String key = src + "\u0001" + tgt + "\u0001" + srcLang + "\u0001" + tgtLang;
                if (!seen.add(key)) { skipped++; continue; }

                double r = norm.lengthRatio(src, tgt);
                boolean ph = norm.placeholdersPreserved(src, tgt);
                if (r < ratioMin || r > ratioMax || !ph) { skipped++; continue; }

                double q = qualityScore(r, ph);
                if (q < minQuality) { skipped++; continue; }

                // escreve na STAGING textual (pipe)
                writeCsvLine(out, src, tgt, srcLang, tgtLang, String.valueOf(q));
                inserted++;
                sumQ += q;
                if (examples.size() < 10) examples.add(new ExamplePair(src, tgt, q));

                if (doEmb) {
                    bufSrc.add(src); bufTgt.add(tgt); bufQ.add(q);
                    if (bufSrc.size() >= BATCH) {
                        flushEmbeddingsBufferToFile(embFileWriter, bufSrc, bufTgt, srcLang, tgtLang, bufQ, /*both*/ true);
                    }
                }
            }
            if (doEmb && !bufSrc.isEmpty()) {
                flushEmbeddingsBufferToFile(embFileWriter, bufSrc, bufTgt, srcLang, tgtLang, bufQ, /*both*/ true);
            }

            out.flush();
            if (doEmb) embFileWriter.flush();
        } finally {
            // fechar writer → sinaliza EOF ao COPY (texto)
            try { pw.close(); } catch (IOException ignore) {}
            try { copyThread.join(120_000); } catch (InterruptedException ignore) {}
            DataSourceUtils.releaseConnection(con, dataSource);

            // fecha o arquivo temporário (se aberto)
            if (embFileWriter != null) {
                try { embFileWriter.close(); } catch (IOException ignore) {}
            }
        }

        if (copyErr.get() != null) throw new RuntimeException("COPY tm_staging falhou (epub-pair)", copyErr.get());

        // 4) Consolidar staging → tm (idempotente, sem duplicatas)
        int up = upsertFromTmStagingToTm();
        log.info("[epub-pair] staging→tm upserts/updates={}", up);
        truncateTmStaging(); // opcional

        // 5) Consolidar embeddings staging → tm_embeddings (1 por tm_id)
        if (doEmb) {
            // Agora executa o COPY do arquivo temporário para tm_emb_staging
            try (Connection con2 = DataSourceUtils.getConnection(dataSource)) {
                CopyManager cm2 = con2.unwrap(org.postgresql.PGConnection.class).getCopyAPI();
                try (Reader r = new BufferedReader(new InputStreamReader(new FileInputStream(embTmpFile), StandardCharsets.UTF_8), 1 << 20)) {
                    cm2.copyIn("""
                        COPY tm_emb_staging(src,tgt,lang_src,lang_tgt,emb_src,emb_tgt,quality)
                        FROM STDIN WITH (FORMAT csv, HEADER true)
                    """, r);
                }
            } finally {
                if (embTmpFile != null && embTmpFile.exists()) {
                    boolean del = embTmpFile.delete();
                    if (!del) {
                        log.warn("Arquivo temporário não foi deletado: {}", embTmpFile.getAbsolutePath());
                    }
                }
            }

            int merged = consolidateEmbeddingsFromStaging();
            log.info("[epub-pair] Embeddings consolidados: {}", merged);
        }

        double avgQ = inserted > 0 ? (sumQ / inserted) : 0.0;
        return new Result(inserted, skipped, avgQ, chapters, examples);
    }

    // ===================== Alinhamento =====================

    private List<Pair> alignByLength(List<String> en, List<String> pt) {
        int n = Math.min(en.size(), pt.size());
        List<Pair> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new Pair(en.get(i), pt.get(i)));
        return out;
    }

    private List<Pair> alignByEmbedding(List<String> en, List<String> pt) {
        log.info("entrando em alignByEmbedding");
        final int N = en.size(), M = pt.size();
        final int BATCH = 128;
        List<Pair> pairs = new ArrayList<>(Math.min(N, M));

        // embeda PT uma vez (poderia cachear)
        var vPt = embedTexts(pt, true);

        for (int i = 0; i < N; i += BATCH) {
            log.info("for (int i = 0; i < N; i += BATCH) { : " + "\ni = " + i + "\nN = " + N + "\nBATCH = " + BATCH);
            int i2 = Math.min(i + BATCH, N);
            var enBatch = en.subList(i, i2);
            log.info("Envio embedTexts");
            var vEn = embedTexts(enBatch, true);
            log.info("retorno embedTexts");

            for (int a = 0; a < enBatch.size(); a++) {
                log.info("for (int a = 0; a < enBatch.size(); a++) { : " + "\na = " + a + "\nenBatch.size() : " + enBatch.size() );
                int bestJ = -1; double best = -1.0;
                double[] ve = vEn.get(a);
                for (int j = 0; j < M; j++) {
                    double sim = cosine(ve, vPt.get(j));
                    if (sim > best) { best = sim; bestJ = j; }
                }
                if (bestJ >= 0) pairs.add(new Pair(enBatch.get(a), pt.get(bestJ)));
            }
        }
        return pairs;
    }

    // ===================== Embeddings helpers =====================

    private List<double[]> embedTexts(List<String> texts, boolean normalize) {
        if (texts.isEmpty()) return List.of();
        var payload = Map.of("texts", texts, "normalize", normalize);
        var resp = embClient.post().uri("/embed")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(com.dnobretech.jarvistradutorbackend.dto.EmbedResponse.class)
                .block();
        return (resp != null && resp.vectors() != null) ? resp.vectors() : List.of();
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

    // grava embeddings no **arquivo temporário** (sem pipe)
    private void flushEmbeddingsBufferToFile(Writer outEmbFile,
                                             List<String> bufSrc,
                                             List<String> bufTgt,
                                             String srcLang, String tgtLang,
                                             List<Double> bufQ,
                                             boolean both) throws IOException {

        var vecSrc = embedTexts(bufSrc, true);
        var vecTgt = both ? embedTexts(bufTgt, true) : List.<double[]>of();

        for (int i = 0; i < bufSrc.size(); i++) {
            String src = bufSrc.get(i);
            String tgt = bufTgt.get(i);
            String embSrc = toVectorLiteral(vecSrc.get(i));
            String embTgt = both ? toVectorLiteral(vecTgt.get(i)) : "";

            StringBuilder sb = new StringBuilder(8192);
            sb.append('"').append(esc(src)).append('"').append(',');
            sb.append('"').append(esc(tgt)).append('"').append(',');
            sb.append('"').append(esc(srcLang)).append('"').append(',');
            sb.append('"').append(esc(tgtLang)).append('"').append(',');
            if (!embSrc.isEmpty()) sb.append('"').append(embSrc).append('"');
            sb.append(',');
            if (!embTgt.isEmpty()) sb.append('"').append(embTgt).append('"');
            sb.append(',');
            sb.append(bufQ.get(i)).append('\n');

            outEmbFile.write(sb.toString());
        }

        // flush leve por lote (ajuda em quedas/monitoramento; sem exagero)
        outEmbFile.flush();

        bufSrc.clear(); bufTgt.clear(); bufQ.clear();
    }

    // ===================== IO/CSV/Qualidade =====================

    private static void writeCsvLine(Writer out, String src, String tgt, String langSrc, String langTgt, String quality) throws IOException {
        out.write('"'); out.write(esc(src)); out.write('"'); out.write(',');
        out.write('"'); out.write(esc(tgt)); out.write('"'); out.write(',');
        out.write('"'); out.write(esc(langSrc)); out.write('"'); out.write(',');
        out.write('"'); out.write(esc(langTgt)); out.write('"'); out.write(',');
        if (quality != null && !quality.isBlank()) out.write(quality);
        out.write('\n');
    }

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

    private static String esc(String s) { return s.replace("\"", "\"\""); }

    private static double cosine(double[] a, double[] b) {
        double dot=0, na=0, nb=0;
        for (int i=0;i<a.length;i++){ dot+=a[i]*b[i]; na+=a[i]*a[i]; nb+=b[i]*b[i]; }
        double denom = Math.sqrt(na)*Math.sqrt(nb);
        return denom==0 ? 0 : dot/denom;
    }

    // ===================== Schema & Consolidação =====================

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
        ensureTmStagingSchema();
        String sql = """
        WITH dedup AS (
          SELECT
            src, tgt, lang_src, lang_tgt,
            MAX(quality) AS quality
          FROM tm_staging
          GROUP BY src, tgt, lang_src, lang_tgt
        )
        INSERT INTO tm (src, tgt, lang_src, lang_tgt, quality)
        SELECT src, tgt, lang_src, lang_tgt, quality
        FROM dedup
        ON CONFLICT (src, tgt, lang_src, lang_tgt)
        DO UPDATE SET quality = GREATEST(EXCLUDED.quality, tm.quality)
    """;
        return jdbc.update(sql);
    }

    private void truncateTmStaging() {
        jdbc.execute("TRUNCATE tm_staging");
    }

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

    // Usa ROW_NUMBER para garantir uma linha por tm_id no mesmo comando
    private int consolidateEmbeddingsFromStaging() {
        String sql = """
          WITH ranked AS (
            SELECT
              t.id AS tm_id, s.emb_src, s.emb_tgt, s.quality,
              ROW_NUMBER() OVER (
                PARTITION BY t.id
                ORDER BY s.quality DESC NULLS LAST
              ) rn
            FROM tm t
            JOIN tm_emb_staging s
              ON t.src = s.src
             AND t.tgt = s.tgt
             AND t.lang_src = s.lang_src
             AND t.lang_tgt = s.lang_tgt
          )
          INSERT INTO tm_embeddings (tm_id, emb_src, emb_tgt)
          SELECT tm_id, emb_src, emb_tgt
          FROM ranked WHERE rn = 1
          ON CONFLICT (tm_id) DO UPDATE
          SET emb_src = EXCLUDED.emb_src,
              emb_tgt = COALESCE(EXCLUDED.emb_tgt, tm_embeddings.emb_tgt)
        """;
        return jdbc.update(sql);
    }

    // ===================== Parsing EPUB =====================

    private List<String> extractBlocks(MultipartFile file, String level) throws Exception {
        Book book;
        try (InputStream in = file.getInputStream()) {
            book = new EpubReader().readEpub(in);
        }

        List<String> blocks = new ArrayList<>();

        for (Resource res : book.getContents()) {
            if (res.getMediaType() == MediatypeService.XHTML) {
                Charset enc = res.getInputEncoding() != null
                        ? Charset.forName(res.getInputEncoding())
                        : StandardCharsets.UTF_8;
                String html = new String(res.getData(), enc);
                blocks.addAll(htmlToBlocks(html, level));
            }
        }

        return postProcessBlocks(blocks);
    }

    private List<String> htmlToBlocks(String html, String level) {
        Document doc = Jsoup.parse(html);

        List<String> out = new ArrayList<>();
        for (Element el : doc.select("p, li")) {
            String t = clean(el.text());
            if (!t.isBlank()) out.add(t);
        }
        for (Element el : doc.select("div:not(:has(p,li))")) {
            String t = clean(el.text());
            if (!t.isBlank()) out.add(t);
        }

        if ("sentence".equalsIgnoreCase(level)) {
            List<String> sent = new ArrayList<>();
            for (String p : out) {
                for (String s : p.split("(?<=[.!?…])\\s+")) {
                    s = s.trim();
                    if (!s.isBlank()) sent.add(s);
                }
            }
            return sent;
        }

        return out;
    }

    private String clean(String s) {
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private List<String> postProcessBlocks(List<String> in) {
        List<String> tmp = new ArrayList<>(in.size());
        String prev = null;
        for (String s : in) {
            if (!s.equals(prev)) tmp.add(s);
            prev = s;
        }
        List<String> out = new ArrayList<>(tmp.size());
        for (String s : tmp) {
            String noDigits = s.replaceAll("\\d", "");
            boolean looksFooter = s.length() <= 5 || noDigits.isBlank();
            if (!looksFooter) out.add(s);
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>(out);
        return new ArrayList<>(seen);
    }

    private record Pair(String src, String tgt) {}
}
