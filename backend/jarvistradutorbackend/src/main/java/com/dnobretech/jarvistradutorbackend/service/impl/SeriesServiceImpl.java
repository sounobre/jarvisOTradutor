// src/main/java/com/dnobretech/jarvistradutorbackend/service/impl/SeriesServiceImpl.java
package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.domain.Author;
import com.dnobretech.jarvistradutorbackend.domain.Series;
import com.dnobretech.jarvistradutorbackend.dto.SeriesCreateDTO;
import com.dnobretech.jarvistradutorbackend.dto.SeriesDTO;
import com.dnobretech.jarvistradutorbackend.dto.SeriesUpdateDTO;
import com.dnobretech.jarvistradutorbackend.repository.AuthorRepository;
import com.dnobretech.jarvistradutorbackend.repository.SeriesRepository;
import com.dnobretech.jarvistradutorbackend.service.SeriesService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SeriesServiceImpl implements SeriesService {

    private final SeriesRepository repo;
    private final AuthorRepository authorRepo;

    @Override
    @Transactional
    public SeriesDTO create(SeriesCreateDTO dto) {
        Author author = authorRepo.findById(dto.authorId())
                .orElseThrow(() -> new EntityNotFoundException("author id="+dto.authorId()+" não encontrado"));

        String slug = (dto.slug() == null || dto.slug().isBlank())
                ? ensureUniqueSeriesSlug(makeSeriesBaseSlug(dto.name(), author.getName()))
                : dto.slug().trim().toLowerCase(Locale.ROOT);

        if (repo.existsBySlug(slug)) throw new IllegalArgumentException("slug já existente: " + slug);

        Series s = Series.builder()
                .name(dto.name())
                .slug(slug)
                .author(author)
                .description(dto.description())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        s = repo.save(s);
        return toDTO(s);
    }

    @Override
    @Transactional
    public SeriesDTO update(Long id, SeriesUpdateDTO dto) {
        Series s = repo.findById(id).orElseThrow(() -> new EntityNotFoundException("series id="+id+" não encontrada"));

        if (dto.name() != null) s.setName(dto.name());
        if (dto.authorId() != null) {
            Author a = authorRepo.findById(dto.authorId())
                    .orElseThrow(() -> new EntityNotFoundException("author id="+dto.authorId()+" não encontrado"));
            s.setAuthor(a);
        }
        if (dto.slug() != null && !dto.slug().isBlank()) {
            String newSlug = dto.slug().trim().toLowerCase(Locale.ROOT);
            if (!newSlug.equals(s.getSlug()) && repo.existsBySlug(newSlug)) {
                throw new IllegalArgumentException("slug já existente: " + newSlug);
            }
            s.setSlug(newSlug);
        }
        if (dto.description() != null) s.setDescription(dto.description());

        s.setUpdatedAt(Instant.now());
        s = repo.save(s);
        return toDTO(s);
    }

    @Override
    @Transactional(readOnly = true)
    public SeriesDTO get(Long id) {
        return repo.findById(id).map(this::toDTO)
                .orElseThrow(() -> new EntityNotFoundException("series id="+id+" não encontrada"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SeriesDTO> list() {
        return repo.findAll()
                .stream()
                .map(this::toDTO)
                .sorted(Comparator.comparing(SeriesDTO::name))
                .toList();
    }

    @Override
    @Transactional
    public void delete(Long id) {
        repo.deleteById(id);
    }

    @Override
    public List<SeriesDTO> findAllByAuthor(Long id) {
        return repo.findAllByAuthor_Id(id);
    }

    // ------ helpers ------
    private SeriesDTO toDTO(Series s) {
        return new SeriesDTO(
                s.getId(),
                s.getName(),
                s.getSlug(),
                s.getAuthor() != null ? s.getAuthor().getId() : null,
                s.getAuthor() != null ? s.getAuthor().getName() : null,
                s.getDescription(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }

    private static String makeSeriesBaseSlug(String seriesName, String authorName) {
        String s = slugify(seriesName);
        String a = slugify(authorName);
        return a.isBlank() ? s : (s + "--" + a);
    }

    private String ensureUniqueSeriesSlug(String base) {
        if (!repo.existsBySlug(base)) return base;
        int i = 2;
        while (repo.existsBySlug(base + "-" + i)) i++;
        return base + "-" + i;
    }

    private static String slugify(String x) {
        if (x == null) return "";
        String s = Normalizer.normalize(x, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+","-")
                .replaceAll("(^-|-$)","");
        return s;
    }
}
