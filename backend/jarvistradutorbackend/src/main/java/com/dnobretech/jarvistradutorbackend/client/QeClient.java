// --- UPDATED FILE: com/dnobretech/jarvistradutorbackend/client/QeClient.java ---
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
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class QeClient {

    private final WebClient web = WebClient.builder()
            .baseUrl("http://localhost:8001")
            .clientConnector(new ReactorClientHttpConnector(
                    HttpClient.create().responseTimeout(Duration.ofSeconds(300)) // ↑
            ))
            .build();

    @Value("${jarvis.qe.timeout-seconds:300}")  // ↑
    private long timeoutSeconds;

    // quantos pares por request pro /qe
    @Value("${jarvis.qe.http-batch:128}")       // ↑
    private int httpBatch;

    @Data public static class QEItem { String src; String mt; String tgt; }
    @Data public static class QERequest { List<QEItem> items; }
    @Data public static class QEResponse { List<Double> scores; Double mean; }

    /** Envia uma lista grande em fatias, mantendo a ordem dos scores. */
    public List<Double> scoreBatch(List<QEItem> items){
        if (items == null || items.isEmpty()) return List.of();
        int B = Math.max(8, httpBatch);
        List<Double> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i += B) {
            int to = Math.min(i + B, items.size());
            List<QEItem> slice = items.subList(i, to);
            try {
                log.info("Entrando no QeClient");
                QERequest req = new QERequest(); req.setItems(slice);
                QEResponse resp = web.post().uri("/qe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(req)
                        .retrieve()
                        .bodyToMono(QEResponse.class)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .retryWhen(
                                Retry.backoff(2, Duration.ofMillis(300))  // 2 tentativas
                                        .maxBackoff(Duration.ofSeconds(2))
                        )
                        .block();
                log.info("Res do Qe: " + req);

                List<Double> scores = (resp != null && resp.getScores() != null)
                        ? resp.getScores() : Collections.emptyList();

                // garante comprimento
                if (scores.size() != slice.size()) {
                    log.warn("[QE] tamanhos diferentes ({} != {}), preenchendo faltantes com 0.",
                            scores.size(), slice.size());
                    List<Double> fixed = new ArrayList<>(slice.size());
                    for (int k = 0; k < slice.size(); k++) {
                        fixed.add(k < scores.size() ? scores.get(k) : 0.0);
                    }
                    out.addAll(fixed);
                } else {
                    out.addAll(scores);
                }
            } catch (Exception e) {
                log.warn("[QE] falha/timeout no lote {}..{}: {} — usando zeros para este slice",
                        i, to, e.toString());
                // fallback: zeros (degrada mas não quebra o import)
                for (int k = i; k < to; k++) out.add(0.0);
            }
        }
        log.info("Saindo do QeClient");
        return out;
    }
}
