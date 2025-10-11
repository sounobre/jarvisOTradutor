// src/main/java/com/dnobretech/jarvistradutorbackend/dto/SeriesUpdateDTO.java
package com.dnobretech.jarvistradutorbackend.dto;

public record SeriesUpdateDTO(
        String name,
        String slug,
        Long authorId,
        String description
) {}
