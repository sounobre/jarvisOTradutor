package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.client.QeClient;
import com.dnobretech.jarvistradutorbackend.dto.AlignedPair;
import com.dnobretech.jarvistradutorbackend.dto.Block;
import com.dnobretech.jarvistradutorbackend.dto.ExamplePair;
import com.dnobretech.jarvistradutorbackend.dto.Result;
import com.dnobretech.jarvistradutorbackend.epubimport.*;
import com.dnobretech.jarvistradutorbackend.service.EPUBPairImportService;
import com.dnobretech.jarvistradutorbackend.util.TextNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.copy.CopyManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class EPUBPairImportServiceImpl implements EPUBPairImportService {

    // ===== Config =====
    @Value("${jarvis.tm.ratio-min:0.5}")
    private double ratioMin;
    @Value("${jarvis.tm.ratio-max:2.0}")
    private double ratioMax;

    // ===== Deps (injetadas) =====
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TextNormalizer norm;
    private final EpubExtractor epubExtractor;
    private final QualityFilter qualityFilter;
    private final EmbeddingService embeddingService;
    private final InboxWriter inboxWriter;
    private final SchemaEnsurer schemaEnsurer;

    // Aligners (nomeados com @Component("lengthAligner") / @Component("embeddingAligner"))
    private final Aligner lengthAligner;
    private final Aligner embeddingAlignerHungarian;

    private final QeClient qeClient;

    @Value("${jarvis.scoring.good-min:0.80}")
    private double goodMin;

    @Value("${jarvis.scoring.suspect-min:0.55}")
    private double suspectMin;

    @Value("${jarvis.scoring.qe-good-min:0.75}")
    private double qeGoodMin;

    @Value("${jarvis.embeddings.only-approved}")
    private boolean embedOnlyApproved;

    public EPUBPairImportServiceImpl(
            DataSource dataSource, JdbcTemplate jdbc, TextNormalizer norm, EpubExtractor epubExtractor, QualityFilter qualityFilter, EmbeddingService embeddingService, InboxWriter inboxWriter, SchemaEnsurer schemaEnsurer, @Qualifier("lengthAligner") Aligner lengthAligner,
            @Qualifier("embeddingAlignerHungarian") Aligner embeddingAlignerHungarian, QeClient qeClient
            /* demais deps… */) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.norm = norm;
        this.epubExtractor = epubExtractor;
        this.qualityFilter = qualityFilter;
        this.embeddingService = embeddingService;
        this.inboxWriter = inboxWriter;
        this.schemaEnsurer = schemaEnsurer;
        this.lengthAligner = lengthAligner;
        this.embeddingAlignerHungarian = embeddingAlignerHungarian;
        this.qeClient = qeClient;
    }

    // ===== Orquestração principal =====
    @Override
    public Result importParallelEPUB(MultipartFile fileEn,
                                     MultipartFile filePt,
                                     String level,            // "paragraph" | "sentence"
                                     String mode,             // "length" | "embedding"
                                     String srcLang,
                                     String tgtLang,
                                     double minQuality,       // descarta abaixo
                                     Long seriesId,
                                     Long bookId,
                                     String sourceTag) throws Exception {

        // 1) Extrair blocos com posição
        List<Block> blocksEn = epubExtractor.extractBlocks(fileEn, level);
        List<Block> blocksPt = epubExtractor.extractBlocks(filePt, level);

        // 2) Alinhar (uma única vez, para AlignedPair)
        Aligner aligner = "embedding".equalsIgnoreCase(mode)
                ? embeddingAlignerHungarian
                : lengthAligner;
        List<AlignedPair> aligned = aligner.align(blocksEn, blocksPt);
        log.info("[epub-pair] alinhados (pos-Hungarian/length) = {}", aligned.size());


        // 3) COPY → STAGING (inbox) + (opcional) arquivo de embeddings
        boolean doEmb = "embedding".equalsIgnoreCase(mode);

        File embTmpFile = null;
        BufferedWriter embFileWriter = null;
        if (doEmb) {
            ensureBookpairEmbStagingSchema(); // cria tm_bookpair_emb_staging (vector(384))
            embTmpFile = File.createTempFile("tm_bookpair_emb_", ".csv");
            embFileWriter = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(embTmpFile), StandardCharsets.UTF_8),
                    1 << 20
            );
            embFileWriter.write("src,tgt,lang_src,lang_tgt,emb_src,emb_tgt,quality\n");
        }

        long inserted = 0, skipped = 0;
        double sumQ = 0.0;
        int chapters = 1; // placeholder
        List<ExamplePair> examples = new ArrayList<>(Math.min(10, aligned.size()));

        final int BATCH = 512;
        List<String> bufSrc = new ArrayList<>(BATCH);
        List<String> bufTgt = new ArrayList<>(BATCH);
        List<Double> bufQ = new ArrayList<>(BATCH);
        List<PendingItem> pending = new ArrayList<>(BATCH);

        // Dedupe leve por (src,tgt,langs,location) dentro do lote
        Set<String> seen = new HashSet<>(aligned.size() * 2);

        final IntRef skippedRef = new IntRef(0);

        try (InboxWriter.CopyCtx ctx = inboxWriter.openBookpairInboxStagingCopy()) {
            Writer out = ctx.writer;

            for (AlignedPair ap : aligned) {
                String src = norm.normalizeDialogue(ap.src());
                String tgt = norm.normalizeDialogue(ap.tgt());
                if (src.isBlank() || tgt.isBlank()) {
                    skipped++;
                    continue;
                }

                // posição do SRC (usaremos para chapter/location)

                String chapter = ap.srcPos().chapterTitle();
                String chapterEn = ap.srcPos()!=null ? ap.srcPos().chapterTitle() : null;
                String chapterPt = ap.tgtPos()!=null ? ap.tgtPos().chapterTitle() : null;
                String location  = posToLocation(ap.srcPos()); // mantemos localização do EN

                String key = src + "\u0001" + tgt + "\u0001" + srcLang + "\u0001" + tgtLang + "\u0001" + location;
                if (!seen.add(key)) {
                    skipped++;
                    continue;
                }

                // qualidade
                double r = qualityFilter.lengthRatio(src, tgt);
                boolean ph = qualityFilter.placeholdersPreserved(src, tgt);
                if (r < ratioMin || r > ratioMax || !ph) {
                    skipped++;
                    continue;
                }

                double q = qualityFilter.qualityScore(r, ph, ratioMin, ratioMax);

                // NEW: sim vindo do aligner (se for LengthAligner, trate como 0.0)
                double sim = 0.0;
                try {
                    sim = Math.max(0.0, Math.min(1.0, ap.sim()));
                } catch (Throwable ignore) {
                }

                // NEW: acumula no lote para enriquecer com QE
                pending.add(new PendingItem(src, tgt, chapterEn, chapterPt, location, q, sim));

                // FLUSH por lote
                if (pending.size() >= BATCH) {
                    enrichWithQE(pending);
                    int writ = flushPendingToStaging(
                            out, pending, srcLang, tgtLang, seriesId, bookId, sourceTag, minQuality,
                            examples, skippedRef,
                            doEmb, bufSrc, bufTgt, bufQ
                    );
                    inserted += writ;
                    for (var it : pending) sumQ += it.qRule;
                    pending.clear();
                }


            }

            if (!pending.isEmpty()) {
                enrichWithQE(pending);
                int writ = flushPendingToStaging(
                        out, pending, srcLang, tgtLang, seriesId, bookId, sourceTag, minQuality,
                        examples, skippedRef,
                        doEmb, bufSrc, bufTgt, bufQ
                );
                inserted += writ;
                for (var it : pending) sumQ += it.qRule;
                pending.clear();
            }

            if (doEmb && !bufSrc.isEmpty()) {
                embeddingService.flushEmbeddingsToFile(embFileWriter, bufSrc, bufTgt, srcLang, tgtLang, bufQ, true);
            }
        } finally {
            if (embFileWriter != null) {
                try {
                    embFileWriter.close();
                } catch (IOException ignore) {
                }
            }
        }

        log.info("[epub-pair] após filtros baratos (ratio/placeholders) = {}", inserted + skipped); // ou faça um contador dedicado
        log.info("[epub-pair] gravados no staging (linhas CSV) = {}", inserted);
        log.info("[epub-pair] rejeitados nos filtros baratos = {}", skipped);


        // 4) Consolidar STAGING → INBOX (UPSERT seguro)
        int merged = inboxWriter.mergeBookpairInboxFromStaging(jdbc);
        log.info("[epub-pair] merged into tm_bookpair_inbox = {}", merged);

        // 5) Se geramos embeddings, COPY do arquivo temporário → tm_bookpair_emb_staging
        if (doEmb && embTmpFile != null && embTmpFile.exists()) {
            try (Connection con2 = DataSourceUtils.getConnection(dataSource)) {
                CopyManager cm2 = con2.unwrap(org.postgresql.PGConnection.class).getCopyAPI();
                try (Reader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(embTmpFile), StandardCharsets.UTF_8),
                        1 << 20)) {
                    cm2.copyIn("""
                                COPY tm_bookpair_emb_staging(src,tgt,lang_src,lang_tgt,emb_src,emb_tgt,quality)
                                FROM STDIN WITH (FORMAT csv, HEADER true)
                            """, r);
                }
            } finally {
                if (!embTmpFile.delete()) {
                    log.warn("Arquivo temporário não foi deletado: {}", embTmpFile.getAbsolutePath());
                }
            }
        }

        // 6) Marca o livro como “pares importados”
        if (bookId != null && merged > 0) {
            int upd = jdbc.update("""
                        UPDATE book
                           SET pairs_imported = TRUE,
                               updated_at     = now()
                         WHERE id = ?
                    """, bookId);
            log.info("[epub-pair] book {} marcado como pairs_imported=TRUE (upd={})", bookId, upd);
        }

        double avgQ = inserted > 0 ? (sumQ / inserted) : 0.0;
        skipped += skippedRef.v;
        return new Result(inserted, skipped, avgQ, chapters, examples);
    }

    // helper para serializar a posição
    private static String posToLocation(Block p) {
        return "spine=" + p.spineIdx() + ";block=" + p.blockIdx() + ";sent=" + p.sentIdx();
    }


    // ===== Helpers de IO CSV (escrita de uma linha do inbox staging) =====
    // --- UPDATED FILE: com/dnobretech/jarvistradutorbackend/service/impl/EPUBPairImportServiceImpl.java ---
// altera a assinatura para receber chapter_en e chapter_pt:
    private static void writeBookpairInboxCsvLine(
            Writer w,
            String src, String tgt,
            String langSrc, String langTgt,
            Double quality,
            Long seriesId, Long bookId,
            String chapterEn, String chapterPt, // NEW
            String chapterLegacy,               // será chapter_en para compat
            String location,
            String sourceTag,
            Double qeScore,
            Double btChrf,
            Double finalScore
    ) throws IOException {
        w.write('"');
        w.write(esc(src));
        w.write('"');
        w.write(',');
        w.write('"');
        w.write(esc(tgt));
        w.write('"');
        w.write(',');
        w.write('"');
        w.write(esc(langSrc));
        w.write('"');
        w.write(',');
        w.write('"');
        w.write(esc(langTgt));
        w.write('"');
        w.write(',');
        if (quality != null) w.write(Double.toString(quality));
        w.write(',');
        if (seriesId != null) w.write(seriesId.toString());
        w.write(',');
        if (bookId != null) w.write(bookId.toString());
        w.write(',');
        if (chapterEn != null && !chapterEn.isBlank()) {
            w.write('"');
            w.write(esc(chapterEn));
            w.write('"');
        }
        w.write(',');
        if (chapterPt != null && !chapterPt.isBlank()) {
            w.write('"');
            w.write(esc(chapterPt));
            w.write('"');
        }
        w.write(',');
        if (chapterLegacy != null && !chapterLegacy.isBlank()) {
            w.write('"');
            w.write(esc(chapterLegacy));
            w.write('"');
        }
        w.write(',');
        if (location != null && !location.isBlank()) {
            w.write('"');
            w.write(esc(location));
            w.write('"');
        }
        w.write(',');
        if (sourceTag != null && !sourceTag.isBlank()) {
            w.write('"');
            w.write(esc(sourceTag));
            w.write('"');
        }
        w.write(',');
        if (qeScore != null) w.write(Double.toString(qeScore));
        w.write(',');
        if (btChrf != null) w.write(Double.toString(btChrf));
        w.write(',');
        if (finalScore != null) w.write(Double.toString(finalScore));
        w.write('\n');
    }


    private static String esc(String s) {
        return s.replace("\"", "\"\"");
    }

    // ===== Esquema de staging de embeddings (book-pair) =====
    private void ensureBookpairEmbStagingSchema() {
        jdbc.execute("""
                  CREATE TABLE IF NOT EXISTS tm_bookpair_emb_staging (
                    src       text NOT NULL,
                    tgt       text NOT NULL,
                    lang_src  text NOT NULL,
                    lang_tgt  text NOT NULL,
                    emb_src   vector(384),
                    emb_tgt   vector(384),
                    quality   double precision,
                    created_at timestamp default now()
                  )
                """);
    }

    // --- UPDATED FILE: EPUBPairImportServiceImpl.java ---
    private static class PendingItem {
        private final String src, tgt;
        private final String chapterEn, chapterPt; // NEW
        private final String location;
        private final double qRule;
        private final double sim;
        private Double qeScore;
        private Double finalScore;

        PendingItem(String src, String tgt, String chapterEn, String chapterPt, String location, double qRule, double sim) {
            this.src = src; this.tgt = tgt;
            this.chapterEn = chapterEn; this.chapterPt = chapterPt; // NEW
            this.location = location;
            this.qRule = qRule; this.sim = sim;
        }
    }


    // === (5.2) Lógica de composição de score ===
    private static double computeFinalScore(double sim01, double qRule01, double qe01 /*, Double bt01 opcional */) {
        // pesos iniciais:
        double sim = clamp01(sim01);
        double q = clamp01(qRule01);
        double qe = clamp01(qe01);
        // sem BT por enquanto: 0.45*sim + 0.35*qe + 0.20*q
        return 0.45 * sim + 0.35 * qe + 0.20 * q;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // converte QE bruto para 0..1 quando necessário
    private static double normalizeQeTo01(Double qeRaw) {
        if (qeRaw == null) return 0.0;
        double x = qeRaw;
        // muitos checkpoints já devolvem ~0..1. Quando vier ~[-1,1], normaliza:
        if (x < 0.0 && x >= -1.0) return (x + 1.0) / 2.0;
        if (x > 1.0 && x <= 2.0) return Math.max(0.0, Math.min(1.0, x / 2.0));
        return clamp01(x);
    }

    // === Enriquecimento com QE (chama /qe em lote) ===
    private void enrichWithQE(List<PendingItem> items) {
        if (items.isEmpty()) return;
        var req = new ArrayList<QeClient.QEItem>(items.size());
        for (var it : items) {
            var qi = new QeClient.QEItem();
            qi.setSrc(it.src);
            qi.setMt(it.tgt); // QE ref-free usa 'mt' (hipótese)
            req.add(qi);
        }
        var scores = qeClient.scoreBatch(req);
        for (int i = 0; i < items.size(); i++) {
            var it = items.get(i);
            Double qeRaw = (i < scores.size() ? scores.get(i) : null);
            it.qeScore = qeRaw;

            double qe01 = normalizeQeTo01(qeRaw);
            it.finalScore = computeFinalScore(it.sim, it.qRule, qe01);
        }
    }

    // === (5.3) Flush do lote para o CSV do staging ===
    // --- UPDATED SIGNATURE:
    // escreve TODO o lote no STAGING; decide embeddings via flag embedOnlyApproved
    // --- UPDATED SIGNATURE (se necessário você já a alterou acima) ---
    private int flushPendingToStaging(
            Writer out,
            List<PendingItem> items,
            String srcLang, String tgtLang,
            Long seriesId, Long bookId,
            String sourceTag,
            double minQualityGate,
            List<ExamplePair> examples,
            IntRef skippedRef,
            boolean doEmb,
            List<String> bufSrc,
            List<String> bufTgt,
            List<Double> bufQ
    ) throws IOException {

        int written = 0;
        for (var it : items) {
            // preenche 'chapter' legado com chapter_en para compat
            String legacyChapter = it.chapterEn;

            writeBookpairInboxCsvLine(
                    out,
                    it.src, it.tgt, srcLang, tgtLang, it.qRule,
                    seriesId, bookId,
                    it.chapterEn, it.chapterPt, legacyChapter,  // NEW trio
                    it.location,
                    sourceTag,
                    it.qeScore,
                    null,
                    it.finalScore
            );
            written++;

            if (examples.size() < 10) {
                examples.add(new ExamplePair(it.src, it.tgt, it.qRule));
            }

            if (doEmb) {
                boolean approvedForEmb = !embedOnlyApproved || (it.finalScore != null && it.finalScore >= 0.55);
                if (approvedForEmb) {
                    bufSrc.add(it.src);
                    bufTgt.add(it.tgt);
                    bufQ.add(it.qRule);
                }
            }
        }
        return written;
    }


    // --- NEW (no mesmo arquivo ou numa classe util sua):
    private static class IntRef {
        int v;

        IntRef(int v) {
            this.v = v;
        }
    }

    private String classifyStatus(Double finalScore, Double qeScore) {
        double f = finalScore != null ? finalScore : 0.0;
        double q = qeScore != null ? qeScore : 0.0;
        if (f >= goodMin && q >= qeGoodMin) return "good";
        if (f >= suspectMin) return "suspect";
        return "bad";
    }

}
