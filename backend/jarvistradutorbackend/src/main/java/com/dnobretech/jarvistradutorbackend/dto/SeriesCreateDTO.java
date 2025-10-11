// src/main/java/com/dnobretech/jarvistradutorbackend/dto/SeriesCreateDTO.java
package com.dnobretech.jarvistradutorbackend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SeriesCreateDTO(
        @NotBlank String name,
        String slug,
        @NotNull Long authorId,        // obrigatório via @NotNull se preferir
        String description
) {}
