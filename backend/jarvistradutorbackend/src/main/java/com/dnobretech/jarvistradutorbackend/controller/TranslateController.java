package com.dnobretech.jarvistradutorbackend.controller;

import com.dnobretech.jarvistradutorbackend.client.WorkerClient;
import com.dnobretech.jarvistradutorbackend.service.FileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;


import java.nio.file.Path;


@RestController
@RequestMapping("/api/translate")
@RequiredArgsConstructor
public class TranslateController {
    private final FileService fileService;
    private final WorkerClient workerClient;
    private final ObjectMapper mapper = new ObjectMapper();


    @Value("${app.storageDir}")
    private String storageDir;


    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) throws Exception {
        String saved = fileService.saveFile(file);
        String workerResp = workerClient.requestTranslation(Path.of(saved));
        JsonNode node = mapper.readTree(workerResp);
// Espera {status:"ok", output:"/worker/tmp/..", outputName:"<nome>.epub|.txt"}
        return ResponseEntity.ok(node);
    }


    @GetMapping("/download/{filename}")
    public ResponseEntity<?> download(@PathVariable String filename) throws Exception {
        return fileService.loadFile(filename);
    }
}
