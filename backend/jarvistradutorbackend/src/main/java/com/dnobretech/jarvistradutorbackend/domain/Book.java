package com.dnobretech.jarvistradutorbackend.domain;

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

    @Column(nullable = false)
    private String title;

    /** ex.: número do volume dentro da série */
    private Integer volumeNumber;

    private String originalTitle;
    private String language;      // ex.: "en", "pt"
    private String isbn;
    private String publisher;
    private String publishedAt;   // string livre ou LocalDate, como preferir

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
