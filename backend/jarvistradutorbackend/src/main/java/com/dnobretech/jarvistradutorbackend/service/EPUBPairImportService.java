package com.dnobretech.jarvistradutorbackend.service;

import com.dnobretech.jarvistradutorbackend.dto.Result;
import org.springframework.web.multipart.MultipartFile;

public interface EPUBPairImportService {
    public Result importParallelEPUB(MultipartFile fileEn,
                                     MultipartFile filePt,
                                     String level,       // "paragraph"|"sentence"
                                     String mode,        // "length"|"embedding"
                                     String srcLang,
                                     String tgtLang,
                                     double minQuality,  // descarta abaixo
                                     Long seriesId,
                                     Long bookId,
                                     String sourceTag) throws Exception;
}
