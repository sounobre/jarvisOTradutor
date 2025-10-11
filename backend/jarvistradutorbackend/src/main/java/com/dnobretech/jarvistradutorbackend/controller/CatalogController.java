package com.dnobretech.jarvistradutorbackend.controller;

import com.dnobretech.jarvistradutorbackend.dto.CatalogImportResult;
import com.dnobretech.jarvistradutorbackend.service.CatalogExcelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/catalog")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogExcelService excelService;

    @PostMapping(value="/import-excel", consumes = "multipart/form-data")
    public CatalogImportResult importExcel(@RequestPart("file") MultipartFile file) throws IOException {
        return excelService.importExcel(file);
    }

    @GetMapping(value="/export-excel")
    public ResponseEntity<byte[]> exportExcel() throws IOException {
        byte[] bytes = excelService.exportExcel();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"catalogo.jarvis.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}

