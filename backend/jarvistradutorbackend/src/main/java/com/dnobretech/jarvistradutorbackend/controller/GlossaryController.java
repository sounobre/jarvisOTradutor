package com.dnobretech.jarvistradutorbackend.controller;

import com.dnobretech.jarvistradutorbackend.domain.Glossary;
import com.dnobretech.jarvistradutorbackend.dto.GlossaryBulkResult;
import com.dnobretech.jarvistradutorbackend.service.GlossaryService;
import com.dnobretech.jarvistradutorbackend.service.SeriesService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/glossary")
@RequiredArgsConstructor
public class GlossaryController {

    private final GlossaryService glossaryService;
    private final SeriesService seriesService;

    // GlossaryController.java (adapte se precisar)
    @GetMapping("/ping") public String ping(){ return "ok"; } // opcional

    @PostMapping("/bulk")
    public GlossaryBulkResult bulkUpsert(
            @RequestParam(required = false) Long seriesId,
            @RequestBody @Valid List<Glossary> items
    ) {
        // valida a série do query param
        if (seriesId != null && Objects.isNull(seriesService.get(seriesId))) {
            throw new jakarta.persistence.EntityNotFoundException("series id=" + seriesId + " não encontrada");
        }
        // valida as séries informadas item a item (se vierem no corpo)
        var seriesIds = items.stream()
                .map(Glossary::getSeriesId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        for (Long sid : seriesIds) {
            if (sid == null || seriesService.get(sid) == null) { // <-- agora usa sid
                throw new jakarta.persistence.EntityNotFoundException("series id=" + sid + " não encontrada");
            }
        }

        int affected = glossaryService.bulkUpsertWithSeries(items, seriesId);
        return new GlossaryBulkResult(affected);
    }

}
