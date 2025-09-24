package com.dnobretech.jarvistradutorbackend.domain;



import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity                     // mapeia para tabela "glossary"
@Table(name = "glossary")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Glossary {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)     // BIGSERIAL
    private Long id;                                        // id

    @Column(nullable = false, unique = true, columnDefinition = "text")
    private String src;                                     // termo origem

    @Column(nullable = false, columnDefinition = "text")
    private String tgt;                                     // termo destino

    private String note;                                    // observação

    @Builder.Default
    private Boolean approved = true;                        // aprovado?

    @Builder.Default
    private Integer priority = 0;                           // prioridade (maior = aplica antes)

    @Column(name = "created_at")
    private OffsetDateTime createdAt;                       // timestamp
}
