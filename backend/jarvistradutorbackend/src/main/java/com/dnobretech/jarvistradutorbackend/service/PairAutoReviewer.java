package com.dnobretech.jarvistradutorbackend.service;

public interface PairAutoReviewer {
    // versão “completa”
    int reviewBatch(double minFinal, double maxFinal, int limit, int k, String mode);

    // versão com defaults (k=5, mode="auto")
    default int reviewBatch(double minFinal, double maxFinal, int limit) {
        return reviewBatch(minFinal, maxFinal, limit, 5, "auto");
    }
}
