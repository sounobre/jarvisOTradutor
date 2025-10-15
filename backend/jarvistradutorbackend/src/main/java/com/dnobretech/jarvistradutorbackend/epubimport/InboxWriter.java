package com.dnobretech.jarvistradutorbackend.epubimport;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.copy.PGCopyOutputStream;
import org.postgresql.core.BaseConnection;
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
    public CopyCtx openBookpairInboxStagingCopy() throws Exception {
        schema.ensureBookpairSchemas(); // garante tudo antes
        Connection con = DataSourceUtils.getConnection(dataSource);
        BaseConnection base = con.unwrap(BaseConnection.class);
        PGCopyOutputStream pgOut = new PGCopyOutputStream(
                base,
                "COPY tm_bookpair_inbox_staging(" +
                        "src,tgt,lang_src,lang_tgt,quality,series_id,book_id,chapter,location,source_tag" +
                        ") FROM STDIN WITH (FORMAT csv, HEADER true)"
        );
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(pgOut, StandardCharsets.UTF_8), 1 << 16);
        w.write("src,tgt,lang_src,lang_tgt,quality,series_id,book_id,chapter,location,source_tag\n");
        return new CopyCtx(w, pgOut, con, dataSource);
    }

    /** Consolida staging → inbox sem erro de duplicidade (requer Postgres 15+ por MERGE). */
    public int mergeBookpairInboxFromStaging(JdbcTemplate jdbc) {
        // DEDUP do staging por (src,tgt,langs,series,book,source_tag) escolhendo maior qualidade
        jdbc.execute("""
            CREATE TEMP TABLE IF NOT EXISTS _bp_dedup AS
            SELECT DISTINCT ON (src,tgt,lang_src,lang_tgt,COALESCE(series_id,0),COALESCE(book_id,0),COALESCE(source_tag,''))
                   src,tgt,lang_src,lang_tgt,quality,series_id,book_id,chapter,location,source_tag
            FROM tm_bookpair_inbox_staging
            ORDER BY src,tgt,lang_src,lang_tgt,COALESCE(series_id,0),COALESCE(book_id,0),COALESCE(source_tag,''), quality DESC, created_at DESC
        """);

        int affected = jdbc.update("""
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
              (src,tgt,lang_src,lang_tgt,quality,series_id,book_id,chapter,location,source_tag,status,created_at)
              VALUES
              (s.src,s.tgt,s.lang_src,s.lang_tgt,s.quality,s.series_id,s.book_id,s.chapter,s.location,s.source_tag,'pending',now())
            WHEN MATCHED THEN UPDATE SET
              quality = GREATEST(t.quality, s.quality)
        """);

        jdbc.execute("TRUNCATE tm_bookpair_inbox_staging");
        jdbc.execute("DROP TABLE IF EXISTS _bp_dedup");
        return affected;
    }
}
