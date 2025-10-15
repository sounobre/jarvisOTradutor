package com.dnobretech.jarvistradutorbackend.dto;

public record Block(
        String text,
        int spineIdx,     // índice do item no spine/TOC
        int blockIdx,     // índice do bloco <p>/<div> dentro do spine
        int sentIdx,      // índice da sentença dentro do bloco (0 se level=paragraph)
        String chapterTitle // se disponível; senão, algo tipo "spine-<i>"
) {}
