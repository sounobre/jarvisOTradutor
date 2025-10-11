// src/main/java/com/dnobretech/jarvistradutorbackend/service/impl/AuthorServiceImpl.java
package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.domain.Author;
import com.dnobretech.jarvistradutorbackend.dto.AuthorCreateDTO;
import com.dnobretech.jarvistradutorbackend.dto.AuthorDTO;
import com.dnobretech.jarvistradutorbackend.dto.AuthorFromSeriesSearchDto;
import com.dnobretech.jarvistradutorbackend.dto.AuthorUpdateDTO;
import com.dnobretech.jarvistradutorbackend.repository.AuthorRepository;
import com.dnobretech.jarvistradutorbackend.service.AuthorService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthorServiceImpl implements AuthorService {

    private final AuthorRepository repo;

    @Override
    @Transactional
    public AuthorDTO create(AuthorCreateDTO dto) {
        String slug = (dto.slug() == null || dto.slug().isBlank())
                ? slugify(dto.name())
                : dto.slug().trim().toLowerCase(Locale.ROOT);

        if (repo.existsBySlug(slug)) {
            throw new IllegalArgumentException("slug já existente: " + slug);
        }

        // opcional: impedir duplicata por name+country
        if (dto.country() != null && repo.existsByNameIgnoreCaseAndCountryIgnoreCase(dto.name(), dto.country())) {
            throw new IllegalArgumentException("autor já existe para este país: " + dto.name() + " / " + dto.country());
        }

        Instant now = Instant.now();
        Author a = Author.builder()
                .name(dto.name())
                .slug(slug)
                .country(trimOrNull(dto.country()))
                .activePeriod(trimOrNull(dto.activePeriod()))
                .birthYear(dto.birthYear())
                .deathYear(dto.deathYear())
                .notes(trimOrNull(dto.notes()))
                .createdAt(now)
                .updatedAt(now)
                .build();

        a = repo.save(a);
        return toDTO(a);
    }

    @Override
    @Transactional
    public AuthorDTO update(Long id, AuthorUpdateDTO dto) {
        Author a = repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("author id=" + id + " não encontrado"));

        if (dto.name() != null) a.setName(dto.name());
        if (dto.slug() != null) {
            String newSlug = dto.slug().trim().toLowerCase(Locale.ROOT);
            if (!newSlug.equals(a.getSlug()) && repo.existsBySlug(newSlug)) {
                throw new IllegalArgumentException("slug já existente: " + newSlug);
            }
            a.setSlug(newSlug);
        }
        if (dto.country() != null) a.setCountry(trimOrNull(dto.country()));
        if (dto.activePeriod() != null) a.setActivePeriod(trimOrNull(dto.activePeriod()));
        if (dto.birthYear() != null) a.setBirthYear(dto.birthYear());
        if (dto.deathYear() != null) a.setDeathYear(dto.deathYear());
        if (dto.notes() != null) a.setNotes(trimOrNull(dto.notes()));

        a.setUpdatedAt(Instant.now());
        a = repo.save(a);
        return toDTO(a);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthorDTO get(Long id) {
        Author a = repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("author id=" + id + " não encontrado"));
        return toDTO(a);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthorDTO getBySlug(String slug) {
        Author a = repo.findBySlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("author slug=" + slug + " não encontrado"));
        return toDTO(a);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuthorDTO> list(Pageable pageable, String q) {
        if (q != null && !q.isBlank()) {
            return repo.findByNameContainingIgnoreCase(q.trim(), pageable)
                    .map(this::toDTO);
        }
        return repo.findAll(pageable).map(this::toDTO);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) {
            throw new EntityNotFoundException("author id=" + id + " não encontrado");
        }
        repo.deleteById(id);
    }

    @Override
    public List<AuthorFromSeriesSearchDto> findAllSortedByName() {
        return repo.findAll()
                .stream()
                .map(author ->
                        AuthorFromSeriesSearchDto.builder()
                                .id(author.getId())
                                .name(author.getName())
                                .build()
                )
                .sorted(Comparator.comparing(AuthorFromSeriesSearchDto::getName))
                .toList();
    }

    // -------- helpers ----------
    private AuthorDTO toDTO(Author a) {
        return new AuthorDTO(
                a.getId(), a.getName(), a.getSlug(), a.getCountry(), a.getActivePeriod(),
                a.getBirthYear(), a.getDeathYear(), a.getNotes(), a.getCreatedAt(), a.getUpdatedAt()
        );
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String slugify(String input) {
        if (input == null) return null;
        String nowh = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return nowh.isBlank() ? "autor" : nowh;
    }
}
