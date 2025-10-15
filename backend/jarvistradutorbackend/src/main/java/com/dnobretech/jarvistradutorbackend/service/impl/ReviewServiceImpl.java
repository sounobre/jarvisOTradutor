// service/impl/ReviewServiceImpl.java
package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {
    private final JdbcTemplate jdbc;

    @Override @Transactional
    public int approveCorpora(java.util.List<Long> ids, String reviewer) {
        if (ids==null || ids.isEmpty()) return 0;
        String sql = "UPDATE tm_corpora_inbox SET status='approved', reviewer=?, reviewed_at=now() WHERE id = ANY(?) AND status='pending'";
        return jdbc.update(sql, reviewer, ids.toArray(new Long[0]));
    }
    @Override @Transactional
    public int rejectCorpora(java.util.List<Long> ids, String reviewer) {
        if (ids==null || ids.isEmpty()) return 0;
        String sql = "UPDATE tm_corpora_inbox SET status='rejected', reviewer=?, reviewed_at=now() WHERE id = ANY(?) AND status IN ('pending','approved')";
        return jdbc.update(sql, reviewer, ids.toArray(new Long[0]));
    }

    @Override @Transactional
    public int approveBookpairs(java.util.List<Long> ids, String reviewer) {
        if (ids==null || ids.isEmpty()) return 0;
        String sql = "UPDATE tm_bookpair_inbox SET status='approved', reviewer=?, reviewed_at=now() WHERE id = ANY(?) AND status='pending'";
        return jdbc.update(sql, reviewer, ids.toArray(new Long[0]));
    }
    @Override @Transactional
    public int rejectBookpairs(java.util.List<Long> ids, String reviewer) {
        if (ids==null || ids.isEmpty()) return 0;
        String sql = "UPDATE tm_bookpair_inbox SET status='rejected', reviewer=?, reviewed_at=now() WHERE id = ANY(?) AND status IN ('pending','approved')";
        return jdbc.update(sql, reviewer, ids.toArray(new Long[0]));
    }

    // ===== COMMIT =====

    @Override @Transactional
    public int commitCorporaApproved() {
        // 1) TM
        int up = jdbc.update("""
            INSERT INTO tm (src,tgt,lang_src,lang_tgt,quality)
            SELECT c.src, c.tgt, c.lang_src, c.lang_tgt, c.quality
              FROM tm_corpora_inbox c
             WHERE c.status='approved'
            ON CONFLICT (src,tgt,lang_src,lang_tgt)
            DO UPDATE SET quality = GREATEST(tm.quality, EXCLUDED.quality)
        """);

        // 2) Embeddings (se existirem em tm_corpora_emb_staging)
        int emb = jdbc.update("""
            WITH dedup AS (
              SELECT DISTINCT ON (src,tgt,lang_src,lang_tgt)
                     src,tgt,lang_src,lang_tgt,emb_src,emb_tgt,quality,created_at
              FROM tm_corpora_emb_staging
              ORDER BY src,tgt,lang_src,lang_tgt,quality DESC, created_at DESC
            )
            INSERT INTO tm_embeddings (tm_id, emb_src, emb_tgt)
            SELECT t.id, d.emb_src, d.emb_tgt
              FROM tm t
              JOIN dedup d
                ON t.src=d.src AND t.tgt=d.tgt AND t.lang_src=d.lang_src AND t.lang_tgt=d.lang_tgt
            ON CONFLICT (tm_id) DO UPDATE
              SET emb_src = EXCLUDED.emb_src,
                  emb_tgt = COALESCE(EXCLUDED.emb_tgt, tm_embeddings.emb_tgt)
        """);

        // opcional: limpar staging de embeddings já consumidos
        // jdbc.execute("TRUNCATE tm_corpora_emb_staging");

        return up; // retorna qtde upserts na TM
    }

    @Override @Transactional
    public int commitBookpairsApproved() {
        // 1) TM (garante o pareamento na TM)
        int up = jdbc.update("""
            INSERT INTO tm (src,tgt,lang_src,lang_tgt,quality)
            SELECT b.src, b.tgt, b.lang_src, b.lang_tgt, b.quality
              FROM tm_bookpair_inbox b
             WHERE b.status='approved'
            ON CONFLICT (src,tgt,lang_src,lang_tgt)
            DO UPDATE SET quality = GREATEST(tm.quality, EXCLUDED.quality)
        """);

        // 2) Occurrence (agora sim, depois que TM existe)
        int occ = jdbc.update("""
            INSERT INTO tm_occurrence (tm_id, series_id, book_id, chapter, location, quality_at_import, source_tag)
            SELECT t.id, b.series_id, b.book_id, b.chapter, b.location, b.quality, b.source_tag
              FROM tm_bookpair_inbox b
              JOIN tm t
                ON t.src=b.src AND t.tgt=b.tgt AND t.lang_src=b.lang_src AND t.lang_tgt=b.lang_tgt
             WHERE b.status='approved'
        """);

        // 3) Embeddings de bookpair (se existirem)
        int emb = jdbc.update("""
            WITH dedup AS (
              SELECT DISTINCT ON (src,tgt,lang_src,lang_tgt)
                     src,tgt,lang_src,lang_tgt,emb_src,emb_tgt,quality,created_at
              FROM tm_bookpair_emb_staging
              ORDER BY src,tgt,lang_src,lang_tgt,quality DESC, created_at DESC
            )
            INSERT INTO tm_embeddings (tm_id, emb_src, emb_tgt)
            SELECT t.id, d.emb_src, d.emb_tgt
              FROM tm t
              JOIN dedup d
                ON t.src=d.src AND t.tgt=d.tgt AND t.lang_src=d.lang_src AND t.lang_tgt=d.lang_tgt
            ON CONFLICT (tm_id) DO UPDATE
              SET emb_src = EXCLUDED.emb_src,
                  emb_tgt = COALESCE(EXCLUDED.emb_tgt, tm_embeddings.emb_tgt)
        """);

        return up + occ; // métrica simples
    }
}
