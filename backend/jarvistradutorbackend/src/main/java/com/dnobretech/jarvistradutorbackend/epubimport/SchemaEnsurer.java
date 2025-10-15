package com.dnobretech.jarvistradutorbackend.epubimport;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SchemaEnsurer {

    private final JdbcTemplate jdbc;

    /** Chame este método no início do fluxo para garantir tudo que o import precisa. */
    public void ensureBookpairSchemas() {
        ensureVectorExtension();
        ensureBookpairInbox();
        ensureBookpairInboxStaging();
        ensureBookpairEmbStaging();
    }

    public void ensureVectorExtension() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    }

    /** Tabela final (inbox) sem UNIQUE por expressão — vamos consolidar via MERGE. */
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
              location    text,
              source_tag  text,
              status      text NOT NULL DEFAULT 'pending',  -- pending|approved|rejected
              reviewer    text,
              reviewed_at timestamp,
              created_at  timestamp DEFAULT now()
            )
        """);

        // índices úteis para filtros comuns
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_bpinbox_status      ON tm_bookpair_inbox(status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_bpinbox_series      ON tm_bookpair_inbox(series_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_bpinbox_book        ON tm_bookpair_inbox(book_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_bpinbox_source_tag  ON tm_bookpair_inbox(source_tag)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_bpinbox_created_at  ON tm_bookpair_inbox(created_at DESC)");
    }

    /** Staging textual para COPY. */
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
              location    text,
              source_tag  text,
              created_at  timestamp DEFAULT now()
            )
        """);
        jdbc.execute("TRUNCATE tm_bookpair_inbox_staging");
    }

    /** Staging de embeddings (dimensão 384 do MiniLM L12 v2). */
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
