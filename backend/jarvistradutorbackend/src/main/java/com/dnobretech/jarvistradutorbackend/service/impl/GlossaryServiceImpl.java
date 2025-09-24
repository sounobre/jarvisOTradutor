package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.domain.Glossary;
import com.dnobretech.jarvistradutorbackend.repository.GlossaryRepository;
import com.dnobretech.jarvistradutorbackend.service.GlossaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GlossaryServiceImpl implements GlossaryService {
    private final GlossaryRepository repo;                // JPA para CRUD simples
    private final JdbcTemplate jdbc;                      // para upsert em lote

    @Override
    public int bulkUpsert(List<Glossary> items) {
        // Chamando o overload com batchSize → o retorno é int[][] (um int[] por lote)
        int[][] counts = jdbc.batchUpdate("""
      INSERT INTO glossary(src, tgt, note, approved, priority)
      VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (src) DO UPDATE
      SET tgt = EXCLUDED.tgt,
          note = EXCLUDED.note,
          approved = EXCLUDED.approved,
          priority = EXCLUDED.priority
      """,
                items,
                500, // batch size
                (ps, g) -> {
                    ps.setString(1, g.getSrc());                                            // src
                    ps.setString(2, g.getTgt());                                            // tgt
                    ps.setString(3, g.getNote());                                           // note
                    ps.setBoolean(4, Boolean.TRUE.equals(g.getApproved()));                 // approved (null -> false)
                    ps.setInt(5, g.getPriority() == null ? 0 : g.getPriority());            // priority
                }
        );

        // 'counts' é int[][]; cada linha do primeiro nível representa um lote (batch),
        // e cada int dentro do segundo nível representa o resultado de 1 statement daquele lote.
        // Precisamos somar tudo (flatten + sum).
        int total =
                java.util.Arrays.stream(counts)                  // Stream<int[]> (cada lote)
                        .mapToInt(batch ->                          // para cada lote (int[])
                                java.util.stream.IntStream.of(batch)    // IntStream dos resultados do lote
                                        .sum()                               // soma do lote
                        )
                        .sum();                                      // soma de todos os lotes

        return total; // total de linhas afetadas na operação inteira
    }
}
