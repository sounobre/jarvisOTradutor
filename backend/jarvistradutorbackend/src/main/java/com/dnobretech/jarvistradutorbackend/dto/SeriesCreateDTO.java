package com.dnobretech.jarvistradutorbackend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SeriesCreateDTO {
    @NotBlank
    private String name;
    @NotBlank
    private String slug;
    private String description;
}
