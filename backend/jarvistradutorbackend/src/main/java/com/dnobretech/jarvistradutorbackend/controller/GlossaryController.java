package com.dnobretech.jarvistradutorbackend.controller;


import com.dnobretech.jarvistradutorbackend.domain.Glossary;
import com.dnobretech.jarvistradutorbackend.service.GlossaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/glossary")
public class GlossaryController {
    private final GlossaryService service;                    // injeta service

    @PostMapping("/bulk")
    public ResponseEntity<?> bulk(@RequestBody List<Glossary> items) {
        int affected = service.bulkUpsert(items);               // upsert em lote
        return ResponseEntity.ok(java.util.Map.of("ok", true, "affected", affected));
    }
}
