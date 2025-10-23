package com.dnobretech.jarvistradutorbackend.epubimport;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.copy.PGCopyOutputStream;
import org.postgresql.core.BaseConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;

@Slf4j
@Component
@RequiredArgsConstructor
public class InboxWriter {

    @Value("${jarvis.scoring.good-min:0.80}")
    private double goodMinValue;

    @Value("${jarvis.scoring.suspect-min:0.55}")
    private double suspectMinValue;

    @Value("${jarvis.scoring.qe-good-min:0.75}")
    private double qeGoodMinValue;

    private final DataSource dataSource;
    private final SchemaEnsurer schema;

    /** Contexto de COPY (fechou = finaliza o COPY). */
    public static class CopyCtx implements AutoCloseable {
        public final Writer writer;
        private final PGCopyOutputStream pgOut;
        private final Connection con;
        private final DataSource ds;

        private CopyCtx(Writer writer, PGCopyOutputStream pgOut, Connection con, DataSource ds) {
            this.writer = writer;
            this.pgOut = pgOut;
            this.con = con;
            this.ds = ds;
        }

        @Override
        public void close() {
            try { writer.flush(); } catch (IOException ignore) {}
            try { pgOut.endCopy(); } catch (Exception e) {
                log.warn("endCopy tm_bookpair_inbox_staging: {}", e.toString());
            }
            DataSourceUtils.releaseConnection(con, ds);
        }
    }

    /** Abre COPY para **tm_bookpair_inbox_staging**. */
    // --- UPDATED FILE: com/dnobretech/jarvistradutorbackend/epubimport/InboxWriter.java ---
    public CopyCtx openBookpairInboxStagingCopy() throws Exception {
        schema.ensureBookpairSchemas();
        Connection con = DataSourceUtils.getConnection(dataSource);
        BaseConnection base = con.unwrap(BaseConnection.class);
        PGCopyOutputStream pgOut = new PGCopyOutputStream(
                base,
                "COPY tm_bookpair_inbox_staging(" +
                        // ORDEM IMPORTANTE:
                        "src,tgt,lang_src,lang_tgt,quality,series_id,book_id," +
                        "chapter_en,chapter_pt,chapter," + // NEW cols + legado
                        "location,source_tag,qe_score,bt_chrf,final_score" +
                        ") FROM STDIN WITH (FORMAT csv, HEADER true)"
        );
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(pgOut, StandardCharsets.UTF_8), 1 << 16);
        w.write("src,tgt,lang_src,lang_tgt,quality,series_id,book_id,chapter_en,chapter_pt,chapter,location,source_tag,qe_score,bt_chrf,final_score\n");
        return new CopyCtx(w, pgOut, con, dataSource);
    }


    //MERGE!!!
    /** Consolida staging → inbox sem erro de duplicidade (requer Postgres 15+ por MERGE). */
    // com.dnobretech.jarvistradutorbackend.epubimport.InboxWriter.java
    // --- UPDATED FILE: com/dnobretech/jarvistradutorbackend/epubimport/InboxWriter.java ---
    public int mergeBookpairInboxFromStaging(JdbcTemplate jdbc) {
        jdbc.execute("""
        CREATE TEMP TABLE IF NOT EXISTS _bp_dedup AS
        SELECT DISTINCT ON (
            src,tgt,lang_src,lang_tgt,
            COALESCE(series_id,0),COALESCE(book_id,0),COALESCE(source_tag,'')
        )
            src,tgt,lang_src,lang_tgt,quality,series_id,book_id,
            chapter,chapter_en,chapter_pt,          -- NEW
            location,source_tag,
            qe_score,bt_chrf,final_score,created_at
        FROM tm_bookpair_inbox_staging
        ORDER BY
            src,tgt,lang_src,lang_tgt,
            COALESCE(series_id,0),COALESCE(book_id,0),COALESCE(source_tag,''),
            quality DESC, created_at DESC
    """);

        double goodMin    = goodMinValue;
        double suspectMin = suspectMinValue;
        double qeGoodMin  = qeGoodMinValue;

        String sql = """
        MERGE INTO tm_bookpair_inbox t
        USING _bp_dedup s
        ON (
             t.src = s.src AND
             t.tgt = s.tgt AND
             t.lang_src = s.lang_src AND
             t.lang_tgt = s.lang_tgt AND
             COALESCE(t.series_id,0) = COALESCE(s.series_id,0) AND
             COALESCE(t.book_id,0)   = COALESCE(s.book_id,0) AND
             COALESCE(t.source_tag,'') = COALESCE(s.source_tag,'')
           )
        WHEN NOT MATCHED THEN INSERT
          (src,tgt,lang_src,lang_tgt,quality,series_id,book_id,
           chapter_en,chapter_pt,chapter,     -- NEW + legado
           location,source_tag,
           status,created_at,qe_score,bt_chrf,final_score)
          VALUES
          (s.src,s.tgt,s.lang_src,s.lang_tgt,s.quality,s.series_id,s.book_id,
           s.chapter_en, s.chapter_pt, COALESCE(s.chapter, s.chapter_en),
           s.location,s.source_tag,
           CASE
             WHEN COALESCE(s.final_score,0) >= ? AND COALESCE(s.qe_score,0) >= ? THEN 'good'
             WHEN COALESCE(s.final_score,0) >= ? THEN 'suspect'
             ELSE 'bad'
           END,
           now(), s.qe_score, s.bt_chrf, s.final_score)
        WHEN MATCHED THEN UPDATE SET
          quality     = GREATEST(t.quality, s.quality),
          qe_score    = COALESCE(s.qe_score, t.qe_score),
          bt_chrf     = COALESCE(s.bt_chrf,  t.bt_chrf),
          final_score = COALESCE(s.final_score, t.final_score),
          -- Preencher capítulos novos se ainda faltam (não rebaixa valores existentes)
          chapter_en  = COALESCE(t.chapter_en, s.chapter_en),
          chapter_pt  = COALESCE(t.chapter_pt, s.chapter_pt),
          -- manter 'chapter' legado coerente (se vazio, usar EN)
          chapter     = COALESCE(t.chapter, COALESCE(s.chapter, s.chapter_en)),
          status = CASE
                     WHEN t.status = 'good' THEN 'good'
                     WHEN COALESCE(s.final_score,0) >= ? AND COALESCE(s.qe_score,0) >= ? THEN 'good'
                     WHEN t.status = 'suspect' AND COALESCE(s.final_score,0) >= ? THEN 'suspect'
                     WHEN t.status IN ('pending','rejected') AND COALESCE(s.final_score,0) >= ? THEN 'suspect'
                     ELSE t.status
                   END
    """;

        int affected = jdbc.update(
                sql,
                // THEN INSERT (CASE):
                goodMin, qeGoodMin, suspectMin,
                // UPDATE (promotions):
                goodMin, qeGoodMin, suspectMin, suspectMin
        );

        jdbc.execute("TRUNCATE tm_bookpair_inbox_staging");
        jdbc.execute("DROP TABLE IF EXISTS _bp_dedup");
        return affected;
    }




}
