package com.dnobretech.jarvistradutorbackend.client;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingsClient {
    private final WebClient webClient = WebClient.builder().build();        // WebClient HTTP

    @Value("${jarvis.embeddings.base-url}")
    private String baseUrl;                                                 // ex.: http://localhost:8001

    // Espera um endpoint no Worker: POST /embed { "texts": ["..."] } → { "vectors": [[...],[...]] }
    public float[] embedOne(String text) {
        var resp = webClient.post()
                .uri(baseUrl + "/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("texts", java.util.List.of(text)))
                .retrieve()
                .bodyToMono(EmbedResponse.class)
                .block();

        if (resp == null || resp.vectors == null || resp.vectors.isEmpty())
            throw new IllegalStateException("Embedding vazio");

        // converte List<Double> → float[]
        var list = resp.vectors.get(0);
        float[] vec = new float[list.size()];
        for (int i = 0; i < list.size(); i++) vec[i] = list.get(i).floatValue();

        log.info("Dentro de EmbeddingsClient \n");
        log.info("vec: " + vec);

        return vec;
    }

    public record EmbedResponse(List<List<Double>> vectors) {}
}
