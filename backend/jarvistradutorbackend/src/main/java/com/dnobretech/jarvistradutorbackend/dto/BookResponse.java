package com.dnobretech.jarvistradutorbackend.dto;

public record BookResponse(
        Long id,
        String volumeNumber,
        String originalTitleEn,
        String titlePtBr,
        String type,
        Integer yearOriginal,
        Integer yearBr,
        String publisherBr,
        String translatorBr,
        String isbn13Br,
        Boolean downloaded,
        Boolean pairsImported,
        SeriesSummaryDTO series
) {}
