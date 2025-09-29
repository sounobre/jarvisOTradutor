package com.dnobretech.jarvistradutorbackend.service;

import com.dnobretech.jarvistradutorbackend.domain.Book;
import com.dnobretech.jarvistradutorbackend.dto.BookCreateDTO;
import com.dnobretech.jarvistradutorbackend.dto.BookUpdateDTO;

import java.util.List;

public interface BookService {
    Book create(BookCreateDTO dto);
    Book update(Long id, BookUpdateDTO dto);
    Book get(Long id);
    List<Book> list();
    void delete(Long id);
    List<Book> listBySeries(Long seriesId);
}
