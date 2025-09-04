package com.dnobretech.jarvistradutorbackend.service.impl;

import lombok.RequiredArgsConstructor;
import com.dnobretech.jarvistradutorbackend.service.FileService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;


import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;


@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {
    @Value("${app.storageDir}")
    private String storageDir;


    @Override
    public String saveFile(MultipartFile file) throws IOException {
        Files.createDirectories(Path.of(storageDir, "uploads"));
        String ext = getExt(file.getOriginalFilename());
        String fname = UUID.randomUUID() + (ext.isEmpty()?"":"."+ext);
        Path dest = Path.of(storageDir, "uploads", fname);
        Files.copy(file.getInputStream(), dest, StandardCopyOption.REPLACE_EXISTING);
        return dest.toString();
    }


    @Override
    public ResponseEntity<Resource> loadFile(String filename) throws IOException {
        Path path = Path.of(storageDir, "translated", filename);
        if (!Files.exists(path)) throw new IOException("Arquivo não encontrado: " + filename);
        FileSystemResource res = new FileSystemResource(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + path.getFileName())
                .contentType(filename.endsWith(".epub") ? MediaType.parseMediaType("application/epub+zip") : MediaType.TEXT_PLAIN)
                .body(res);
    }


    private String getExt(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i>0? name.substring(i+1): "";
    }
}