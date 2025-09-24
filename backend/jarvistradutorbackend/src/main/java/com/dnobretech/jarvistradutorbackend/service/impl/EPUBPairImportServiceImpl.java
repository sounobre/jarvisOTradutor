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

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.codec.support.DefaultClientCodecConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;

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

        // 1) Extrair blocos paralelos “crus”
        List<String> blocksEn = extractBlocks(fileEn, level);
        List<String> blocksPt = extractBlocks(filePt, level);

        // 2) Alinhar (simples: por comprimento) — você pode trocar por sua rotina
        List<Pair> aligned = "embedding".equalsIgnoreCase(mode)
                ? alignByEmbedding(blocksEn, blocksPt)
                : alignByLength(blocksEn, blocksPt);

        // 3) Preparar COPY para tm (+ opcional staging de embeddings)
        Connection con = DataSourceUtils.getConnection(dataSource);
        final CopyManager cm = con.unwrap(org.postgresql.PGConnection.class).getCopyAPI();

        // COPY tm
        final PipedReader pr = new PipedReader(1 << 16);
        final PipedWriter pw = new PipedWriter(pr);
        final AtomicReference<Throwable> copyErr = new AtomicReference<>();
        Thread copyThread = new Thread(() -> {
            try (Reader r = pr) {
                cm.copyIn("COPY tm(src,tgt,lang_src,lang_tgt,quality) FROM STDIN WITH (FORMAT csv, HEADER true)", r);
            } catch (Throwable t) {
                copyErr.set(t);
                log.error("Erro COPY tm (epub-pair)", t);
            }
        }, "copy-epub-tm");
        copyThread.start();

        // COPY staging (se embedding ou se quiser já popular)
        boolean doEmb = "embedding".equalsIgnoreCase(mode); // pode trocar para flag query param tipo embed=src|both
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
                    log.error("Erro COPY tm_emb_staging (epub-pair)", t);
                }
            }, "copy-epub-emb");
            copyEmbThread.start();
        } else {
            prEmb = null;
        }

        long inserted = 0, skipped = 0;
        double sumQ = 0.0;
        int chapters = 1; // se quiser detectar por toc/capítulos, ajuste
        List<ExamplePair> examples = new ArrayList<>(Math.min(10, aligned.size()));

        try (BufferedWriter out = new BufferedWriter(pw, 1 << 16)) {
            out.write("src,tgt,lang_src,lang_tgt,quality\n");
            if (doEmb) {
                outEmb.write("src,tgt,lang_src,lang_tgt,emb_src,emb_tgt,quality\n");
            }

            // Se for modo embedding para alinhar, já teremos vetores; senão, podemos só embutir src/tgt aqui
            // Para simplificar: se "embedding", gere também os vetores p/ staging
            final int BATCH = 512;
            List<String> bufSrc = new ArrayList<>(BATCH);
            List<String> bufTgt = new ArrayList<>(BATCH);
            List<Double> bufQ = new ArrayList<>(BATCH);

            for (Pair p : aligned) {
                String src = norm.normalize(p.src());
                String tgt = norm.normalize(p.tgt());
                if (src.isBlank() || tgt.isBlank()) { skipped++; continue; }

                double r = norm.lengthRatio(src, tgt);
                boolean ph = norm.placeholdersPreserved(src, tgt);
                if (r < ratioMin || r > ratioMax || !ph) { skipped++; continue; }

                double q = qualityScore(r, ph);
                if (q < minQuality) { skipped++; continue; }

                // escreve TM
                writeCsvLine(out, src, tgt, srcLang, tgtLang, String.valueOf(q));
                inserted++;
                sumQ += q;

                if (examples.size() < 10) examples.add(new ExamplePair(src, tgt, q));

                if (doEmb) {
                    bufSrc.add(src); bufTgt.add(tgt); bufQ.add(q);
                    if (bufSrc.size() >= BATCH) {
                        flushEmbeddingsBuffer(outEmb, bufSrc, bufTgt, srcLang, tgtLang, bufQ, /*both*/ true);
                    }
                }
            }
            if (doEmb && !bufSrc.isEmpty()) {
                flushEmbeddingsBuffer(outEmb, bufSrc, bufTgt, srcLang, tgtLang, bufQ, true);
            }

            out.flush();
            if (doEmb) outEmb.flush();
        } finally {
            // fechar writers → sinaliza EOF ao COPY
            try { pw.close(); } catch (IOException ignore) {}
            try { copyThread.join(120_000); } catch (InterruptedException ignore) {}
            DataSourceUtils.releaseConnection(con, dataSource);

            if (doEmb) {
                try { outEmb.close(); } catch (IOException ignore) {}
                try { copyEmbThread.join(120_000); } catch (InterruptedException ignore) {}
            }
        }

        if (copyErr.get() != null) throw new RuntimeException("COPY tm falhou (epub-pair)", copyErr.get());
        if (doEmb && copyEmbErr.get() != null) throw new RuntimeException("COPY tm_emb_staging falhou (epub-pair)", copyEmbErr.get());

        // Consolida staging → tm_embeddings (idempotente)
        if (doEmb) {
            int merged = consolidateEmbeddingsFromStaging();
            log.info("[epub-pair] Embeddings consolidados: {}", merged);
        }

        double avgQ = inserted > 0 ? (sumQ / inserted) : 0.0;
        return new Result(inserted, skipped, avgQ, chapters, examples);
    }

    // ===================== Alinhamento (simples) =====================

    private List<Pair> alignByLength(List<String> en, List<String> pt) {
        // Ingênuo: emparelha por índice e filtra por tamanho (você pode trocar por seu alinhador real)
        int n = Math.min(en.size(), pt.size());
        List<Pair> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new Pair(en.get(i), pt.get(i)));
        return out;
    }

    private List<Pair> alignByEmbedding(List<String> en, List<String> pt) {
        // Muito simples: para cada EN, acha o PT mais parecido por cosine (N x M pode ser pesado; use batches!)
        // Produção: reduza com janela local ou índices approximate. Aqui fica um baseline fácil.
        final int N = en.size(), M = pt.size();
        final int BATCH = 128;
        List<Pair> pairs = new ArrayList<>(Math.min(N, M));

        for (int i = 0; i < N; i += BATCH) {
            int i2 = Math.min(i + BATCH, N);
            var enBatch = en.subList(i, i2);
            var vEn = embedTexts(enBatch, true);

            // embed todo PT uma vez (cache em produção); aqui direto
            var vPt = embedTexts(pt, true);

            for (int a = 0; a < enBatch.size(); a++) {
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

    private void flushEmbeddingsBuffer(Writer outEmb,
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

            outEmb.write("\"" + esc(src) + "\",\"" + esc(tgt) + "\",\"" + esc(srcLang) + "\",\"" + esc(tgtLang) + "\",");

            if (!embSrc.isEmpty()) outEmb.write('"' + embSrc + '"');
            outEmb.write(',');
            if (!embTgt.isEmpty()) outEmb.write('"' + embTgt + '"');
            outEmb.write(',');
            outEmb.write(Double.toString(bufQ.get(i)));
            outEmb.write('\n');
            log.info("linha: " + i);
        }
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

    // ===================== Parsing EPUB =====================

    private List<String> extractBlocks(MultipartFile file, String level) throws Exception {
        // 1) Lê o EPUB via epublib (abre o ZIP e encontra os recursos HTML/XHTML)
        Book book;
        try (InputStream in = file.getInputStream()) {
            book = new EpubReader().readEpub(in);
        }

        List<String> blocks = new ArrayList<>();

        for (Resource res : book.getContents()) {
            // só processa HTML/XHTML
            if (res.getMediaType() == MediatypeService.XHTML ) {
                Charset enc = res.getInputEncoding() != null
                        ? Charset.forName(res.getInputEncoding())
                        : StandardCharsets.UTF_8;
                String html = new String(res.getData(), enc);
                blocks.addAll(htmlToBlocks(html, level));
            }
        }

        // Remoção de duplicatas e ruídos típicos (headers/footers) mantendo ordem
        blocks = postProcessBlocks(blocks);

        return blocks;
    }

    private List<String> htmlToBlocks(String html, String level) {
        Document doc = Jsoup.parse(html);

        // Evita duplicar texto de <div> e <p>: pegue só blocos “folha”
        // Estratégia: colete apenas <p> e <li>. Se quiser <div>, apenas quando não tiver <p>/<li> dentro.
        List<String> out = new ArrayList<>();

        // 1) Padrão: pegue <p> e <li>
        for (Element el : doc.select("p, li")) {
            String t = clean(el.text());
            if (!t.isBlank()) out.add(t);
        }

        // 2) Opcional: <div> que não contenha blocos já coletados (folhas)
        for (Element el : doc.select("div:not(:has(p,li))")) {
            String t = clean(el.text());
            if (!t.isBlank()) out.add(t);
        }

        // Se pediram nível de sentença, faça um split simples aqui
        if ("sentence".equalsIgnoreCase(level)) {
            List<String> sent = new ArrayList<>();
            for (String p : out) {
                // split simples. Você pode trocar por pysbd no worker; aqui mantemos simples no Java:
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
        // normaliza espaços e remove truques comuns de rodapé/cabeçalho invisível se necessário
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private List<String> postProcessBlocks(List<String> in) {
        // 1) remove duplicatas consecutivas idênticas
        List<String> tmp = new ArrayList<>(in.size());
        String prev = null;
        for (String s : in) {
            if (!s.equals(prev)) tmp.add(s);
            prev = s;
        }

        // 2) remove linhas muito curtas que parecem número de página/rodapé (ajuste limiar conforme seu corpus)
        List<String> out = new ArrayList<>(tmp.size());
        for (String s : tmp) {
            String noDigits = s.replaceAll("\\d", "");
            boolean looksFooter = s.length() <= 5 || noDigits.isBlank();
            if (!looksFooter) out.add(s);
        }

        // 3) dedupe global "leve" preservando ordem (evita repetir o mesmo parágrafo espalhado)
        LinkedHashSet<String> seen = new LinkedHashSet<>(out);
        return new ArrayList<>(seen);
    }

    private record Pair(String src, String tgt) {}

}
