// --- NEW FILE: com/dnobretech/jarvistradutorbackend/client/GptjClient.java ---
package com.dnobretech.jarvistradutorbackend.client;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Cliente simples para um servidor GPT-J estilo "text-generation" com POST /generate
 * Espera:
 *   req:  { "prompt": "...", "max_new_tokens": 192, "temperature": 0.2 }
 *   resp: { "text": "..." }  (modelo deve retornar JSON válido no 'text')
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GptjClient {

    private final WebClient web = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(HttpClient.create()))
            .build();

    @Value("${jarvis.gptj.base-url:http://localhost:8010}")
    private String baseUrl;

    @Value("${jarvis.gptj.timeout-seconds:60}")
    private long timeoutSeconds;

    public String generate(String prompt, int maxNewTokens, double temperature) {
        Map<String, Object> body = Map.of(
                "prompt", prompt,
                "max_new_tokens", maxNewTokens,
                "temperature", temperature
        );

        var resp = web.post()
                .uri(baseUrl + "/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(GenResp.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        if (resp == null || resp.text == null) {
            log.warn("[GPT-J] resposta vazia");
            return "";
        }
        return resp.text;
    }

    @Data
    public static class GenResp { private String text; }
}
