package com.dnobretech.jarvistradutorbackend.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@ToString
@Table(name = "import_checkpoint")
public class ImportCheckpoint {

    @Id
    @Column(name = "file_key", nullable = false, length = 255)
    private String fileKey;                 // identificador lógico (ex: "paracrawl_v1")

    @Column(name = "path", nullable = false, length = 2048)
    private String path;                    // caminho do arquivo no servidor

    @Column(name = "byte_offset", nullable = false)
    private long byteOffset;                // posição em BYTES dentro do arquivo (após última linha processada)

    @Column(name = "line_count", nullable = false)
    private long lineCount;                 // total de linhas já lidas (para telemetria)

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;              // auto-atualiza a cada persist/merge
}
