package com.dnobretech.jarvistradutorbackend.service.impl;

import com.dnobretech.jarvistradutorbackend.domain.Author;
import com.dnobretech.jarvistradutorbackend.domain.Book;
import com.dnobretech.jarvistradutorbackend.domain.Series;
import com.dnobretech.jarvistradutorbackend.dto.*;
import com.dnobretech.jarvistradutorbackend.repository.AuthorRepository;
import com.dnobretech.jarvistradutorbackend.repository.BookRepository;
import com.dnobretech.jarvistradutorbackend.repository.SeriesRepository;
import com.dnobretech.jarvistradutorbackend.service.BookService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookServiceImpl implements BookService {

    private final BookRepository repo;
    private final SeriesRepository seriesRepo;
    private final AuthorRepository authorRepository;

    @Override
    @Transactional
    public BookResponse create(BookCreateDTO dto) {

        Series series = null;
        if (dto.getSeriesId() != null) {
            series = seriesRepo.findById(dto.getSeriesId())
                    .orElseThrow(() -> new EntityNotFoundException("series id=" + dto.getSeriesId() + " não encontrada"));
        }

        Book b = Book.builder()
                .series(series)
                .volumeNumber(dto.getVolumeNumber())                // NumeroNaSerie
                .originalTitleEn(dto.getOriginalTitleEn())          // TituloOriginalEN
                .titlePtBr(dto.getTitlePtBr())                      // TituloPTBR
                .type(dto.getType())                                // Tipo (enum BookType)
                .yearOriginal(dto.getYearOriginal())                // AnoPublicacaoOriginal
                .yearBr(dto.getYearBr())                            // AnoPublicacaoBR
                .publisherBr(dto.getPublisherBr())                  // EditoraBR
                .translatorBr(dto.getTranslatorBr())                // TradutorBR
                .isbn13Br(dto.getIsbn13Br())                        // ISBN13_BR
                .downloaded(dto.getDownloaded())                    // Baixado
                .pathEn(dto.getPathEn())                            // Caminho versão em Inglês
                .pathPt(dto.getPathPt())                            // Caminho versão em Português
                .pairsImported(dto.getPairsImported())              // Já foi feita a importação dos pares
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        Book book = repo.save(b);
        return BookResponse.builder()
                .id(book.getId())
                .volumeNumber(String.valueOf(book.getVolumeNumber()))
                .originalTitleEn(book.getOriginalTitleEn())
                .titlePtBr(book.getTitlePtBr())
                .type(book.getType() != null ? book.getType().name() : null)
                .yearOriginal(book.getYearOriginal())
                .yearBr(book.getYearBr())
                .publisherBr(book.getPublisherBr())
                .translatorBr(book.getTranslatorBr())
                .isbn13Br(book.getIsbn13Br())
                .downloaded(book.getDownloaded())
                .pairsImported(book.getPairsImported())
                .seriesId(series.getId())
                .build();
    }

    @Override
    @Transactional
    public BookResponse update(Long id, BookUpdateDTO dto) {
        Book b = repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("book id=" + id + " não encontrado"));

        if (dto.getSeriesId() != null) {
            Series series = seriesRepo.findById(dto.getSeriesId())
                    .orElseThrow(() -> new EntityNotFoundException("series id=" + dto.getSeriesId() + " não encontrada"));
            b.setSeries(series);
        }
        if (dto.getTitlePtBr() != null) b.setTitlePtBr(dto.getTitlePtBr());
        if (dto.getOriginalTitleEn() != null) b.setOriginalTitleEn(dto.getOriginalTitleEn());
        if (dto.getVolumeNumber() != null) b.setVolumeNumber(dto.getVolumeNumber());
        if (dto.getType() != null) b.setType(dto.getType());
        if (dto.getYearOriginal() != null) b.setYearOriginal(dto.getYearOriginal());
        if (dto.getYearBr() != null) b.setYearBr(dto.getYearBr());
        if (dto.getPublisherBr() != null) b.setPublisherBr(dto.getPublisherBr());
        if (dto.getTranslatorBr() != null) b.setTranslatorBr(dto.getTranslatorBr());
        if (dto.getIsbn13Br() != null) b.setIsbn13Br(dto.getIsbn13Br());
        if (dto.getDownloaded() != null) b.setDownloaded(dto.getDownloaded());
        if (dto.getPairsImported() != null) b.setPairsImported(dto.getPairsImported());

        b.setUpdatedAt(Instant.now());
        repo.save(b);

        // recarrega com series+author para o DTO
        Book full = repo.findOneWithSeriesAndAuthor(b.getId())
                .orElseThrow(() -> new EntityNotFoundException("book id=" + id + " não encontrado após update"));
        return toBookResponse(full);
    }

    @Override
    @Transactional(readOnly = true)
    public Book get(Long id) {
        return repo.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("book id=" + id + " não encontrado"));
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

    @Override
    @Transactional(readOnly = true)
    public Page<BookSummaryDTO> listPaged(Pageable pageable) {
        return repo.findAll(pageable).map(this::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BookSummaryDTO> listBySeriesPaged(Long seriesId, Pageable pageable) {
        Series series = seriesRepo.findById(seriesId)
                .orElseThrow(() -> new EntityNotFoundException("series id=" + seriesId + " não encontrada"));
        Page<BookSummaryDTO> map = repo.findBySeries(series, pageable)
                .map(this::toSummary);
        return map;
    }

    private BookSummaryDTO toSummary(Book b) {
        Series s = b.getSeries();
        return new BookSummaryDTO(
                b.getId(),
                s != null ? s.getId() : null,
                s != null ? s.getName() : null,
                b.getVolumeNumber(),
                b.getOriginalTitleEn(),
                b.getTitlePtBr(),
                b.getType() != null ? b.getType().name() : null,
                b.getYearOriginal(),
                b.getYearBr(),
                b.getPublisherBr(),
                b.getTranslatorBr(),
                b.getIsbn13Br(),
                b.getDownloaded(),
                b.getPathEn(),
                b.getPathPt(),
                b.getPairsImported(),
                b.getCreatedAt(),
                b.getUpdatedAt()
        );
    }

    private static AuthorSummaryDTO toAuthorDTO(Author a) {
        return (a == null) ? null : new AuthorSummaryDTO(a.getId(), a.getName());
    }
    private static SeriesSummaryDTO toSeriesDTO(Series s) {
        if (s == null) return null;
        return new SeriesSummaryDTO(s.getId(), s.getName(), s.getSlug(), toAuthorDTO(s.getAuthor()));
    }
    private static BookResponse toBookResponse(Book b) {
        return new BookResponse(
                b.getId(),
                b.getVolumeNumber(),
                b.getOriginalTitleEn(),
                b.getTitlePtBr(),
                b.getType() != null ? b.getType().name() : null,
                b.getYearOriginal(),
                b.getYearBr(),
                b.getPublisherBr(),
                b.getTranslatorBr(),
                b.getIsbn13Br(),
                b.getDownloaded(),
                b.getPairsImported(),
                b.getSeries().getId()

        );
    }
}
