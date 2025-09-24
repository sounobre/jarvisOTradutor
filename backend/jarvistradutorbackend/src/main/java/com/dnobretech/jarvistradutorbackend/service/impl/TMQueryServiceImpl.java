package com.dnobretech.jarvistradutorbackend.service.impl;


import com.dnobretech.jarvistradutorbackend.client.EmbeddingsClient;
import com.dnobretech.jarvistradutorbackend.repository.TMRepository;
import com.dnobretech.jarvistradutorbackend.service.TMQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TMQueryServiceImpl implements TMQueryService {
    private final JdbcTemplate jdbc;                           // queries custom (trgm/pgvector)
    private final TMRepository tmRepo;                         // salvar TM fallback
    private final EmbeddingsClient embeddings;                 // cliente p/ gerar embedding

    @Value("${jarvis.tm.cosine-threshold:0.86}")
    private double cosineThreshold;                            // limiar de similaridade

    @Override
    public String lookupBest(String src) {
        // 1) candidatos rápidos por trigram (SIMILARITY do pg_trgm) – pega top 50
        List<Map<String, Object>> trigram = jdbc.queryForList("""
      SELECT id, src, tgt
      FROM tm
      WHERE src % ?                        -- operador de similaridade (trgm)
      ORDER BY similarity(src, ?) DESC
      LIMIT 50
    """, src, src);

        // vetor da consulta (um só texto)
        float[] qvec = embeddings.embedOne(src);                 // gera embedding via Worker Python

        // 2) Se não houver candidatos, busca direto em ANN (pgvector) top 20
        if (trigram.isEmpty()) {
            List<Map<String, Object>> ann = jdbc.queryForList("""
        SELECT tm.id, tm.src, tm.tgt,
               1 - (e.src_vec <=> ?::vector) AS cos       -- similaridade cos = 1 - L2 normalized
        FROM tm_embeddings e
        JOIN tm ON tm.id = e.tm_id
        ORDER BY e.src_vec <=> ?::vector                  -- distância L2
        LIMIT 20
      """, qvec, qvec);

            if (!ann.isEmpty()) {
                Map<String, Object> best = ann.get(0);
                double cos = ((Number) best.get("cos")).doubleValue();
                return cos >= cosineThreshold ? (String) best.get("tgt") : null;
            }
            return null;
        }

        // 3) Refiltrar ANN apenas nos IDs candidatos (mais eficiente)
        long[] ids = trigram.stream().mapToLong(r -> ((Number) r.get("id")).longValue()).toArray();
        List<Map<String, Object>> ann = jdbc.queryForList("""
      SELECT tm.id, tm.src, tm.tgt,
             1 - (e.src_vec <=> ?::vector) AS cos
      FROM tm_embeddings e
      JOIN tm ON tm.id = e.tm_id
      WHERE tm.id = ANY (?)
      ORDER BY e.src_vec <=> ?::vector
      LIMIT 20
    """, qvec, ids, qvec);

        if (!ann.isEmpty()) {
            Map<String, Object> best = ann.get(0);
            double cos = ((Number) best.get("cos")).doubleValue();
            return cos >= cosineThreshold ? (String) best.get("tgt") : null;
        }
        return null;
    }

    @Override
    public void learnOnline(String src, String tgt, Double quality) {
        // Insere novo par TM e já grava embedding correspondente (aprendizado online)
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
          INSERT INTO tm(src, tgt, quality, used_count) VALUES (?, ?, ?, 1)
          """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, src);
            ps.setString(2, tgt);
            if (quality == null) ps.setNull(3, java.sql.Types.DOUBLE);
            else ps.setDouble(3, quality);
            return ps;
        }, kh);

        Number key = kh.getKey();
        if (key == null) return;
        long tmId = key.longValue();

        float[] vec = embeddings.embedOne(src);                  // embedding do src
        jdbc.update("""
        INSERT INTO tm_embeddings(tm_id, src_vec) VALUES (?, ?)
      """, ps -> {
            ps.setLong(1, tmId);
            ps.setObject(2, vec);                                  // o driver PG entende vector via Object
        });
    }
}
