package com.dnobretech.jarvistradutorbackend.service;

import com.dnobretech.jarvistradutorbackend.domain.Book;
import com.dnobretech.jarvistradutorbackend.dto.BookCreateDTO;
import com.dnobretech.jarvistradutorbackend.dto.BookResponse;
import com.dnobretech.jarvistradutorbackend.dto.BookSummaryDTO;
import com.dnobretech.jarvistradutorbackend.dto.BookUpdateDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface BookService {
    Book create(BookCreateDTO dto);
    BookResponse update(Long id, BookUpdateDTO dto);
    Book get(Long id);
    List<Book> list();
    void delete(Long id);
    List<Book> listBySeries(Long seriesId);
    Page<BookSummaryDTO> listPaged(Pageable pageable);
    Page<BookSummaryDTO> listBySeriesPaged(Long seriesId, Pageable pageable);
}
