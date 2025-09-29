package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.domain.Book;
import com.dnobretech.jarvistradutorbackend.domain.Series;
import com.dnobretech.jarvistradutorbackend.dto.BookCreateDTO;
import com.dnobretech.jarvistradutorbackend.dto.BookUpdateDTO;
import com.dnobretech.jarvistradutorbackend.repository.BookRepository;
import com.dnobretech.jarvistradutorbackend.repository.SeriesRepository;
import com.dnobretech.jarvistradutorbackend.service.BookService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookServiceImpl implements BookService {

    private final BookRepository repo;
    private final SeriesRepository seriesRepo;

    @Override
    @Transactional
    public Book create(BookCreateDTO dto) {
        Series series = null;
        if (dto.getSeriesId() != null) {
            series = seriesRepo.findById(dto.getSeriesId())
                    .orElseThrow(() -> new EntityNotFoundException("series id=" + dto.getSeriesId() + " não encontrada"));
        }
        Book b = Book.builder()
                .series(series)
                .title(dto.getTitle())
                .volumeNumber(dto.getVolumeNumber())
                .originalTitle(dto.getOriginalTitle())
                .language(dto.getLanguage())
                .isbn(dto.getIsbn())
                .publisher(dto.getPublisher())
                .publishedAt(dto.getPublishedAt())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return repo.save(b);
    }

    @Override
    @Transactional
    public Book update(Long id, BookUpdateDTO dto) {
        Book b = repo.findById(id).orElseThrow(() -> new EntityNotFoundException("book id=" + id + " não encontrado"));
        if (dto.getSeriesId() != null) {
            Series series = seriesRepo.findById(dto.getSeriesId())
                    .orElseThrow(() -> new EntityNotFoundException("series id=" + dto.getSeriesId() + " não encontrada"));
            b.setSeries(series);
        }
        if (dto.getTitle() != null) b.setTitle(dto.getTitle());
        if (dto.getVolumeNumber() != null) b.setVolumeNumber(dto.getVolumeNumber());
        if (dto.getOriginalTitle() != null) b.setOriginalTitle(dto.getOriginalTitle());
        if (dto.getLanguage() != null) b.setLanguage(dto.getLanguage());
        if (dto.getIsbn() != null) b.setIsbn(dto.getIsbn());
        if (dto.getPublisher() != null) b.setPublisher(dto.getPublisher());
        if (dto.getPublishedAt() != null) b.setPublishedAt(dto.getPublishedAt());
        b.setUpdatedAt(Instant.now());
        return repo.save(b);
    }

    @Override
    @Transactional(readOnly = true)
    public Book get(Long id) {
        try {
            Book book = repo.findById(id).get(); //.orElseThrow(() -> new EntityNotFoundException("book id=" + id + " não encontrado"));
            return book;
        } catch (Exception e) {
            e.getMessage();
        }
           return null;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Book> list() {
        return repo.findAll();
    }

    @Override
    @Transactional
    public void delete(Long id) {
        repo.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Book> listBySeries(Long seriesId) {
        Series series = seriesRepo.findById(seriesId)
                .orElseThrow(() -> new EntityNotFoundException("series id=" + seriesId + " não encontrada"));
        return repo.findBySeries(series);
    }
}
