// service/impl/ReviewServiceImpl.java
package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.client.GptjClient;
import com.dnobretech.jarvistradutorbackend.client.ReviewLLMClient;
import com.dnobretech.jarvistradutorbackend.service.ReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {
    private final JdbcTemplate jdbc;
    private final ReviewLLMClient reviewer;
    private final ObjectMapper om = new ObjectMapper();

    @Override
    @Transactional
    public int approveCorpora(java.util.List<Long> ids, String reviewer) {
        if (ids == null || ids.isEmpty()) return 0;
        String sql = "UPDATE tm_corpora_inbox SET status='approved', reviewer=?, reviewed_at=now() WHERE id = ANY(?) AND status='pending'";
        return jdbc.update(sql, reviewer, ids.toArray(new Long[0]));
    }

    @Override
    @Transactional
    public int rejectCorpora(java.util.List<Long> ids, String reviewer) {
        if (ids == null || ids.isEmpty()) return 0;
        String sql = "UPDATE tm_corpora_inbox SET status='rejected', reviewer=?, reviewed_at=now() WHERE id = ANY(?) AND status IN ('pending','approved')";
        return jdbc.update(sql, reviewer, ids.toArray(new Long[0]));
    }

    @Override
    @Transactional
    public int approveBookpairs(java.util.List<Long> ids, String reviewer) {
        if (ids == null || ids.isEmpty()) return 0;
        String sql = "UPDATE tm_bookpair_inbox SET status='approved', reviewer=?, reviewed_at=now() WHERE id = ANY(?) AND status='pending'";
        return jdbc.update(sql, reviewer, ids.toArray(new Long[0]));
    }

    @Override
    @Transactional
    public int rejectBookpairs(java.util.List<Long> ids, String reviewer) {
        if (ids == null || ids.isEmpty()) return 0;
        String sql = "UPDATE tm_bookpair_inbox SET status='rejected', reviewer=?, reviewed_at=now() WHERE id = ANY(?) AND status IN ('pending','approved')";
        return jdbc.update(sql, reviewer, ids.toArray(new Long[0]));
    }

    // ===== COMMIT =====

    @Override
    @Transactional
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

    @Override
    @Transactional
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

    @Override
    public int reviewPairs(double maxFinalScore, int limit) {
        record Row(long id, String src, String tgt, String chEn, String chPt, Double fscore) {
        }
        var rows = jdbc.query("""
                    SELECT id, src, tgt,
                           COALESCE(chapter_en,'') AS ch_en,
                           COALESCE(chapter_pt,'') AS ch_pt,
                           final_score
                      FROM tm_bookpair_inbox
                     WHERE COALESCE(final_score,0) <= ?
                       AND COALESCE(rev_status,'') = ''   -- ainda não revisados
                     ORDER BY final_score ASC, id ASC
                     LIMIT ?
                """, (rs, i) -> new Row(
                rs.getLong("id"),
                rs.getString("src"),
                rs.getString("tgt"),
                rs.getString("ch_en"),
                rs.getString("ch_pt"),
                (Double) rs.getObject("final_score")
        ), maxFinalScore, limit);

        if (rows.isEmpty()) return 0;

        // monta request p/ API
        var items = new java.util.ArrayList<ReviewLLMClient.Item>(rows.size());
        for (var r : rows) {
            var it = new ReviewLLMClient.Item();
            it.setId(r.id());
            it.setSrc(r.src());
            it.setTgt(r.tgt());
            it.setChapter_en(r.chEn());
            it.setChapter_pt(r.chPt());
            it.setFinal_score(r.fscore());
            items.add(it);
        }

        var res = reviewer.reviewBatch(items, 96, 0.0, 1.0, 1.0);

        int updated = 0;
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            var rr = (i < res.size()) ? res.get(i) : null;

            String status = (rr != null && rr.getStatus() != null) ? rr.getStatus() : "suspect";
            Double score = (rr != null && rr.getScore() != null) ? clamp01(rr.getScore()) : 0.0;
            String comment = (rr != null) ? rr.getComment() : "auto: no_result";

            updated += jdbc.update("""
                        UPDATE tm_bookpair_inbox
                           SET rev_status = ?,
                               rev_score  = ?,
                               rev_comment= ?,
                               rev_model  = 'flan-t5-base',
                               rev_at     = now()
                         WHERE id = ?
                    """, status, score, comment, r.id());
        }
        return updated;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
