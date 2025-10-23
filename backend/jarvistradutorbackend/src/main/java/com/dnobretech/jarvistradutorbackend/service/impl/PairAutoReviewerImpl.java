package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.client.TranslateClient;
import com.dnobretech.jarvistradutorbackend.epubimport.metrics.Chrf;
import com.dnobretech.jarvistradutorbackend.service.NBestRerankerService;
import com.dnobretech.jarvistradutorbackend.service.PairAutoReviewer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class PairAutoReviewerImpl implements PairAutoReviewer {

    private final JdbcTemplate jdbc;
    private final TranslateClient translate;
    private final NBestRerankerService reranker;

    // thresholds (ajuste à vontade)
    private final double GOOD_MIN_FINAL = 0.80;
    private final double SUSPECT_MIN_FINAL = 0.55;
    private final double GOOD_MIN_CHRF = 75.0;  // chrF 0..100
    private final double SUSPECT_MIN_CHRF = 55.0;

    private static record Row(
            long id, String src, String tgt,
            String langSrc, String langTgt,
            Double finalScore, String chapterEn, String chapterPt
    ) {
    }

    @Override
    public int reviewBatch(double minFinal, double maxFinal, int limit, int k, String mode) {
        final boolean auto = !"manual".equalsIgnoreCase(mode); // default: auto

        String sql = """
                    SELECT id, src, tgt, lang_src, lang_tgt, final_score, chapter AS chapter_en, chapter_pt
                      FROM tm_bookpair_inbox
                     WHERE status IN ('suspect','bad')
                       AND COALESCE(final_score, 0.0) BETWEEN ? AND ?
                       AND lang_src = 'en' AND lang_tgt = 'pt'
                     ORDER BY created_at DESC
                     LIMIT ?
                """;

        List<Row> rows = jdbc.query(sql, ps -> {
            ps.setDouble(1, minFinal);
            ps.setDouble(2, maxFinal);
            ps.setInt(3, limit);
        }, (ResultSet rs, int i) -> new Row(
                rs.getLong("id"),
                rs.getString("src"),
                rs.getString("tgt"),
                rs.getString("lang_src"),
                rs.getString("lang_tgt"),
                (Double) rs.getObject("final_score"),
                rs.getString("chapter_en"),
                rs.getString("chapter_pt")
        ));

        if (rows.isEmpty()) {
            log.info("[auto-review] nada a revisar no range {}..{}.", minFinal, maxFinal);
            return 0;
        }

        int updated = 0;
        List<Object[]> batchParams = new ArrayList<>(rows.size());

        for (Row r : rows) {
            try {
                // 1) back-translation PT->EN e chrF
                var bt = translate.translate(r.tgt(), "por_Latn", "eng_Latn");
                String backEn = bt != null ? bt.getTranslation() : "";
                double chrf = Chrf.chrf(r.src(), backEn);

                // 2) classificar
                String status = classify(r.finalScore(), chrf);

                // 3) se não for "good", gerar sugestão EN->PT com N-best + QE
                String suggestionPt = null;
                Double qeBest = null;
                String candidatesJson = null;
                Integer bestIndex = null;
                String commentExtra = "";

                if (!"good".equals(status)) {
                    var rr = reranker.suggestBest(r.src(), "eng_Latn", "por_Latn", Math.max(1, k));
                    suggestionPt = rr.getBest();
                    qeBest = rr.getQeScores().get(rr.getBestIndex());
                    candidatesJson = toJson(rr.getCandidates());
                    bestIndex = rr.getBestIndex();
                    commentExtra = String.format(Locale.US, " ; qe_best=%.3f", qeBest);
                    log.info("id={} melhor sugestão QE={} -> {}", r.id(), qeBest, suggestionPt);
                }

                String comment = buildComment(r.chapterEn(), r.chapterPt(), chrf, r.finalScore()) + commentExtra;

                // 4) acumula update
                batchParams.add(new Object[]{
                        chrf,                             // 1  bt_chrf
                        status,                           // 2  status
                        "nllb-200-distilled-1.3B",        // 3  reviewer
                        OffsetDateTime.now(),             // 4  reviewed_at
                        comment,                          // 5  review_comment
                        suggestionPt,                     // 6  review_suggestion
                        qeBest,                           // 7  qe_best
                        candidatesJson,                   // 8  review_candidates (jsonb)
                        bestIndex,                        // 9  review_best_index
                        r.id()                            // 10 WHERE id
                });

                updated++;
            } catch (Exception e) {
                log.warn("[auto-review] falha ao revisar id={} -> {}", r.id(), e.toString());
            }
        }

        // 5) aplicar updates em batch


        String up = """
                    UPDATE tm_bookpair_inbox
                       SET bt_chrf = ?,
                           status = ?,
                           reviewer = ?,
                           reviewed_at = ?,
                           review_comment = ?,
                           review_suggestion = ?,
                           qe_best = ?,
                           review_candidates = ?::jsonb,   -- 👈 CAST aqui
                           review_best_index = ?
                     WHERE id = ?
                """;

        jdbc.batchUpdate(up, batchParams);

        log.info("[auto-review] revisados {} pares ({}..{}, limit={}, k={}, mode={})",
                updated, minFinal, maxFinal, limit, k, mode);
        return updated;
    }

    private String classify(Double finalScore, double chrf) {
        double f = finalScore != null ? finalScore : 0.0;
        boolean good = (f >= GOOD_MIN_FINAL && chrf >= GOOD_MIN_CHRF);
        boolean suspect = (!good) && (f >= SUSPECT_MIN_FINAL || chrf >= SUSPECT_MIN_CHRF);
        if (good) return "good";
        if (suspect) return "suspect";
        return "bad";
    }

    private String buildComment(String chapterEn, String chapterPt, double chrf, Double finalScore) {
        String fStr = String.format(Locale.US, "%.3f", finalScore != null ? finalScore : 0.0);
        String cStr = String.format(Locale.US, "%.2f", chrf);
        StringBuilder sb = new StringBuilder();
        sb.append("bt_chrf=").append(cStr).append("; final=").append(fStr);
        if (chapterEn != null && chapterPt != null && !chapterEn.equalsIgnoreCase(chapterPt)) {
            sb.append("; capítulos possivelmente diferentes");
        }
        return sb.toString();
    }

    // --- helper JSON ---
    private static String toJson(Object obj) {
        try {
            return new ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
