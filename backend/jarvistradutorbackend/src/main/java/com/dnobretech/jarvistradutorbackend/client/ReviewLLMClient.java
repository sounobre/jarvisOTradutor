// --- FILE: src/main/java/com/dnobretech/jarvistradutorbackend/client/ReviewLLMClient.java ---
package com.dnobretech.jarvistradutorbackend.client;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;

/**
 * Client para se comunicar com a API Python do revisor (FastAPI).
 * Envia pares EN↔PT para avaliação em lote e retorna status/score/comentário.
 *
 * Endpoint alvo: http://localhost:8010/review
 */
@Component
@RequiredArgsConstructor
public class ReviewLLMClient {

    private final WebClient web = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(HttpClient.create()))
            .build();

    @Value("${jarvis.reviewer.base-url:http://localhost:8010}")
    private String baseUrl;

    @Value("${jarvis.reviewer.timeout-seconds:120}")
    private long timeoutSeconds;

    /**
     * Envia um lote de pares para a API do revisor e retorna as avaliações.
     */
    public List<Result> reviewBatch(List<Item> items,
                                    Integer maxNewTokens,
                                    Double temperature,
                                    Double topP,
                                    Double repetitionPenalty) {

        Request req = new Request();
        req.setItems(items);
        if (maxNewTokens != null) req.setMax_new_tokens(maxNewTokens);
        if (temperature != null) req.setTemperature(temperature);
        if (topP != null) req.setTop_p(topP);
        if (repetitionPenalty != null) req.setRepetition_penalty(repetitionPenalty);

        Response resp = web.post()
                .uri(baseUrl + "/review")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(Response.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        return (resp != null && resp.results != null) ? resp.results : List.of();
    }

    // --- DTOs usados na comunicação com a API ---

    @Data
    public static class Item {
        private Long id;
        private String src;
        private String tgt;
        private String chapter_en;
        private String chapter_pt;
        private Double final_score;
    }

    @Data
    public static class Request {
        private List<Item> items;
        private Integer max_new_tokens = 96;
        private Double temperature = 0.0;
        private Double top_p = 1.0;
        private Double repetition_penalty = 1.0;
    }

    @Data
    public static class Result {
        private String status;   // good | suspect | bad
        private Double score;    // 0..1
        private String comment;  // comentário gerado
    }

    @Data
    public static class Response {
        private List<Result> results;
    }
}
