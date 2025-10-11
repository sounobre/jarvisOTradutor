// src/main/java/com/dnobretech/jarvistradutorbackend/service/impl/GlossarySearchServiceImpl.java
package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.dto.EmbedResponse;
import com.dnobretech.jarvistradutorbackend.dto.GlossarySearchItem;
import com.dnobretech.jarvistradutorbackend.service.GlossarySearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GlossarySearchServiceImpl implements GlossarySearchService {

    private final JdbcTemplate jdbc;

    private final WebClient embClient = WebClient.builder()
            .baseUrl("http://localhost:8001")
            .exchangeStrategies(ExchangeStrategies.builder()
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024)).build())
            .build();

    @Override
    public List<GlossarySearchItem> search(String q, Long seriesId, int k, double wvec, double wtxt) {
        if (q == null || q.isBlank()) return List.of();
        // 1) embed da query
        var resp = embClient.post().uri("/embed")
                .bodyValue(Map.of("texts", List.of(q), "normalize", true))
                .retrieve()
                .bodyToMono(EmbedResponse.class)
                .block();
        double[] vec = (resp != null && resp.vectors()!=null && !resp.vectors().isEmpty())
                ? resp.vectors().get(0) : null;
        if (vec == null) return List.of();

        // 2) vetor → literal pgvector
        String qvec = toVectorLiteral(vec);

        // 3) SQL híbrido (usa pg_trgm + pgvector)
        String sql = """
            WITH params AS (
              SELECT CAST(? AS vector) AS qv, CAST(? AS text) AS qtxt,
                     CAST(? AS bigint) AS sid, CAST(? AS double precision) AS wv,
                     CAST(? AS double precision) AS wt, CAST(? AS int) AS topk
            )
            SELECT g.id,
                   g.series_id,
                   g.src,
                   g.tgt,
                   (wv * (1 - (e.emb_src <=> p.qv)) + wt * similarity(g.src, p.qtxt)) AS score,
                   (1 - (e.emb_src <=> p.qv)) AS sim_vec,
                   similarity(g.src, p.qtxt)  AS sim_txt
            FROM params p
            JOIN glossary g     ON g.approved = true
            JOIN glossary_embeddings e ON e.glossary_id = g.id
            WHERE (p.sid IS NULL OR g.series_id = p.sid)
            ORDER BY score DESC
            LIMIT (SELECT topk FROM params)
        """;

        return jdbc.query(
                sql,
                ps -> {
                    ps.setString(1, qvec);
                    ps.setString(2, q);
                    if (seriesId == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, seriesId);
                    ps.setDouble(4, wvec);
                    ps.setDouble(5, wtxt);
                    ps.setInt(6, k);
                },
                (rs, i) -> new GlossarySearchItem(
                        rs.getLong("id"),
                        (Long) rs.getObject("series_id"),
                        rs.getString("src"),
                        rs.getString("tgt"),
                        rs.getDouble("score"),
                        (Double) rs.getObject("sim_vec"),
                        (Double) rs.getObject("sim_txt")
                )
        );
    }

    private static String toVectorLiteral(double[] v) {
        StringBuilder sb = new StringBuilder(v.length * 6 + 2);
        sb.append('[');
        for (int i=0;i<v.length;i++){
            if (i>0) sb.append(',');
            sb.append(Double.toString(v[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}
