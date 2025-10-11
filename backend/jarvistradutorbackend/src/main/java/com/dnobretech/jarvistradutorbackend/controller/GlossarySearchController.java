// src/main/java/com/dnobretech/jarvistradutorbackend/controller/GlossarySearchController.java
package com.dnobretech.jarvistradutorbackend.controller;

import com.dnobretech.jarvistradutorbackend.dto.GlossarySearchItem;
import com.dnobretech.jarvistradutorbackend.service.GlossarySearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/glossary")
@RequiredArgsConstructor
public class GlossarySearchController {

    private final GlossarySearchService service;

    @GetMapping("/search")
    public List<GlossarySearchItem> search(
            @RequestParam String q,
            @RequestParam(required = false) Long seriesId,
            @RequestParam(defaultValue = "20") int k,
            @RequestParam(defaultValue = "0.6") double wvec,
            @RequestParam(defaultValue = "0.4") double wtxt
    ) {
        return service.search(q, seriesId, k, wvec, wtxt);
    }
}
