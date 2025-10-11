// src/main/java/com/dnobretech/jarvistradutorbackend/service/GlossarySearchService.java
package com.dnobretech.jarvistradutorbackend.service;

import com.dnobretech.jarvistradutorbackend.dto.GlossarySearchItem;

import java.util.List;

public interface GlossarySearchService {
    List<GlossarySearchItem> search(String q, Long seriesId, int k, double wvec, double wtxt);
}
