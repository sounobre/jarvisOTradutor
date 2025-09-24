package com.dnobretech.jarvistradutorbackend.controller;

import com.dnobretech.jarvistradutorbackend.service.TMImportService;
import com.dnobretech.jarvistradutorbackend.service.TMQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/tm")
public class TMController {

    private final TMImportService importService;
    private final TMQueryService tmService;

    // Upload (multipart) OU Resumível (path+fileKey)
    @PostMapping("/import")
    public ResponseEntity<?> importFile(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(defaultValue = "\t") String delimiter,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String fileKey,
            @RequestParam(defaultValue = "100000") int batchLines,
            @RequestParam(defaultValue = "0") int examples,                 // <- NOVO
            @RequestParam(defaultValue = "none") String embed                // <- NOVO: none|src|both
    ) throws Exception {

        if (path != null && fileKey != null) {
            var res = importService.importTxtResume(path, delimiter, fileKey, batchLines, examples, embed);
            return ResponseEntity.ok(java.util.Map.of(
                    "ok", true, "mode", "resume",
                    "processedLines", res.processedLines(),
                    "newOffset", res.newOffset(),
                    "totalCopied", res.totalCopied(),
                    "examples", res.examples()
            ));
        }

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "ok", false,
                    "error", "Envie 'file' (multipart) OU use 'path' + 'fileKey' para modo resumível."
            ));
        }

        long rows = importService.importTsvOrCsvStreaming(file, delimiter);
        return ResponseEntity.ok(java.util.Map.of("ok", true, "mode", "upload", "rows", rows));
    }

    // Acompanhar checkpoint
    @GetMapping("/import/checkpoint")
    public ResponseEntity<?> getCheckpoint(@RequestParam String fileKey) {
        var ck = importService.getCheckpoint(fileKey);
        if (ck == null) return ResponseEntity.ok(java.util.Map.of("ok", true, "exists", false));
        return ResponseEntity.ok(java.util.Map.of(
                "ok", true, "exists", true,
                "fileKey", ck.fileKey(), "path", ck.path(),
                "byteOffset", ck.byteOffset(), "lineCount", ck.lineCount(),
                "fileSize", ck.fileSize()
        ));
    }

    // Resetar checkpoint
    @DeleteMapping("/import/checkpoint")
    public ResponseEntity<?> resetCheckpoint(@RequestParam String fileKey) {
        importService.resetCheckpoint(fileKey);
        return ResponseEntity.ok(java.util.Map.of("ok", true, "reset", fileKey));
    }

    // Lookup/learn (inalterados)
    @GetMapping("/lookup")
    public ResponseEntity<?> lookup(@RequestParam String src) {
        String tgt = tmService.lookupBest(src);
        return ResponseEntity.ok(java.util.Map.of("src", src, "tgt", tgt));
    }

    @PostMapping("/learn")
    public ResponseEntity<?> learn(@RequestParam String src,
                                   @RequestParam String tgt,
                                   @RequestParam(required = false) Double quality) {
        tmService.learnOnline(src, tgt, quality);
        return ResponseEntity.ok(java.util.Map.of("ok", true));
    }
}
