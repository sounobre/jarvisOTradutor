package com.dnobretech.jarvistradutorbackend.client;

import com.dnobretech.jarvistradutorbackend.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerClient {
    private final FileService fileService;


    @Value("${app.workerBaseUrl}")
    private String workerBaseUrl;


    public String requestTranslation(Path inputPath) {
        RestTemplate rt = new RestTemplate();

        FileSystemResource fileRes = new FileSystemResource(inputPath.toFile());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileRes); // simples, sem headers de part

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        HttpEntity<MultiValueMap<String, Object>> req = new HttpEntity<>(body, headers);

        ResponseEntity<String> resp = rt.postForEntity(workerBaseUrl + "/translate", req, String.class);
        log.info("Retorno: " + resp);
        return resp.getBody();
    }
}
