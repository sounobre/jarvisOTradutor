package com.dnobretech.jarvistradutorbackend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "series", uniqueConstraints = {
        @UniqueConstraint(name = "uk_series_slug", columnNames = "slug")
})
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Series {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** um identificador estável/URL-friendly (opcional, mas útil) */
    @Column(nullable = false)
    private String slug;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() { this.updatedAt = Instant.now(); }
}
