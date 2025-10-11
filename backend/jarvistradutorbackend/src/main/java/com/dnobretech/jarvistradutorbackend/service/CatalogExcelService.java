package com.dnobretech.jarvistradutorbackend.service;

import com.dnobretech.jarvistradutorbackend.dto.CatalogImportResult;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface CatalogExcelService {
    CatalogImportResult importExcel(MultipartFile file) throws IOException;
    byte[] exportExcel() throws IOException;
}
