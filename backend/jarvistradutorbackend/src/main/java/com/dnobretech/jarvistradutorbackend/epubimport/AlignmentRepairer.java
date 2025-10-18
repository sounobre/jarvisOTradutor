package com.dnobretech.jarvistradutorbackend.epubimport;

import com.dnobretech.jarvistradutorbackend.dto.AlignedPair;
import com.dnobretech.jarvistradutorbackend.dto.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

public class AlignmentRepairer {

    // sinal simples de fala: aspas no EN, travessão/aspas no PT
    private static boolean looksSpeechEN(String s){
        return s.contains("\"");
    }
    private static boolean looksSpeechPT(String s){
        return s.contains("\"") || s.startsWith("-") || s.startsWith("—");
    }

    /**
     * Tenta consertar pares fracos fundindo vizinhos dentro de uma janela curta.
     * embSimFn recebe (textoA, textoB) e retorna cosine 0..1 usando os embeddings já carregados/cached.
     */
    public static List<AlignedPair> repair(
            List<AlignedPair> in,
            double minSim,
            BiFunction<String,String,Double> embSimFn
    ){
        List<AlignedPair> out = new ArrayList<>(in.size());
        for (int i=0; i<in.size(); i++){
            AlignedPair ap = in.get(i);
            double sim = safeSim(ap);
            if (sim >= minSim) { out.add(ap); continue; }

            String s = ap.src();
            String t = ap.tgt();
            boolean speechLike = looksSpeechEN(s) || looksSpeechPT(t);

            if (!speechLike) { out.add(ap); continue; }

            // candidatos: fundir com próximo à direita (1→2) ou fundir src com próximo (2→1)
            AlignedPair fused = tryFuseRight(in, i, embSimFn, minSim);
            if (fused != null) { out.add(fused); i++; continue; }

            AlignedPair fusedLeft = tryFuseSrcRight(in, i, embSimFn, minSim);
            if (fusedLeft != null) { out.add(fusedLeft); i++; continue; }

            // não deu: mantém como está
            out.add(ap);
        }
        return out;
    }

    private static double safeSim(AlignedPair ap){
        try { return Math.max(0, Math.min(1, ap.sim())); } catch(Exception e){ return 0.0; }
    }

    // 1→2: mantém src[i], concatena tgt[i] + tgt[i+1]
    private static AlignedPair tryFuseRight(List<AlignedPair> in, int i,
                                            BiFunction<String,String,Double> embSimFn, double minSim){
        if (i+1 >= in.size()) return null;
        AlignedPair a = in.get(i);
        AlignedPair b = in.get(i+1);

        // só se for o mesmo src (Hungarian 1-1 normalmente não repete, mas por segurança)
        Block src = a.srcPos();
        if (src != null && b.srcPos()!=null && b.srcPos()!=src) {
            // se mudou de src, não é 1→2
        }

        String tgt2 = a.tgt() + " " + b.tgt();
        double sim2 = embSimFn.apply(a.src(), tgt2);
        if (sim2 >= minSim - 0.02) { // tolerância
            return new AlignedPair(a.src(), a.srcPos(), tgt2, fusePos(a.tgtPos(), b.tgtPos()), sim2);
        }
        return null;
    }

    // 2→1: concatena src[i] + src[i+1], mantém tgt[i]
    private static AlignedPair tryFuseSrcRight(List<AlignedPair> in, int i,
                                               BiFunction<String,String,Double> embSimFn, double minSim){
        if (i+1 >= in.size()) return null;
        AlignedPair a = in.get(i);
        AlignedPair b = in.get(i+1);

        Block tgt = a.tgtPos();
        if (tgt != null && b.tgtPos()!=null && b.tgtPos()!=tgt) {
            // se mudou de tgt, não é 2→1
        }

        String src2 = a.src() + " " + b.src();
        double sim2 = embSimFn.apply(src2, a.tgt());
        if (sim2 >= minSim - 0.02) {
            return new AlignedPair(src2, fusePos(a.srcPos(), b.srcPos()), a.tgt(), a.tgtPos(), sim2);
        }
        return null;
    }

    private static Block fusePos(Block a, Block b){
        if (a==null) return b;
        if (b==null) return a;
        // mantém início de a; poderia marcar range se quiser
        return a;
    }
}
