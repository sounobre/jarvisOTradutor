// --- UPDATED FILE: com/dnobretech/jarvistradutorbackend/epubimport/SchemaEnsurer.java ---
package com.dnobretech.jarvistradutorbackend.epubimport;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SchemaEnsurer {

    private final JdbcTemplate jdbc;

    public void ensureBookpairSchemas() {
        ensureVectorExtension();
        ensureBookpairInbox();
        ensureBookpairInboxStaging();
        ensureBookpairEmbStaging();
    }

    public void ensureVectorExtension() { jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector"); }

    public void ensureBookpairInbox() {
        jdbc.execute("""
        CREATE TABLE IF NOT EXISTS tm_bookpair_inbox (
          id          bigserial PRIMARY KEY,
          src         text NOT NULL,
          tgt         text NOT NULL,
          lang_src    text NOT NULL,
          lang_tgt    text NOT NULL,
          quality     double precision,
          series_id   bigint,
          book_id     bigint,
          chapter     text,
          chapter_en  text,
          chapter_pt  text,
          location    text,
          source_tag  text,
          qe_score    double precision,
          bt_chrf     double precision,
          final_score double precision,
          status      text NOT NULL DEFAULT 'pending',
          reviewer    text,
          reviewed_at timestamp,
          /* --- Campos de revisão automática --- */
          rev_status  text,                -- 'good' | 'suspect' | 'bad'
          rev_score   double precision,    -- 0..1 (do modelo)
          rev_comment text,                -- observações do modelo
          rev_model   text,                -- ex.: 'gpt-j-6B'
          rev_at      timestamp,           -- quando foi revisado
          created_at  timestamp DEFAULT now()
        )
        """);

        // garantir colunas (migração suave)
        jdbc.execute("ALTER TABLE tm_bookpair_inbox ADD COLUMN IF NOT EXISTS chapter_en  text");
        jdbc.execute("ALTER TABLE tm_bookpair_inbox ADD COLUMN IF NOT EXISTS chapter_pt  text");
        jdbc.execute("ALTER TABLE tm_bookpair_inbox ADD COLUMN IF NOT EXISTS rev_status  text");
        jdbc.execute("ALTER TABLE tm_bookpair_inbox ADD COLUMN IF NOT EXISTS rev_score   double precision");
        jdbc.execute("ALTER TABLE tm_bookpair_inbox ADD COLUMN IF NOT EXISTS rev_comment text");
        jdbc.execute("ALTER TABLE tm_bookpair_inbox ADD COLUMN IF NOT EXISTS rev_model   text");
        jdbc.execute("ALTER TABLE tm_bookpair_inbox ADD COLUMN IF NOT EXISTS rev_at      timestamp");
        jdbc.execute("ALTER TABLE tm_bookpair_inbox ADD COLUMN IF NOT EXISTS qe_best double precision");
        jdbc.execute("ALTER TABLE tm_bookpair_inbox ADD COLUMN IF NOT EXISTS review_best_index int");
        jdbc.execute("ALTER TABLE tm_bookpair_inbox ADD COLUMN IF NOT EXISTS review_candidates jsonb");




        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_bpinbox_langs    ON tm_bookpair_inbox(lang_src,lang_tgt)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_bpinbox_scores   ON tm_bookpair_inbox(final_score DESC, qe_score DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_bpinbox_rev_stat ON tm_bookpair_inbox(rev_status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_bpinbox_created  ON tm_bookpair_inbox(created_at DESC)");
    }

    public void ensureBookpairInboxStaging() {
        jdbc.execute("""
        CREATE TABLE IF NOT EXISTS tm_bookpair_inbox_staging (
          src         text NOT NULL,
          tgt         text NOT NULL,
          lang_src    text NOT NULL,
          lang_tgt    text NOT NULL,
          quality     double precision,
          series_id   bigint,
          book_id     bigint,
          chapter     text,
          chapter_en  text,
          chapter_pt  text,
          location    text,
          source_tag  text,
          qe_score    double precision,
          bt_chrf     double precision,
          final_score double precision,
          created_at  timestamp DEFAULT now()
        )
        """);
        jdbc.execute("ALTER TABLE tm_bookpair_inbox_staging ADD COLUMN IF NOT EXISTS chapter_en text");
        jdbc.execute("ALTER TABLE tm_bookpair_inbox_staging ADD COLUMN IF NOT EXISTS chapter_pt text");
        jdbc.execute("ALTER TABLE tm_bookpair_inbox_staging ADD COLUMN IF NOT EXISTS qe_score double precision");
        jdbc.execute("ALTER TABLE tm_bookpair_inbox_staging ADD COLUMN IF NOT EXISTS bt_chrf double precision");
        jdbc.execute("ALTER TABLE tm_bookpair_inbox_staging ADD COLUMN IF NOT EXISTS final_score double precision");
        jdbc.execute("TRUNCATE tm_bookpair_inbox_staging");
    }

    public void ensureBookpairEmbStaging() {
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
        jdbc.execute("TRUNCATE tm_bookpair_emb_staging");
    }
}
