// src/main/java/com/dnobretech/jarvistradutorbackend/dto/SeriesDTO.java
package com.dnobretech.jarvistradutorbackend.dto;

import java.time.Instant;

public record SeriesDTO(
        Long id,
        String name,
        String slug,
        Long authorId,
        String authorName,
        String description,
        Instant createdAt,
        Instant updatedAt
) {}
