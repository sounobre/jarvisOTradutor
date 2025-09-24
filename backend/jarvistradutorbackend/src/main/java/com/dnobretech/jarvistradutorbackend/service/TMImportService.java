package com.dnobretech.jarvistradutorbackend.service;

import com.dnobretech.jarvistradutorbackend.dto.ResumeResult;
import org.springframework.web.multipart.MultipartFile;

public interface TMImportService {

    // upload normal (multipart) — AGORA streaming → COPY (sem arquivo temporário)
    long importTsvOrCsvStreaming(MultipartFile file, String delimiter) throws Exception;

    // resumível (lê do disco do servidor em lotes; salva checkpoint)

    ResumeResult importTxtResume(String path, String delimiter, String fileKey, int batchLines, int examples, String embed) throws Exception;

    // utilidades para acompanhar/gerenciar checkpoint
    record CheckpointDTO(String fileKey, String path, long byteOffset, long lineCount, Long fileSize) {}
    CheckpointDTO getCheckpoint(String fileKey);
    void resetCheckpoint(String fileKey);
}
