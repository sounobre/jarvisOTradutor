package com.dnobretech.jarvistradutorbackend.dto;

import com.dnobretech.jarvistradutorbackend.enums.BookType;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BookUpdateDTO {
    private Long seriesId;           // opcional
    private String title;
    private String volumeNumber;
    private String originalTitle;
    private String language;
    private String isbn;
    private String publisher;
    private String publishedAt;
    private String originalTitleEn;       // TituloOriginalEN
    private String titlePtBr;             // TituloPTBR
    private BookType type;                // Tipo
    private Integer yearOriginal;         // AnoPublicacaoOriginal
    private Integer yearBr;               // AnoPublicacaoBR
    private String publisherBr;           // EditoraBR
    private String translatorBr;          // TradutorBR
    private String isbn13Br;              // ISBN13_BR
    private Boolean downloaded;           // Baixado
    private String pathEn;                // Caminho versão em Inglês
    private String pathPt;                // Caminho versão em Português
    private Boolean pairsImported;        // Já foi feita a importação dos pares
}
