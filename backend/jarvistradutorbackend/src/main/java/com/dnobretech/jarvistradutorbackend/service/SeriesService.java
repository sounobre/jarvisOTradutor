package com.dnobretech.jarvistradutorbackend.service;

import com.dnobretech.jarvistradutorbackend.domain.Series;
import com.dnobretech.jarvistradutorbackend.dto.SeriesCreateDTO;
import com.dnobretech.jarvistradutorbackend.dto.SeriesUpdateDTO;

import java.util.List;

public interface SeriesService {
    Series create(SeriesCreateDTO dto);
    Series update(Long id, SeriesUpdateDTO dto);
    Series get(Long id);
    List<Series> list();
    void delete(Long id);
}
