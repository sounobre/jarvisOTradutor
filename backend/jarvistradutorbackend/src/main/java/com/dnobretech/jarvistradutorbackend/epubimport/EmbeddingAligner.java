// com.dnobretech.jarvistradutorbackend.epubimport.EmbeddingAligner.java
package com.dnobretech.jarvistradutorbackend.epubimport;

import com.dnobretech.jarvistradutorbackend.dto.AlignedPair;
import com.dnobretech.jarvistradutorbackend.dto.Block;
import com.dnobretech.jarvistradutorbackend.dto.Pair;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class EmbeddingAligner implements Aligner {
    private final EmbeddingService emb;

    @Override
    public List<AlignedPair> align(List<Block> src, List<Block> tgt) {
        final int N = src.size(), M = tgt.size(), BATCH=128;
        List<AlignedPair> out = new ArrayList<>(Math.min(N,M));

        // embed tgt uma vez
        var tgtTexts = tgt.stream().map(Block::text).toList();
        var vT = emb.embedTexts(tgtTexts, true);

        for (int i=0; i<N; i+=BATCH) {
            int i2 = Math.min(i+BATCH, N);
            var sBatch = src.subList(i,i2);
            var vS = emb.embedTexts(sBatch.stream().map(Block::text).toList(), true);

            for (int a=0; a<sBatch.size(); a++) {
                double best = -1; int bestJ = -1;
                double[] vs = vS.get(a);
                for (int j=0; j<M; j++) {
                    double sim = cosine(vs, vT.get(j));
                    if (sim>best){ best=sim; bestJ=j; }
                }
                if (bestJ>=0) {
                    Block sb = sBatch.get(a), tb = tgt.get(bestJ);
                    out.add(new AlignedPair(sb.text(), sb, tb.text(), tb));
                }
            }
        }
        return out;
    }

    private static double cosine(double[] a, double[] b){
        double dot=0,na=0,nb=0;
        for (int i=0;i<a.length;i++){ dot+=a[i]*b[i]; na+=a[i]*a[i]; nb+=b[i]*b[i]; }
        double d = Math.sqrt(na)*Math.sqrt(nb);
        return d==0 ? 0 : dot/d;
    }
}