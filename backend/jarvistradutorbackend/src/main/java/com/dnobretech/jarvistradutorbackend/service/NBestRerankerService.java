package com.dnobretech.jarvistradutorbackend.service;

import com.dnobretech.jarvistradutorbackend.service.impl.NBestRerankerServiceImpl;

public interface NBestRerankerService {
    NBestRerankerServiceImpl.RerankResult suggestBest(String srcEn, String srcLang, String tgtLang, int k);
}
