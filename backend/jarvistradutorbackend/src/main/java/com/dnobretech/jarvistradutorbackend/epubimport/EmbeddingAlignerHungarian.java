package com.dnobretech.jarvistradutorbackend.epubimport;

import com.dnobretech.jarvistradutorbackend.dto.AlignedPair;
import com.dnobretech.jarvistradutorbackend.dto.Block;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Alinha via otimização global: custo = 1 - cosine(emb_en, emb_pt).
 * - Evita duplicidade (matching 1-1).
 * - Permite EN!=PT em quantidade (retangular).
 */
@Component("embeddingAlignerHungarian")
@RequiredArgsConstructor
public class EmbeddingAlignerHungarian implements Aligner {
    private final EmbeddingService emb;

    // limiar mínimo de similaridade (ajuste conforme seu corpus)
    private static final double MIN_SIM = 0.70;

    @Override
    public List<AlignedPair> align(List<Block> src, List<Block> tgt) {
        final int N = src.size(), M = tgt.size();
        if (N == 0 || M == 0) return List.of();

        // 1) Embeddings
        var vS = emb.embedTexts(src.stream().map(Block::text).toList(), true);
        var vT = emb.embedTexts(tgt.stream().map(Block::text).toList(), true);

        // 2) Matriz de custo (1 - sim); cuidado com NaN
        double[][] cost = new double[N][M];
        for (int i = 0; i < N; i++) {
            double[] vs = vS.get(i);
            for (int j = 0; j < M; j++) {
                double sim = cosine(vs, vT.get(j));
                if (Double.isNaN(sim)) sim = 0.0;
                cost[i][j] = 1.0 - sim;
            }
        }

        // 3) Rodar Hungarian em matriz retangular
        int[] match = hungarian(cost); // match[i] = j pareado (ou -1)

        // 4) Construir pares com filtro de similaridade
        List<AlignedPair> out = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            int j = match[i];
            if (j >= 0 && j < M) {
                double sim = 1.0 - cost[i][j];
                if (sim >= MIN_SIM) {
                    Block sb = src.get(i), tb = tgt.get(j);
                    out.add(new AlignedPair(sb.text(), sb, tb.text(), tb));
                }
            }
        }
        return out;
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

    /**
     * Implementação simples do Hungarian para matriz retangular de custo.
     * Retorna array match de tamanho N (linhas), com índice da coluna pareada ou -1.
     *
     * Obs.: O(N^3). Para livros grandes, considere processar por janelas/bandas.
     */
    public static int[] hungarian(double[][] cost) {
        int n = cost.length;
        int m = cost[0].length;
        int dim = Math.max(n, m);

        // cria matriz quadrada por padding com zeros (ou custo neutro)
        double[][] a = new double[dim][dim];
        for (int i = 0; i < dim; i++) {
            Arrays.fill(a[i], 0.0);
        }
        for (int i = 0; i < n; i++) {
            System.arraycopy(cost[i], 0, a[i], 0, m);
        }

        // versão baseada em potentials (u, v) e pareamento (p, way)
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

        // p[j] = i (1-based). Queremos match[i-1] = j-1
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
