// src/main/java/com/dnobretech/jarvistradutorbackend/dto/GlossarySearchItem.java
package com.dnobretech.jarvistradutorbackend.dto;

public record GlossarySearchItem(
        Long id,
        Long seriesId,
        String src,
        String tgt,
        double score,
        Double simVec,
        Double simTxt
) {}
