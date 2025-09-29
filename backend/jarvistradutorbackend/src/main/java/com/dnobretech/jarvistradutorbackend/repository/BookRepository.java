package com.dnobretech.jarvistradutorbackend.repository;

import com.dnobretech.jarvistradutorbackend.domain.Book;
import com.dnobretech.jarvistradutorbackend.domain.Series;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookRepository extends JpaRepository<Book, Long> {
    List<Book> findBySeries(Series series);
}
