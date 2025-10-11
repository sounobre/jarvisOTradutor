// src/main/java/com/dnobretech/jarvistradutorbackend/domain/Author.java
package com.dnobretech.jarvistradutorbackend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "author",
        indexes = {
                @Index(name = "ix_author_name", columnList = "name"),
                @Index(name = "ix_author_country", columnList = "country")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_author_slug", columnNames = {"slug"})
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class Author {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;            // Nome do autor

    @Column(nullable = false)
    private String slug;            // identificador único (url-friendly)

    private String country;         // País do autor (ISO-2/ISO-3 ou texto livre)
    private String activePeriod;    // "1997–presente", etc.

    private Integer birthYear;      // opcional
    private Integer deathYear;      // opcional

    @Column(columnDefinition = "text")
    private String notes;           // observações

    private Instant createdAt;
    private Instant updatedAt;
}
