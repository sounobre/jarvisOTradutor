package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.client.QeClient;
import com.dnobretech.jarvistradutorbackend.client.TranslateClient;
import com.dnobretech.jarvistradutorbackend.service.NBestRerankerService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NBestRerankerServiceImpl implements NBestRerankerService {

    private final TranslateClient translateClient;
    private final QeClient qeClient;

    @Data
    public static class RerankResult {
        private String best;               // melhor candidato PT
        private int bestIndex;             // índice em candidates
        private List<String> candidates;   // candidatos PT
        private List<Double> qeScores;     // scores QE
        private String modelId;            // modelo do tradutor
    }

    /**
     * Gera k candidatos PT para um src EN e escolhe o melhor por QE (ref-free) com o próprio src.
     */
    @Override
    public RerankResult suggestBest(String srcEn, String srcLang, String tgtLang, int k) {
        // 1) obter n-best do tradutor
        var nbest = translateClient.translateNBest(srcEn, srcLang, tgtLang, k);
        List<String> cands = nbest.getCandidates();
        if (cands == null || cands.isEmpty()) {
            throw new IllegalStateException("Nenhum candidato retornado pelo tradutor.");
        }

        // 2) preparar itens para QE
        List<QeClient.QEItem> items = new ArrayList<>(cands.size());
        for (String hyp : cands) {
            QeClient.QEItem it = new QeClient.QEItem();
            it.setSrc(srcEn);
            it.setMt(hyp);
            items.add(it);
        }

        // 3) pedir QE
        List<Double> scores = qeClient.scoreBatch(items);
        if (scores == null || scores.size() != cands.size()) {
            // alinhar tamanho, preenchendo faltantes com 0.0, similar ao teu QeClient
            List<Double> fixed = new ArrayList<>(cands.size());
            for (int i=0; i<cands.size(); i++) {
                fixed.add(i < (scores==null?0:scores.size()) ? scores.get(i) : 0.0);
            }
            scores = fixed;
        }

        // 4) escolher o índice do maior score
        int bestIdx = 0;
        double bestScore = -1;
        for (int i=0; i<scores.size(); i++) {
            double s = scores.get(i) != null ? scores.get(i) : 0.0;
            if (s > bestScore) {
                bestScore = s;
                bestIdx = i;
            }
        }

        RerankResult res = new RerankResult();
        res.setCandidates(cands);
        res.setQeScores(scores);
        res.setBestIndex(bestIdx);
        res.setBest(cands.get(bestIdx));
        res.setModelId(nbest.getModelId());
        return res;
    }
}
