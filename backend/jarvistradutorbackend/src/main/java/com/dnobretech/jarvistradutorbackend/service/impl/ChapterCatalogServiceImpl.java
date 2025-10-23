// com.dnobretech.jarvistradutorbackend.service.impl.ChapterCatalogServiceImpl.java
package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.dto.Block;
import com.dnobretech.jarvistradutorbackend.service.ChapterCatalogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterCatalogServiceImpl implements ChapterCatalogService {

    private final JdbcTemplate jdbc;

    @Override
    public void upsertChapters(Long bookId, String lang, List<Block> blocks) {
        if (bookId == null || blocks == null || blocks.isEmpty()) return;

        // Agrupa por spineIdx (um "capítulo" por recurso do spine)
        // e mantém título/totais
        Map<Integer, ChapterAgg> agg = new LinkedHashMap<>();
        for (Block b : blocks) {
            int spine = b.spineIdx();
            ChapterAgg a = agg.computeIfAbsent(spine, k -> new ChapterAgg(spine, safeTitle(b.chapterTitle())));
            a.blockCount += 1;
            a.sentCount  += Math.max(1, b.sentIdx() >= 0 ? 1 : 1); // se veio sentence-level cada Block é uma sent; se paragraph-level, ~1
            a.charCount  += (b.text() != null ? b.text().length() : 0);
            if (a.blockFirstIdx == null || b.blockIdx() < a.blockFirstIdx) a.blockFirstIdx = b.blockIdx();
            if (a.blockLastIdx  == null || b.blockIdx() > a.blockLastIdx ) a.blockLastIdx  = b.blockIdx();
        }

        // UPSERT em lote
        List<Object[]> params = new ArrayList<>(agg.size());
        for (ChapterAgg a : agg.values()) {
            params.add(new Object[]{
                    bookId, lang, a.spineIdx, normKey(a.chapterTitle), a.chapterTitle,
                    a.blockFirstIdx, a.blockLastIdx, a.blockCount, a.sentCount, a.charCount
            });
        }

        // Postgres 15+: MERGE seria elegante; ON CONFLICT funciona bem aqui
        int[] res = jdbc.batchUpdate("""
            INSERT INTO book_chapter(
              book_id, lang, spine_idx, chapter_key, chapter_title,
              block_first_idx, block_last_idx, block_count, sent_count, char_count, created_at, updated_at
            ) VALUES (
              ?, ?, ?, ?, ?,
              ?, ?, ?, ?, ?, now(), now()
            )
            ON CONFLICT (book_id, lang, spine_idx)
            DO UPDATE SET
              chapter_key     = EXCLUDED.chapter_key,
              chapter_title   = EXCLUDED.chapter_title,
              block_first_idx = LEAST(COALESCE(book_chapter.block_first_idx, EXCLUDED.block_first_idx), EXCLUDED.block_first_idx),
              block_last_idx  = GREATEST(COALESCE(book_chapter.block_last_idx,  EXCLUDED.block_last_idx),  EXCLUDED.block_last_idx),
              block_count     = EXCLUDED.block_count,
              sent_count      = EXCLUDED.sent_count,
              char_count      = EXCLUDED.char_count,
              updated_at      = now()
        """, params);

        int n = 0; for (int x : res) n += x;
        log.info("[chapters] upsertados={} (book_id={}, lang={}, grupos={})",
                n, bookId, lang, agg.size());
    }

    private static String normKey(String s) {
        if (s == null) return null;
        String t = Normalizer.normalize(s, Normalizer.Form.NFKC).trim().toLowerCase();
        t = t.replaceAll("\\s+", " ");
        return t.isEmpty()? null : t;
    }

    private static String safeTitle(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.isEmpty()? null : t;
    }

    private static class ChapterAgg {
        final int spineIdx;
        final String chapterTitle;
        Integer blockFirstIdx;
        Integer blockLastIdx;
        int blockCount = 0;
        int sentCount = 0;
        int charCount = 0;
        ChapterAgg(int spineIdx, String title) {
            this.spineIdx = spineIdx;
            this.chapterTitle = title;
        }
    }
}
