package com.dnobretech.jarvistradutorbackend.service;

public interface TMQueryService {
    String lookupBest(String src);
    void learnOnline(String src, String tgt, Double quality);
}
