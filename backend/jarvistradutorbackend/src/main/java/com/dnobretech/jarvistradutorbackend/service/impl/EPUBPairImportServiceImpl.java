package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.dto.*;
import com.dnobretech.jarvistradutorbackend.epubimport.*;
import com.dnobretech.jarvistradutorbackend.service.EPUBPairImportService;
import com.dnobretech.jarvistradutorbackend.util.TextNormalizer;
import lombok.RequiredArgsConstructor;
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

    public EPUBPairImportServiceImpl(
            DataSource dataSource, JdbcTemplate jdbc, TextNormalizer norm, EpubExtractor epubExtractor, QualityFilter qualityFilter, EmbeddingService embeddingService, InboxWriter inboxWriter, SchemaEnsurer schemaEnsurer, @Qualifier("lengthAligner") Aligner lengthAligner,
            @Qualifier("embeddingAlignerHungarian") Aligner embeddingAlignerHungarian
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
        List<Double> bufQ  = new ArrayList<>(BATCH);

        // Dedupe leve por (src,tgt,langs,location) dentro do lote
        Set<String> seen = new HashSet<>(aligned.size() * 2);

        try (InboxWriter.CopyCtx ctx = inboxWriter.openBookpairInboxStagingCopy()) {
            Writer out = ctx.writer;

            for (AlignedPair ap : aligned) {
                String src = norm.normalize(ap.src());
                String tgt = norm.normalize(ap.tgt());
                if (src.isBlank() || tgt.isBlank()) { skipped++; continue; }

                // posição do SRC (usaremos para chapter/location)
                String chapter  = ap.srcPos().chapterTitle();
                String location = posToLocation(ap.srcPos());

                String key = src + "\u0001" + tgt + "\u0001" + srcLang + "\u0001" + tgtLang + "\u0001" + location;
                if (!seen.add(key)) { skipped++; continue; }

                // qualidade
                double r  = qualityFilter.lengthRatio(src, tgt);
                boolean ph = qualityFilter.placeholdersPreserved(src, tgt);
                if (r < ratioMin || r > ratioMax || !ph) { skipped++; continue; }

                double q = qualityFilter.qualityScore(r, ph, ratioMin, ratioMax);
                if (q < minQuality) { skipped++; continue; }

                // 3.1) CSV → staging do inbox (agora inclui chapter/location)
                writeBookpairInboxCsvLine(
                        out,
                        src, tgt, srcLang, tgtLang, q,
                        seriesId, bookId,
                        chapter, location,
                        sourceTag
                );

                inserted++;
                sumQ += q;
                if (examples.size() < 10) examples.add(new ExamplePair(src, tgt, q));

                // 3.2) Embeddings → arquivo temporário
                if (doEmb) {
                    bufSrc.add(src);
                    bufTgt.add(tgt);
                    bufQ.add(q);
                    if (bufSrc.size() >= BATCH) {
                        embeddingService.flushEmbeddingsToFile(embFileWriter, bufSrc, bufTgt, srcLang, tgtLang, bufQ, true);
                    }
                }
            }

            if (doEmb && !bufSrc.isEmpty()) {
                embeddingService.flushEmbeddingsToFile(embFileWriter, bufSrc, bufTgt, srcLang, tgtLang, bufQ, true);
            }
        } finally {
            if (embFileWriter != null) {
                try { embFileWriter.close(); } catch (IOException ignore) {}
            }
        }

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
        return new Result(inserted, skipped, avgQ, chapters, examples);
    }

    // helper para serializar a posição
    private static String posToLocation(Block p) {
        return "spine=" + p.spineIdx() + ";block=" + p.blockIdx() + ";sent=" + p.sentIdx();
    }


    // ===== Helpers de IO CSV (escrita de uma linha do inbox staging) =====
    private static void writeBookpairInboxCsvLine(
            Writer w,
            String src, String tgt,
            String langSrc, String langTgt,
            Double quality,
            Long seriesId, Long bookId,
            String chapter, String location,
            String sourceTag
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
        if (chapter != null && !chapter.isBlank()) {
            w.write('"');
            w.write(esc(chapter));
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


}
