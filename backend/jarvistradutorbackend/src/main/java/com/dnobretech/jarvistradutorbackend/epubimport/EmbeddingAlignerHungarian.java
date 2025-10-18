package com.dnobretech.jarvistradutorbackend.epubimport;

import com.dnobretech.jarvistradutorbackend.dto.AlignedPair;
import com.dnobretech.jarvistradutorbackend.dto.Block;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Alinha via otimização global:
 * custo = W_SIM * (1 - simBlend) + W_POS * posPenalty
 * com banding por proximidade de capítulo/spine.
 */
@Slf4j
@Component("embeddingAlignerHungarian")
@RequiredArgsConstructor
public class EmbeddingAlignerHungarian implements Aligner {
    private final EmbeddingService emb;

    // Limiar final
    private static final double MIN_SIM = 0.70;

    // pesos do custo
    private static final double W_SIM = 0.75;
    private static final double W_POS = 0.25;

    // banding de posição
    private static final int BAND_SPINE = 3;
    private static final double BIG = 1e6;

    @Override
    public List<AlignedPair> align(List<Block> src, List<Block> tgt) {
        log.info("EmbeddingAlignerHungarian: srcN={}, tgtM={}", src.size(), tgt.size());
        final int N = src.size(), M = tgt.size();
        if (N == 0 || M == 0) return List.of();

        // 1) Embeddings unitários
        var sTexts = src.stream().map(Block::text).toList();
        var tTexts = tgt.stream().map(Block::text).toList();
        var vS = emb.embedTexts(sTexts, true);
        var vT = emb.embedTexts(tTexts, true);

        // 1.1) Embeddings de contexto (prev+curr+next)
        var sCtxTexts = new ArrayList<String>(N);
        var tCtxTexts = new ArrayList<String>(M);
        for (int i = 0; i < N; i++) sCtxTexts.add(ctx(src, i));
        for (int j = 0; j < M; j++) tCtxTexts.add(ctx(tgt, j));
        var vSctx = emb.embedTexts(sCtxTexts, true);
        var vTctx = emb.embedTexts(tCtxTexts, true);

        // 2) Matriz de custo com blend de similaridade + penalidade de posição
        double[][] cost = new double[N][M];
        for (int i = 0; i < N; i++) {
            Block sb = src.get(i);
            double[] vs = vS.get(i);
            double[] vsCtx = vSctx.get(i);

            for (int j = 0; j < M; j++) {
                Block tb = tgt.get(j);

                // banding por proximidade de capítulo/spine
                if (Math.abs(sb.spineIdx() - tb.spineIdx()) > BAND_SPINE) {
                    cost[i][j] = BIG;
                    continue;
                }

                double simUnit = cosine(vs, vT.get(j));
                double simCtx  = cosine(vsCtx, vTctx.get(j));
                if (Double.isNaN(simUnit)) simUnit = 0.0;
                if (Double.isNaN(simCtx))  simCtx  = 0.0;

                // blend de similaridade
                double simBlend = 0.7 * simUnit + 0.3 * simCtx;
                simBlend = clamp01(simBlend);

                // penalidade posicional
                double pos = posPenalty(sb, tb);

                cost[i][j] = W_SIM * (1.0 - simBlend) + W_POS * pos;
            }
        }

        // 3) Hungarian
        int[] match = hungarian(cost);

        // 4) Pares brutos
        List<AlignedPair> pairsRaw = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            int j = match[i];
            if (j >= 0 && j < M && cost[i][j] < BIG / 2) {
                // recompute simBlend p/ guardar no par
                double simUnit = cosine(vS.get(i), vT.get(j));
                double simCtx  = cosine(vSctx.get(i), vTctx.get(j));
                double simBlend = clamp01(0.7 * simUnit + 0.3 * simCtx);

                Block sb = src.get(i), tb = tgt.get(j);
                pairsRaw.add(new AlignedPair(sb.text(), sb, tb.text(), tb, simBlend));
            }
        }

        // 5) (Opcional) reparo pós-processamento
        List<AlignedPair> repaired = AlignmentRepairer.repair(
                pairsRaw,
                MIN_SIM,
                (a, b) -> {
                    var va = emb.embedTexts(List.of(a), true).get(0);
                    var vb = emb.embedTexts(List.of(b), true).get(0);
                    return cosine(va, vb);
                }
        );

        // 6) filtro final
        List<AlignedPair> out = new ArrayList<>();
        for (AlignedPair ap : repaired) {
            if (ap.sim() >= MIN_SIM) out.add(ap);
        }

        log.info("EmbeddingAlignerHungarian: raw={}, repaired={}, kept>={} (minSim={})",
                pairsRaw.size(), repaired.size(), out.size(), MIN_SIM);
        return out;
    }

    private static String ctx(List<Block> xs, int i) {
        StringBuilder b = new StringBuilder();
        int from = Math.max(0, i - 1);
        int to   = Math.min(xs.size() - 1, i + 1);
        for (int k = from; k <= to; k++) {
            if (b.length() > 0) b.append(' ');
            b.append(xs.get(k).text());
        }
        return b.toString();
    }

    private static double posPenalty(Block a, Block b) {
        // capítulo diferente pesa bastante
        double chap = 0.0;
        if (a.chapterTitle() != null && b.chapterTitle() != null) {
            boolean same = a.chapterTitle().equalsIgnoreCase(b.chapterTitle());
            chap = same ? 0.0 : 1.0; // 0 = mesmo capítulo, 1 = diferente
        }

        // distância de spine e de bloco (limitadas)
        double spineDelta = Math.min(10, Math.abs(a.spineIdx() - b.spineIdx()));
        double blockDelta = Math.min(30, Math.abs(a.blockIdx() - b.blockIdx()));

        double normSpine = spineDelta / 10.0; // 0..1
        double normBlock = blockDelta / 30.0; // 0..1

        // pesos internos da penalidade
        return 0.6 * chap + 0.3 * normSpine + 0.1 * normBlock; // 0..1 aprox.
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        int L = Math.min(a.length, b.length);
        for (int i = 0; i < L; i++) {
            dot += a[i] * b[i];
            na  += a[i] * a[i];
            nb  += b[i] * b[i];
        }
        double d = Math.sqrt(na) * Math.sqrt(nb);
        return d == 0 ? 0 : dot / d;
    }

    // === Hungarian (inalterado) ===
    public static int[] hungarian(double[][] cost) {
        int n = cost.length;
        int m = cost[0].length;
        int dim = Math.max(n, m);

        double[][] a = new double[dim][dim];
        for (int i = 0; i < dim; i++) Arrays.fill(a[i], 0.0);
        for (int i = 0; i < n; i++) System.arraycopy(cost[i], 0, a[i], 0, m);

        double[] u = new double[dim + 1];
        double[] v = new double[dim + 1];
        int[] p = new int[dim + 1];
        int[] way = new int[dim + 1];

        for (int i = 1; i <= dim; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[dim + 1];
            boolean[] used = new boolean[dim + 1];
            Arrays.fill(minv, Double.POSITIVE_INFINITY);
            Arrays.fill(used, false);

            do {
                used[j0] = true;
                int i0 = p[j0], j1 = 0;
                double delta = Double.POSITIVE_INFINITY;

                for (int j = 1; j <= dim; j++) {
                    if (used[j]) continue;
                    double cur = a[i0 - 1][j - 1] - u[i0] - v[j];
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0; }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j; }
                }

                for (int j = 0; j <= dim; j++) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta; }
                    else { minv[j] -= delta; }
                }
                j0 = j1;
            } while (p[j0] != 0);

            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }

        int[] match = new int[n];
        Arrays.fill(match, -1);
        for (int j = 1; j <= dim; j++) {
            int i = p[j];
            if (i >= 1 && i <= n && j >= 1 && j <= m) {
                match[i - 1] = j - 1;
            }
        }
        return match;
    }
}
