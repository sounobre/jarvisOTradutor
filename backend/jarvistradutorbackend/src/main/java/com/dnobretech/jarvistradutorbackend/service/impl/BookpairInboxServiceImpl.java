package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.dto.BookpairInboxRow;
import com.dnobretech.jarvistradutorbackend.service.BookpairInboxService;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookpairInboxServiceImpl implements BookpairInboxService {

    private final JdbcTemplate jdbc;

    @Override
    public List<BookpairInboxRow> list(String status, Long seriesId, Long bookId, String sourceTag, int page, int size) {
        StringBuilder sql = new StringBuilder("""
            SELECT id, src, tgt, lang_src, lang_tgt, quality, series_id, book_id,
                   chapter, location, source_tag, status, reviewer, reviewed_at, created_at
            FROM tm_bookpair_inbox
            WHERE 1=1
        """);
        List<Object> args = new ArrayList<>();

        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ? ");
            args.add(status);
        }
        if (seriesId != null) {
            sql.append(" AND series_id = ? ");
            args.add(seriesId);
        }
        if (bookId != null) {
            sql.append(" AND book_id = ? ");
            args.add(bookId);
        }
        if (sourceTag != null && !sourceTag.isBlank()) {
            sql.append(" AND source_tag ILIKE ? ");
            args.add('%' + sourceTag + '%');
        }

        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ? ");
        args.add(size <= 0 ? 50 : size);
        args.add(Math.max(0, page) * (size <= 0 ? 50 : size));

        return jdbc.query(sql.toString(), args.toArray(), this::mapRow);
    }

    @Override
    public long count(String status, Long seriesId, Long bookId, String sourceTag) {
        StringBuilder sql = new StringBuilder("""
            SELECT count(*) FROM tm_bookpair_inbox WHERE 1=1
        """);
        List<Object> args = new ArrayList<>();

        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ? ");
            args.add(status);
        }
        if (seriesId != null) {
            sql.append(" AND series_id = ? ");
            args.add(seriesId);
        }
        if (bookId != null) {
            sql.append(" AND book_id = ? ");
            args.add(bookId);
        }
        if (sourceTag != null && !sourceTag.isBlank()) {
            sql.append(" AND source_tag ILIKE ? ");
            args.add('%' + sourceTag + '%');
        }

        return jdbc.queryForObject(sql.toString(), args.toArray(), Long.class);
    }

    private com.dnobretech.jarvistradutorbackend.dto.BookpairInboxRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Timestamp reviewedTs = rs.getTimestamp("reviewed_at"); // pode ser null
        java.sql.Timestamp createdTs  = rs.getTimestamp("created_at");  // geralmente não-null

        return new com.dnobretech.jarvistradutorbackend.dto.BookpairInboxRow(
                rs.getLong("id"),
                rs.getString("src"),
                rs.getString("tgt"),
                rs.getString("lang_src"),
                rs.getString("lang_tgt"),
                (Double) rs.getObject("quality"),
                (Long) rs.getObject("series_id"),
                (Long) rs.getObject("book_id"),
                rs.getString("chapter"),
                rs.getString("location"),
                rs.getString("source_tag"),
                rs.getString("status"),
                rs.getString("reviewer"),
                reviewedTs == null ? null : reviewedTs.toInstant().atOffset(java.time.ZoneOffset.UTC),
                createdTs  == null ? null : createdTs.toInstant().atOffset(java.time.ZoneOffset.UTC)
        );
    }

    @Override
    public int approve(long id, String reviewer) {
        return jdbc.update("""
            UPDATE tm_bookpair_inbox
               SET status='approved', reviewer = COALESCE(?, reviewer), reviewed_at = now()
             WHERE id = ? AND status <> 'approved'
        """, reviewer, id);
    }

    @Override
    public int reject(long id, String reviewer, String reason) {
        return jdbc.update("""
        UPDATE tm_bookpair_inbox
           SET status='rejected',
               reviewer = COALESCE(?, reviewer),
               reviewed_at = now(),
               reject_reason = COALESCE(?, reject_reason)
         WHERE id = ? AND status <> 'rejected'
    """, reviewer, reason, id);
    }


    /**
     * Consolida TODOS os registros com status='approved' do inbox:
     * - Upsert em TM (mantendo a melhor quality)
     * - INSERT em TM_OCCURRENCE
     * - Upsert em TM_EMBEDDINGS (usando os vetores de tm_bookpair_emb_staging; pega o de maior quality)
     */
    @Override
    @org.springframework.transaction.annotation.Transactional
    public int consolidateApproved() {
        int tmUpserts = jdbc.update("""
        WITH approved AS (
          SELECT src, tgt, lang_src, lang_tgt, quality
          FROM tm_bookpair_inbox
          WHERE status = 'approved'
        ),
        dedup AS (
          SELECT DISTINCT ON (src, tgt, lang_src, lang_tgt)
                 src, tgt, lang_src, lang_tgt, quality
          FROM approved
          ORDER BY src, tgt, lang_src, lang_tgt, quality DESC
        )
        INSERT INTO tm (src, tgt, lang_src, lang_tgt, quality)
        SELECT src, tgt, lang_src, lang_tgt, quality
        FROM dedup
        ON CONFLICT (src, tgt, lang_src, lang_tgt)
        DO UPDATE SET quality = GREATEST(tm.quality, EXCLUDED.quality)
    """);

        int occ = jdbc.update("""
        INSERT INTO tm_occurrence (tm_id, series_id, book_id, chapter, location, quality_at_import, source_tag)
        SELECT t.id, b.series_id, b.book_id, b.chapter, b.location, b.quality, b.source_tag
          FROM tm_bookpair_inbox b
          JOIN tm t
            ON t.src = b.src AND t.tgt = b.tgt
           AND t.lang_src = b.lang_src AND t.lang_tgt = b.lang_tgt
         WHERE b.status = 'approved'
    """);

        // Preferir embeddings não-nulos e de melhor qualidade
        int emb = jdbc.update("""
        WITH approved AS (
          SELECT src, tgt, lang_src, lang_tgt, quality
          FROM tm_bookpair_inbox
          WHERE status = 'approved'
        ),
        joined AS (
          SELECT t.id AS tm_id, e.emb_src, e.emb_tgt, a.quality,
                 ROW_NUMBER() OVER (
                   PARTITION BY t.id
                   ORDER BY
                     (CASE WHEN e.emb_src IS NULL AND e.emb_tgt IS NULL THEN 1 ELSE 0 END), -- embeddings presentes primeiro
                     a.quality DESC
                 ) rn
          FROM approved a
          JOIN tm t
            ON t.src = a.src AND t.tgt = a.tgt
           AND t.lang_src = a.lang_src AND t.lang_tgt = a.lang_tgt
          LEFT JOIN tm_bookpair_emb_staging e
            ON e.src = a.src AND e.tgt = a.tgt
           AND e.lang_src = a.lang_src AND e.lang_tgt = a.lang_tgt
        )
        INSERT INTO tm_embeddings (tm_id, emb_src, emb_tgt)
        SELECT tm_id, emb_src, emb_tgt
        FROM joined
        WHERE rn = 1
        ON CONFLICT (tm_id) DO UPDATE
          SET emb_src = COALESCE(EXCLUDED.emb_src, tm_embeddings.emb_src),
              emb_tgt = COALESCE(EXCLUDED.emb_tgt, tm_embeddings.emb_tgt)
    """);

        // Se quiser logar detalhado:
        org.slf4j.LoggerFactory.getLogger(getClass()).info(
                "[consolidateApproved] tmUpserts={}, occInserted={}, embUpserts={}", tmUpserts, occ, emb);

        return occ + emb;
    }

}
