package com.dnobretech.jarvistradutorbackend.dto;


import java.time.OffsetDateTime;

public record BookpairInboxRow(
        Long id,
        String src,
        String tgt,
        String langSrc,
        String langTgt,
        Double quality,
        Long seriesId,
        Long bookId,
        String chapter,
        String location,
        String sourceTag,
        String status,
        String reviewer,
        OffsetDateTime reviewedAt,
        OffsetDateTime createdAt
) {}
