package com.dnobretech.jarvistradutorbackend.dto;

public record CheckpointDTO(String fileKey, String path, long byteOffset, long lineCount, Long fileSize) {
}
