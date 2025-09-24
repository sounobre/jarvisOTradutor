package com.dnobretech.jarvistradutorbackend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "tm")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TM {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                                        // id

    @Column(nullable = false, columnDefinition = "text")
    private String src;                                     // frase origem

    @Column(nullable = false, columnDefinition = "text")
    private String tgt;                                     // frase destino

    @Builder.Default
    private String langSrc = "en";                          // idioma origem

    @Builder.Default
    private String langTgt = "pt";                          // idioma destino

    private Double quality;                                 // score opcional

    @Builder.Default
    private Integer usedCount = 0;                          // contador de uso

    @Column(name = "created_at")
    private OffsetDateTime createdAt;                       // timestamp
}