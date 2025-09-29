package com.dnobretech.jarvistradutorbackend.controller;

import com.dnobretech.jarvistradutorbackend.dto.Result;
import com.dnobretech.jarvistradutorbackend.service.EPUBPairImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/tm")
public class EPUBPairImportController {

    private final EPUBPairImportService service;

    /**
     * Importa dois EPUBs paralelos (EN↔PT) e popula a TM.
     *
     * @param fileEn  EPUB em inglês (src)
     * @param filePt  EPUB em português (tgt)
     * @param level   "paragraph" (default) ou "sentence"
     * @param mode    "length" (rápido, default) ou "embedding" (mais preciso)
     * @param srcLang default "en"
     * @param tgtLang default "pt"
     * @param minQuality descarta pares com quality abaixo (default 0.55)
     */
    @PostMapping(value = "/import/epub-pair", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importPair(
            @RequestPart("file_en") MultipartFile fileEn,
            @RequestPart("file_pt") MultipartFile filePt,
            @RequestParam(defaultValue = "paragraph") String level,
            @RequestParam(defaultValue = "length") String mode,
            @RequestParam(defaultValue = "en") String srcLang,
            @RequestParam(defaultValue = "pt") String tgtLang,
            @RequestParam(defaultValue = "0.55") double minQuality,
            @RequestParam(required = false) Long seriesId,
            @RequestParam(required = false) Long bookId,
            @RequestParam(required = false) String sourceTag
    ) throws Exception {
        Result r = service.importParallelEPUB(
                fileEn, filePt, level, mode, srcLang, tgtLang, minQuality, seriesId, bookId, sourceTag
        );
        return ResponseEntity.ok(java.util.Map.of(
                "ok", true,
                "pairsInserted", r.inserted(),
                "pairsSkipped", r.skipped(),
                "avgQuality", r.avgQuality(),
                "chapters", r.chapters(),
                "examples",      r.examples()   // <- add
        ));
    }
}

