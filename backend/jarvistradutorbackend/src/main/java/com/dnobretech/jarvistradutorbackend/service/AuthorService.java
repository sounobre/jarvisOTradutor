// src/main/java/com/dnobretech/jarvistradutorbackend/service/AuthorService.java
package com.dnobretech.jarvistradutorbackend.service;

import com.dnobretech.jarvistradutorbackend.dto.AuthorCreateDTO;
import com.dnobretech.jarvistradutorbackend.dto.AuthorDTO;
import com.dnobretech.jarvistradutorbackend.dto.AuthorFromSeriesSearchDto;
import com.dnobretech.jarvistradutorbackend.dto.AuthorUpdateDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface AuthorService {
    AuthorDTO create(AuthorCreateDTO dto);
    AuthorDTO update(Long id, AuthorUpdateDTO dto);
    AuthorDTO get(Long id);
    AuthorDTO getBySlug(String slug);
    Page<AuthorDTO> list(Pageable pageable, String q); // q: busca por nome (opcional)
    void delete(Long id);

    List<AuthorFromSeriesSearchDto> findAllSortedByName();
}
