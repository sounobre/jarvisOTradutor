// com/dnobretech/jarvistradutorbackend/client/TranslateClient.java
package com.dnobretech.jarvistradutorbackend.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TranslateClient {

    private final WebClient webClient = WebClient.builder()
            .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                    HttpClient.create()
            ))
            .build();

    @Value("${jarvis.translate.base-url}")
    private String baseUrl;  // ex.: http://127.0.0.1:8010

    @Value("${jarvis.translate.timeout-seconds:60}")
    private long timeoutSeconds;

    // ===== DTOs existentes =====
    @Data
    public static class TranslateIn {
        private String text;
        @JsonProperty("src_lang") private String srcLang = "eng_Latn";
        @JsonProperty("tgt_lang") private String tgtLang = "por_Latn";
        public TranslateIn() {}
        public TranslateIn(String text, String srcLang, String tgtLang) {
            this.text = text; this.srcLang = srcLang; this.tgtLang = tgtLang;
        }
    }

    @Data
    public static class TranslateOut {
        private String translation;
        private boolean cached;
        @JsonProperty("latency_ms") private long latencyMs;
        @JsonProperty("model_id") private String modelId;
    }

    @Data
    public static class BatchTranslateIn {
        private List<TranslateIn> items;
        public BatchTranslateIn() {}
        public BatchTranslateIn(List<TranslateIn> items) { this.items = items; }
    }

    @Data
    public static class BatchTranslateOutItem {
        private String translation;
        private boolean cached;
        @JsonProperty("latency_ms") private long latencyMs;
    }

    @Data
    public static class BatchTranslateOut {
        private List<BatchTranslateOutItem> results;
        @JsonProperty("model_id") private String modelId;
    }

    // ===== NOVO: N-best =====
    @Data
    public static class NBestIn {
        private String text;
        @JsonProperty("src_lang") private String srcLang = "eng_Latn";
        @JsonProperty("tgt_lang") private String tgtLang = "por_Latn";
        private int k = 5;

        public NBestIn() {}
        public NBestIn(String text, String srcLang, String tgtLang, int k) {
            this.text = text; this.srcLang = srcLang; this.tgtLang = tgtLang; this.k = k;
        }
    }

    @Data
    public static class NBestOut {
        private List<String> candidates;
        @JsonProperty("model_id") private String modelId;
    }

    // ===== Métodos =====
    public TranslateOut translate(String text, String srcLang, String tgtLang) {
        TranslateIn body = new TranslateIn(text, srcLang, tgtLang);
        return webClient.post()
                .uri(baseUrl + "/translate")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(TranslateOut.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();
    }

    public TranslateOut translateEnPt(String text) {
        return translate(text, "eng_Latn", "por_Latn");
    }

    public BatchTranslateOut translateBatch(List<TranslateIn> items) {
        BatchTranslateIn body = new BatchTranslateIn(items);
        return webClient.post()
                .uri(baseUrl + "/translate/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(BatchTranslateOut.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();
    }

    // NOVO: chama /translate/nbest
    public NBestOut translateNBest(String text, String srcLang, String tgtLang, int k) {
        NBestIn body = new NBestIn(text, srcLang, tgtLang, k);
        return webClient.post()
                .uri(baseUrl + "/translate/nbest")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(NBestOut.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();
    }
}
