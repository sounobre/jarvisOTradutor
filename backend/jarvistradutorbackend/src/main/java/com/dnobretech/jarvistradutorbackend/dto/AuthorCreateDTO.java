// src/main/java/com/dnobretech/jarvistradutorbackend/dto/AuthorCreateDTO.java
package com.dnobretech.jarvistradutorbackend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthorCreateDTO(
        @NotBlank @Size(max = 255) String name,
        String slug,                 // se null, será gerado a partir do name
        String country,
        String activePeriod,
        Integer birthYear,
        Integer deathYear,
        String notes
) {}
