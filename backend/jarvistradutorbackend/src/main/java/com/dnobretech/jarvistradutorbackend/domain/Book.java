package com.dnobretech.jarvistradutorbackend.domain;

import com.dnobretech.jarvistradutorbackend.enums.BookType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "book", indexes = {
        @Index(name = "idx_book_series_id", columnList = "series_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Book {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** vínculo opcional com série */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "series_id", foreignKey = @ForeignKey(name = "fk_book_series"))
    private Series series;

    @Column(nullable = true)
    private String title;

    /** ex.: número do volume dentro da série */
    private String volumeNumber;

    private String originalTitle;
    private String language;      // ex.: "en", "pt"
    private String isbn;
    private String publisher;
    private String publishedAt;   // string livre ou LocalDate, como preferir

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = true)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }



    private String originalTitleEn;        // TituloOriginalEN
    private String titlePtBr;              // TituloPTBR
    @Enumerated(EnumType.STRING)
    private BookType type;                 // Tipo
    private Integer yearOriginal;          // AnoPublicacaoOriginal
    private Integer yearBr;                // AnoPublicacaoBR
    private String publisherBr;            // EditoraBR
    private String translatorBr;           // TradutorBR
    private String isbn13Br;               // ISBN13_BR (chave natural preferida)
    private Boolean downloaded;            // Baixado
    private String pathEn;                 // Caminho versão em Inglês
    private String pathPt;                 // Caminho versão em Português (assumido)
    private Boolean pairsImported;         // Já foi feita a importação dos pares
}
