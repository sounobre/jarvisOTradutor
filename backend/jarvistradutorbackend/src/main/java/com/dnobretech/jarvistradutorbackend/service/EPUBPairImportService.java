package com.dnobretech.jarvistradutorbackend.service;

import com.dnobretech.jarvistradutorbackend.dto.Result;
import org.springframework.web.multipart.MultipartFile;

public interface EPUBPairImportService {
    public Result importParallelEPUB(MultipartFile en, MultipartFile pt,
                                     String level, String mode,
                                     String srcLang, String tgtLang,
                                     double minQuality) throws Exception;
}
