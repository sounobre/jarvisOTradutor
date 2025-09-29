package com.dnobretech.jarvistradutorbackend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BookCreateDTO {
    private Long seriesId;           // opcional
    @NotBlank
    private String title;
    private Integer volumeNumber;
    private String originalTitle;
    private String language;
    private String isbn;
    private String publisher;
    private String publishedAt;
}
