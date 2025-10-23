//package com.dnobretech.jarvistradutorbackend.service.impl;
//
//import com.dnobretech.jarvistradutorbackend.client.BtClient;
//import com.dnobretech.jarvistradutorbackend.epubimport.metrics.Chrf;
//import com.dnobretech.jarvistradutorbackend.service.BtCheckService;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.jdbc.core.JdbcTemplate;
//import org.springframework.stereotype.Service;
//
//import java.util.ArrayList;
//import java.util.List;
//
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class BtCheckServiceImpl implements BtCheckService {
//
//    private final JdbcTemplate jdbc;
//    private final BtClient bt;
//
//    @Value("${jarvis.bt.promote.chrf-min:55.0}")
//    private double chrfMin;
//
//    @Value("${jarvis.scoring.good-min:0.80}")
//    private double goodMin;
//
//    @Value("${jarvis.scoring.suspect-min:0.55}")
//    private double suspectMin;
//
//    @Value("${jarvis.scoring.qe-good-min:0.75}")
//    private double qeGoodMin;
//
//    // NOVO: só BT para final_score baixo
//    @Value("${jarvis.btcheck.only-low-final}")
//    private boolean onlyLowFinal;
//
//    @Value("${jarvis.btcheck.final-threshold}")
//    private double finalThreshold;
//
//    @Override
//    public int runBtCheck(Long bookId, Integer limitRows) {
//        int total = 0;
//        final int FETCH = 512;
//
//        while (true) {
//            // monta WHERE dinamicamente:
//            StringBuilder where = new StringBuilder("""
//                WHERE book_id = ?
//                  AND bt_chrf IS NULL
//                  AND status IN ('suspect','pending')
//            """);
//
//            List<Object> args = new ArrayList<>();
//            args.add(bookId);
//
//            if (onlyLowFinal) {
//                // roda BT só quando final_score é nulo ou <= threshold
//                where.append(" AND (final_score IS NULL OR final_score <= ?) ");
//                args.add(finalThreshold);
//            }
//
//            where.append(" ORDER BY id LIMIT ? ");
//            args.add(limitRows != null ? Math.min(limitRows, FETCH) : FETCH);
//
//            String sql = """
//                SELECT id, src, tgt, lang_src, lang_tgt, final_score, qe_score
//                FROM tm_bookpair_inbox
//            """ + where;
//
//            List<Row> rows = jdbc.query(sql, (rs, rn) -> new Row(
//                    rs.getLong("id"),
//                    rs.getString("src"),
//                    rs.getString("tgt"),
//                    rs.getString("lang_src"),
//                    rs.getString("lang_tgt"),
//                    rs.getObject("final_score") != null ? rs.getDouble("final_score") : null,
//                    rs.getObject("qe_score") != null ? rs.getDouble("qe_score") : null
//            ), args.toArray());
//
//            if (rows.isEmpty()) break;
//
//            // BT (tgt→src)
//            List<String> tgts = new ArrayList<>(rows.size());
//            for (var r : rows) tgts.add(r.tgt);
//           // List<String> bts = bt.backtranslateBatch(tgts, rows.get(0).langTgt, rows.get(0).langSrc);
//
//            // chrF + promoção leve
//            List<Object[]> updates = new ArrayList<>(rows.size());
//            for (int i = 0; i < rows.size(); i++) {
//                var r = rows.get(i);
//                String btText = (i < bts.size() ? bts.get(i) : "");
//                double chrf = Chrf.chrf(r.src, btText); // 0..100
//
//                Double newFinal = r.finalScore != null
//                        ? clamp01(0.8 * r.finalScore + 0.2 * (chrf / 100.0))
//                        : (chrf / 100.0);
//
//                String newStatus = decideStatus(newFinal, r.qeScore, chrf);
//                updates.add(new Object[]{chrf, newFinal, newStatus, r.id});
//            }
//
//            int[] cnt = jdbc.batchUpdate("""
//                        UPDATE tm_bookpair_inbox
//                           SET bt_chrf = ?,
//                               final_score = ?,
//                               status = ?
//                         WHERE id = ?
//                    """, updates);
//
//            int applied = 0;
//            for (int c : cnt) applied += c;
//            total += applied;
//            if (limitRows != null) break; // modo “amostra”
//        }
//
//        log.info("[btcheck] atualizados={} (book_id={})", total, bookId);
//        return total;
//    }
//
//    private String decideStatus(Double finalScore, Double qeScore, double chrf) {
//        double f = finalScore != null ? finalScore : 0.0;
//        double q = qeScore != null ? qeScore : 0.0;
//        boolean strongBt = chrf >= chrfMin;
//        if (f >= goodMin && q >= qeGoodMin && strongBt) return "good";
//        if (f >= suspectMin || strongBt) return "suspect";
//        return "bad";
//    }
//
//    private static double clamp01(double v) {
//        return Math.max(0.0, Math.min(1.0, v));
//    }
//
//    private record Row(Long id, String src, String tgt, String langSrc, String langTgt, Double finalScore,
//                       Double qeScore) {}
//}
