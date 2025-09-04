package com.dnobretech.jarvistradutorbackend.service;

import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import org.springframework.core.io.Resource;


public interface FileService {
    String saveFile(MultipartFile file) throws IOException;
    ResponseEntity<Resource> loadFile(String filename) throws IOException;
}
