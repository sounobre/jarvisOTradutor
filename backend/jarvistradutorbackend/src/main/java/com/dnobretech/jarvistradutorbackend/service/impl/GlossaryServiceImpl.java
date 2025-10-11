package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.domain.Glossary;
import com.dnobretech.jarvistradutorbackend.dto.EmbedResponse;
import com.dnobretech.jarvistradutorbackend.repository.GlossaryRepository;
import com.dnobretech.jarvistradutorbackend.service.GlossaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.copy.CopyManager;
import org.postgresql.copy.PGCopyOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.postgresql.copy.PGCopyOutputStream;

import javax.sql.DataSource;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GlossaryServiceImpl implements GlossaryService {

    private final GlossaryRepository repo;   // jpa (se necessário em outros pontos)
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    /** none | src | both (default: src) */
    @Value("${jarvis.glossary.embed-mode:src}")
    private String embedMode;

    /** tamanho do lote para o embedder */
    @Value("${jarvis.embeddings.batch-size:256}")
    private int EMB_BATCH;

    /** dimensão do vetor do seu modelo (MiniLM-L12-v2 = 384) */
    @Value("${jarvis.embeddings.dimension:384}")
    private int VECTOR_DIM;

    private final WebClient embClient = WebClient.builder()
            .baseUrl("http://localhost:8001")
            .exchangeStrategies(ExchangeStrategies.builder()
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
                    .build())
            .build();

    // ======================= API PÚBLICA =======================

    @Override
    public int bulkUpsert(List<Glossary> items) {
        return bulkUpsertWithSeries(items, null);
    }

    @Override
    public int bulkUpsertWithSeries(List<Glossary> items, Long seriesId) {
        if (items == null || items.isEmpty()) return 0;

        // 1) UPSERT léxico em glossary (por src + series_id)
        int[][] counts = jdbc.batchUpdate("""
            INSERT INTO glossary(src, tgt, note, approved, priority, series_id, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (src, series_id) DO UPDATE
            SET tgt = EXCLUDED.tgt,
                note = EXCLUDED.note,
                approved = EXCLUDED.approved,
                priority = EXCLUDED.priority,
                updated_at = now()
        """, items, 500, (ps, g) -> {
            ps.setString(1, nvl(g.getSrc()));
            ps.setString(2, nvl(g.getTgt()));
            ps.setString(3, g.getNote());
            ps.setBoolean(4, Boolean.TRUE.equals(g.getApproved()));
            ps.setInt(5, g.getPriority() == null ? 0 : g.getPriority());
            Long sid = g.getSeriesId() != null ? g.getSeriesId() : seriesId;
            if (sid == null) ps.setNull(6, java.sql.Types.BIGINT);
            else ps.setLong(6, sid);
        });

        int total = Arrays.stream(counts).mapToInt(batch -> Arrays.stream(batch).sum()).sum();

        // 2) embeddings (opcional)
        String mode = (embedMode == null) ? "src" : embedMode.toLowerCase(Locale.ROOT);
        if (!"none".equals(mode)) {
            try {
                ensureEmbeddingsSchema();
                embedAndStage(items, seriesId, mode); // escreve staging via COPY
                int merged = consolidateFromStaging();
                log.info("[glossary] embeddings consolidados: {}", merged);
            } catch (Exception e) {
                log.error("[glossary] falha ao gerar/consolidar embeddings: {}", e.toString(), e);
            }
        }

        return total;
    }

    // ======================= EMBEDDINGS PIPELINE =======================

    /** Cria/ajusta tabelas de embedding para a dimensão configurada (VECTOR_DIM). */
    private void ensureEmbeddingsSchema() {
        // glossary_embeddings com vector(VECTOR_DIM)
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS glossary_embeddings (
              glossary_id BIGINT PRIMARY KEY REFERENCES glossary(id) ON DELETE CASCADE,
              emb_src     vector,
              emb_tgt     vector,
              updated_at  timestamp default now()
            )
        """);

        // garantir dimensão correta (ALTER TYPE)
        jdbc.execute("ALTER TABLE glossary_embeddings " +
                "ALTER COLUMN emb_src TYPE vector(" + VECTOR_DIM + ") USING emb_src");
        jdbc.execute("ALTER TABLE glossary_embeddings " +
                "ALTER COLUMN emb_tgt TYPE vector(" + VECTOR_DIM + ") USING emb_tgt");

        // staging com series_id e dimensão fixa
        jdbc.execute("DROP TABLE IF EXISTS glossary_emb_staging");
        jdbc.execute("CREATE TABLE glossary_emb_staging (" +
                "src text NOT NULL, " +
                "series_id bigint, " +
                "emb_src vector(" + VECTOR_DIM + "), " +
                "emb_tgt vector(" + VECTOR_DIM + "), " +
                "created_at timestamp default now()" +
                ")");

        // índice ANN em emb_src (se ainda não existir)
        jdbc.execute("""
            DO $$
            BEGIN
              IF NOT EXISTS (
                SELECT 1 FROM pg_class c
                JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE c.relname='gx_emb_src_ivfflat' AND n.nspname=current_schema()
              ) THEN
                CREATE INDEX gx_emb_src_ivfflat
                  ON glossary_embeddings USING ivfflat (emb_src vector_cosine_ops) WITH (lists=100);
              END IF;
            END$$
        """);
    }

    /** Deduplica por (src, series_id), chama embedder em lotes e streama para staging via COPY. */
    private void embedAndStage(List<Glossary> items, Long defaultSeriesId, String mode) throws Exception {
        record Key(String src, Long sid) {}
        Map<Key, Glossary> map = items.stream()
                .filter(g -> g.getSrc() != null && !g.getSrc().isBlank())
                .collect(Collectors.toMap(
                        g -> new Key(g.getSrc().trim(), g.getSeriesId() != null ? g.getSeriesId() : defaultSeriesId),
                        g -> g,
                        (a, b) -> b,
                        LinkedHashMap::new
                ));
        if (map.isEmpty()) return;

        Connection con = DataSourceUtils.getConnection(dataSource);
        try {
            // ↓↓↓ EM VEZ de pegar CopyManager e passar pro construtor, pegue o PGConnection:
            PGConnection pgConn = con.unwrap(PGConnection.class);

            // Opção A (mais simples): usa o construtor PGCopyOutputStream(PGConnection, String)
            PGCopyOutputStream pgOut = new PGCopyOutputStream(
                    pgConn,
                    "COPY glossary_emb_staging (src, series_id, emb_src, emb_tgt) " +
                            "FROM STDIN WITH (FORMAT csv, HEADER true)"
            );

            try (Writer w = new OutputStreamWriter(pgOut, StandardCharsets.UTF_8)) {
                w.write("src,series_id,emb_src,emb_tgt\n");

                List<String> batchSrc = new ArrayList<>(EMB_BATCH);
                List<String> batchTgt = new ArrayList<>(EMB_BATCH);
                List<Long>   batchSid = new ArrayList<>(EMB_BATCH);

                int written = 0;
                for (var e : map.entrySet()) {
                    batchSrc.add(e.getKey().src());
                    batchSid.add(e.getKey().sid());
                    if ("both".equals(mode)) batchTgt.add(nvl(e.getValue().getTgt()));

                    if (batchSrc.size() >= EMB_BATCH) {
                        written += writeBatch(w, batchSrc, batchTgt, batchSid, mode);
                        batchSrc.clear(); batchTgt.clear(); batchSid.clear();
                    }
                }
                if (!batchSrc.isEmpty()) {
                    written += writeBatch(w, batchSrc, batchTgt, batchSid, mode);
                }

                w.flush();
                log.info("[glossary] staging wrote {} rows", written);
            } finally {
                // garante endCopy() mesmo em erro
                pgOut.close();
            }

        } finally {
            DataSourceUtils.releaseConnection(con, dataSource);
        }
    }

    /** Embeda o batch atual e escreve linhas CSV no writer. */
    private int writeBatch(Writer w, List<String> srcs, List<String> tgts, List<Long> sids, String mode) throws Exception {
        List<double[]> vSrc = embedTexts(srcs, true);
        List<double[]> vTgt = "both".equals(mode) ? embedTexts(tgts, true) : List.of();

        for (int i = 0; i < srcs.size(); i++) {
            String embSrc = vSrc.isEmpty() ? "" : toVectorLiteral(vSrc.get(i));
            String embTgt = (!vTgt.isEmpty()) ? toVectorLiteral(vTgt.get(i)) : "";
            Long sid = sids.get(i);

            // "src","series_id","[vec]","[vec]"
            w.write('"'); w.write(esc(srcs.get(i))); w.write('"'); w.write(',');
            if (sid == null) w.write(""); else w.write(Long.toString(sid));
            w.write(',');
            if (!embSrc.isEmpty()) { w.write('"'); w.write(embSrc); w.write('"'); }
            w.write(',');
            if (!embTgt.isEmpty()) { w.write('"'); w.write(embTgt); w.write('"'); }
            w.write('\n');
        }
        return srcs.size();
    }

    /** Consolida staging → glossary_embeddings por join (src, series_id) → glossary.id. */
    private int consolidateFromStaging() {
        String sql = """
            WITH dedup AS (
              SELECT DISTINCT ON (src, series_id)
                     src, series_id, emb_src, emb_tgt, created_at
              FROM glossary_emb_staging
              ORDER BY src, series_id, created_at DESC
            )
            INSERT INTO glossary_embeddings (glossary_id, emb_src, emb_tgt, updated_at)
            SELECT g.id, d.emb_src, d.emb_tgt, now()
              FROM dedup d
              JOIN glossary g
                ON g.src = d.src
               AND ( (g.series_id IS NULL AND d.series_id IS NULL) OR g.series_id = d.series_id )
            ON CONFLICT (glossary_id) DO UPDATE
              SET emb_src = EXCLUDED.emb_src,
                  emb_tgt = COALESCE(EXCLUDED.emb_tgt, glossary_embeddings.emb_tgt),
                  updated_at = now()
        """;
        int up = jdbc.update(sql);
        jdbc.execute("TRUNCATE glossary_emb_staging");
        return up;
    }

    // ======================= EMBED CLIENT =======================

    private List<double[]> embedTexts(List<String> texts, boolean normalize) {
        if (texts == null || texts.isEmpty()) return List.of();
        Map<String, Object> payload = Map.of("texts", texts, "normalize", normalize);
        EmbedResponse resp = embClient.post()
                .uri("/embed")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(EmbedResponse.class)
                .block();
        return (resp != null && resp.vectors() != null) ? resp.vectors() : List.of();
    }

    // ======================= UTILS =======================

    private static String toVectorLiteral(double[] v) {
        StringBuilder sb = new StringBuilder(v.length * 6 + 2);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Double.toString(v[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String esc(String s) { return s == null ? "" : s.replace("\"","\"\""); }
    private static String nvl(String s) { return s == null ? "" : s; }
}
