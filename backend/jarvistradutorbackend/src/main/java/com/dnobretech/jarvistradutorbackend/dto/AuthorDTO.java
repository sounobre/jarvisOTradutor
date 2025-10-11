// src/main/java/com/dnobretech/jarvistradutorbackend/dto/AuthorDTO.java
package com.dnobretech.jarvistradutorbackend.dto;

import java.time.Instant;

public record AuthorDTO(
        Long id,
        String name,
        String slug,
        String country,
        String activePeriod,
        Integer birthYear,
        Integer deathYear,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {}
