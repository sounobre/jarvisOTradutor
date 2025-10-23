package com.dnobretech.jarvistradutorbackend.epubimport.metrics;

import java.util.HashMap;
import java.util.Map;

public class Chrf {
    // chrF (char n-grams 1..6) com beta^2 = 9 (beta=3) é comum; usaremos beta=2 (beta^2=4).
    private static final int N = 6;
    private static final double BETA2 = 4.0; // (beta = 2)

    public static double chrf(String ref, String hyp) {
        if (ref == null) ref = "";
        if (hyp == null) hyp = "";
        ref = normalize(ref);
        hyp = normalize(hyp);
        double prec = 0.0, rec = 0.0, cnt = 0.0;

        for (int n=1; n<=N; n++) {
            Map<String,Integer> refN = ngrams(ref, n);
            Map<String,Integer> hypN = ngrams(hyp, n);
            int overlap = 0, hypTot = 0, refTot = 0;
            for (var e : hypN.entrySet()) {
                hypTot += e.getValue();
                int r = refN.getOrDefault(e.getKey(), 0);
                overlap += Math.min(e.getValue(), r);
            }
            for (int v : refN.values()) refTot += v;

            double p = hypTot == 0 ? 0.0 : (double)overlap / hypTot;
            double r = refTot == 0 ? 0.0 : (double)overlap / refTot;
            prec += p; rec += r; cnt += 1.0;
        }

        double P = cnt == 0 ? 0 : prec / cnt;
        double R = cnt == 0 ? 0 : rec / cnt;
        if (P+R == 0) return 0.0;
        double F = (1 + BETA2) * P * R / (R + BETA2 * P);
        // escalar para 0..100 como é comum reportar chrF
        return Math.round(F * 10000.0) / 100.0;
    }

    private static String normalize(String s){
        return s.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private static Map<String,Integer> ngrams(String s, int n){
        Map<String,Integer> m = new HashMap<>();
        if (s.length() < n) return m;
        for (int i=0;i<=s.length()-n;i++){
            String g = s.substring(i, i+n);
            m.merge(g, 1, Integer::sum);
        }
        return m;
    }
}
