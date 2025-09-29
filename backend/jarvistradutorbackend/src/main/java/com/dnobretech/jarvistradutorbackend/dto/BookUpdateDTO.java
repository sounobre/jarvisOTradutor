package com.dnobretech.jarvistradutorbackend.dto;

import lombok.Data;

@Data
public class BookUpdateDTO {
    private Long seriesId;           // pode trocar vínculo
    private String title;
    private Integer volumeNumber;
    private String originalTitle;
    private String language;
    private String isbn;
    private String publisher;
    private String publishedAt;
}
