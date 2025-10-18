package com.dnobretech.jarvistradutorbackend.client;


import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.*;

@Component
@RequiredArgsConstructor
public class BtClient {
    private final WebClient web = WebClient.builder()
            .baseUrl("http://localhost:8001")
            .clientConnector(new ReactorClientHttpConnector(HttpClient.create()))
            .build();

    @Value("${jarvis.bt.timeout-seconds:120}")
    private long timeoutSeconds;

    @Data public static class Pair { String src; String tgt; }
    @Data public static class Batch { List<Pair> pairs; }
    @Data public static class Resp  { List<Double> chrf; }

    public List<Double> chrfBatch(List<Pair> pairs){
        if(pairs==null || pairs.isEmpty()) return List.of();
        Batch b = new Batch(); b.setPairs(pairs);
        Resp r = web.post().uri("/btcheck")
                .bodyValue(b)
                .retrieve()
                .bodyToMono(Resp.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();
        return (r!=null && r.getChrf()!=null) ? r.getChrf() : Collections.emptyList();
    }
}
