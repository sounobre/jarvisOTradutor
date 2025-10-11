package com.dnobretech.jarvistradutorbackend.dto;

import java.time.Instant;

public record BookSummaryDTO(
        Long id,
        Long seriesId,
        String seriesName,
        String volumeNumber,
        String originalTitleEn,
        String titlePtBr,
        String type,              // usar name() do enum BookType
        Integer yearOriginal,
        Integer yearBr,
        String publisherBr,
        String translatorBr,
        String isbn13Br,
        Boolean downloaded,
        String pathEn,
        String pathPt,
        Boolean pairsImported,
        Instant createdAt,
        Instant updatedAt
) {}