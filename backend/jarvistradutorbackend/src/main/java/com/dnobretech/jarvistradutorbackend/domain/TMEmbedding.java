package com.dnobretech.jarvistradutorbackend.domain;


import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tm_embeddings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TMEmbedding {
    @Id
    @Column(name = "tm_id")
    private Long tmId;                                      // PK = FK para TM.id

    // Usamos byte[] para receber do JDBC; o repositório custom faz bind como vector
    @Transient
    private float[] srcVec;                                 // representaremos no insert custom

    // Dica: como o JPA não entende 'vector' nativamente, a escrita/leitura será via JDBC custom
}