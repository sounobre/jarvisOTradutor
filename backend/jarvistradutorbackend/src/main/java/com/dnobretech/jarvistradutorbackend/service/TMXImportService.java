package com.dnobretech.jarvistradutorbackend.service;

import org.springframework.web.multipart.MultipartFile;

public interface TMXImportService {
    long importTmx(MultipartFile file, String srcLang, String tgtLang) throws Exception;
}
