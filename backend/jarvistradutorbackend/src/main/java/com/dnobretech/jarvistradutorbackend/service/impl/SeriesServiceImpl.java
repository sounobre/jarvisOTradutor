package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.domain.Series;
import com.dnobretech.jarvistradutorbackend.dto.SeriesCreateDTO;
import com.dnobretech.jarvistradutorbackend.dto.SeriesUpdateDTO;
import com.dnobretech.jarvistradutorbackend.repository.SeriesRepository;
import com.dnobretech.jarvistradutorbackend.service.SeriesService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SeriesServiceImpl implements SeriesService {

    private final SeriesRepository repo;

    @Override
    @Transactional
    public Series create(SeriesCreateDTO dto) {
        if (repo.existsBySlug(dto.getSlug())) {
            throw new IllegalArgumentException("slug já existente: " + dto.getSlug());
        }
        Series s = Series.builder()
                .name(dto.getName())
                .slug(dto.getSlug())
                .description(dto.getDescription())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return repo.save(s);
    }

    @Override
    @Transactional
    public Series update(Long id, SeriesUpdateDTO dto) {
        Series s = repo.findById(id).orElseThrow(() -> new EntityNotFoundException("series id=" + id + " não encontrada"));
        if (dto.getName() != null) s.setName(dto.getName());
        if (dto.getDescription() != null) s.setDescription(dto.getDescription());
        s.setUpdatedAt(Instant.now());
        return repo.save(s);
    }

    @Override
    @Transactional(readOnly = true)
    public Series get(Long id) {
        return repo.findById(id).orElseThrow(() -> new EntityNotFoundException("series id=" + id + " não encontrada"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Series> list() {
        return repo.findAll();
    }

    @Override
    @Transactional
    public void delete(Long id) {
        repo.deleteById(id);
    }
}
