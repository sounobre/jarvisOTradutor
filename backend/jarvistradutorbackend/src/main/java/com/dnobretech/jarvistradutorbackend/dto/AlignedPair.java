package com.dnobretech.jarvistradutorbackend.dto;

// Se quiser carregar a posição em pares alinhados:
public record AlignedPair(
        String src, Block srcPos,
        String tgt, Block tgtPos,
        double sim // <-- novo
) {}

