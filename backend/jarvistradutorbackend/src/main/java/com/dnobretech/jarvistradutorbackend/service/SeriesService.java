// src/main/java/com/dnobretech/jarvistradutorbackend/service/SeriesService.java
package com.dnobretech.jarvistradutorbackend.service;

import com.dnobretech.jarvistradutorbackend.dto.SeriesCreateDTO;
import com.dnobretech.jarvistradutorbackend.dto.SeriesDTO;
import com.dnobretech.jarvistradutorbackend.dto.SeriesUpdateDTO;

import java.util.List;

public interface SeriesService {
    SeriesDTO create(SeriesCreateDTO dto);
    SeriesDTO update(Long id, SeriesUpdateDTO dto);
    SeriesDTO get(Long id);
    List<SeriesDTO> list();
    void delete(Long id);

    List<SeriesDTO> findAllByAuthor(Long id);

}
