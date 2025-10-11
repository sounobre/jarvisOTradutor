// src/main/java/com/dnobretech/jarvistradutorbackend/dto/AuthorUpdateDTO.java
package com.dnobretech.jarvistradutorbackend.dto;

public record AuthorUpdateDTO(
        String name,
        String slug,
        String country,
        String activePeriod,
        Integer birthYear,
        Integer deathYear,
        String notes
) {}
