package com.dnobretech.jarvistradutorbackend.controller;

import com.dnobretech.jarvistradutorbackend.service.TMXImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/tm")
public class TMXController {

    private final TMXImportService tmxImportService;

    /**
     * Importa um arquivo TMX (ex.: ParaCrawl) para TM (Postgres) via COPY.
     *
     * @param file    arquivo .tmx (multipart)
     * @param srcLang idioma origem (default: en)
     * @param tgtLang idioma destino (default: pt)
     */
    @PostMapping(value = "/import/tmx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importTmx(@RequestPart("file") MultipartFile file,
                                       @RequestParam(defaultValue = "en") String srcLang,
                                       @RequestParam(defaultValue = "pt") String tgtLang) throws Exception {
        long rows = tmxImportService.importTmx(file, srcLang, tgtLang);
        return ResponseEntity.ok(java.util.Map.of(
                "ok", true,
                "rows", rows,
                "srcLang", srcLang,
                "tgtLang", tgtLang
        ));
    }
}

