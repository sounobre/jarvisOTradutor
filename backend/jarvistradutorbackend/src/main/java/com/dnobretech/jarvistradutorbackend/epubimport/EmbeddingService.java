// com.dnobretech.jarvistradutorbackend.epubimport.EmbeddingService.java
package com.dnobretech.jarvistradutorbackend.epubimport;

import com.dnobretech.jarvistradutorbackend.dto.EmbedResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.io.Writer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingService {

    /**
     * Tamanho do lote para evitar estouro do buffer de 64MB. Ajuste via application.yml se quiser.
     */
    @Value("${jarvis.embed.batch-size:256}")
    private int batchSize;

    /**
     * Timeout por chamada ao /embed (segundos).
     */
    @Value("${jarvis.embeddings.timeout-seconds:60}")
    private long timeoutSeconds;

    private final WebClient embClient = WebClient.builder()
            .baseUrl("http://localhost:8001")
            .clientConnector(new ReactorClientHttpConnector(
                    HttpClient.create().responseTimeout(Duration.ofSeconds(90))
            ))
            // Mantemos 64MB; o batching garante que cada resposta fique abaixo disso.
            .exchangeStrategies(ExchangeStrategies.builder()
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024))
                    .build())
            .build();

    /**
     * Assinatura preservada. Agora faz batching internamente.
     */
    public List<double[]> embedTexts(List<String> texts, boolean normalize) {
        log.info("Entrando no EmbeddingService: \n");
        List<double[]> out = embedTextsBatched(texts, normalize, Math.max(1, batchSize));
        log.info("Saindo do EmbeddingService: \n");
        return out;
    }

    /**
     * Faz várias chamadas ao /embed em fatias e concatena o resultado.
     */
    private List<double[]> embedTextsBatched(List<String> texts, boolean normalize, int batch) {
        List<double[]> out = new ArrayList<>(texts.size());
        if (texts == null || texts.isEmpty()) return out;

        for (int i = 0; i < texts.size(); i += batch) {
            int to = Math.min(i + batch, texts.size());
            List<String> slice = texts.subList(i, to);

            var payload = Map.of("texts", slice, "normalize", normalize);

            EmbedResponse resp = embClient.post()
                    .uri("/embed")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(EmbedResponse.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            if (resp == null || resp.vectors() == null) {
                log.warn("EmbedResponse vazio (lote {}..{})", i, to);
                continue;
            }
            out.addAll(resp.vectors());
        }
        return out;
    }

    /**
     * Assinatura preservada — usada por quem grava CSV com embeddings.
     */
    public void flushEmbeddingsToFile(Writer outEmbFile,
                                      List<String> bufSrc,
                                      List<String> bufTgt,
                                      String srcLang, String tgtLang,
                                      List<Double> bufQ,
                                      boolean both) throws IOException {

        var vecSrc = embedTexts(bufSrc, true);                   // agora batched
        var vecTgt = both ? embedTexts(bufTgt, true) : List.<double[]>of();

        for (int i = 0; i < bufSrc.size(); i++) {
            String src = bufSrc.get(i);
            String tgt = bufTgt.get(i);
            String embSrc = toVectorLiteral(vecSrc.get(i));
            String embTgt = both ? toVectorLiteral(vecTgt.get(i)) : "";

            StringBuilder sb = new StringBuilder(8192);
            sb.append('"').append(esc(src)).append('"').append(',');
            sb.append('"').append(esc(tgt)).append('"').append(',');
            sb.append('"').append(esc(srcLang)).append('"').append(',');
            sb.append('"').append(esc(tgtLang)).append('"').append(',');
            if (!embSrc.isEmpty()) sb.append('"').append(embSrc).append('"');
            sb.append(',');
            if (!embTgt.isEmpty()) sb.append('"').append(embTgt).append('"');
            sb.append(',');
            sb.append(bufQ.get(i)).append('\n');

            outEmbFile.write(sb.toString());
        }
        outEmbFile.flush();
        bufSrc.clear();
        bufTgt.clear();
        bufQ.clear();
    }

    // ===== helpers (iguais aos seus) =====
    private static String esc(String s) {
        return s.replace("\"", "\"\"");
    }

    private static String toVectorLiteral(double[] v) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Double.toString(v[i]));
        }
        sb.append(']');
        return sb.toString();
    }
}
