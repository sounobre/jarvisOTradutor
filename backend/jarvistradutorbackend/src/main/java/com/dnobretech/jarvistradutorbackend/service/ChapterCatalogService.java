package com.dnobretech.jarvistradutorbackend.service;

import com.dnobretech.jarvistradutorbackend.dto.Block;
import java.util.List;

public interface ChapterCatalogService {
    void upsertChapters(Long bookId, String lang, List<Block> blocks);
}