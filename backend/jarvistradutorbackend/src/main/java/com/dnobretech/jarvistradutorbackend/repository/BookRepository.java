package com.dnobretech.jarvistradutorbackend.repository;

import com.dnobretech.jarvistradutorbackend.domain.Book;
import com.dnobretech.jarvistradutorbackend.domain.Series;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface BookRepository extends JpaRepository<Book, Long> {
    List<Book> findBySeries(Series series);
    Optional<Book> findByIsbn13Br(String isbn13Br);
    Optional<Book> findBySeriesAndVolumeNumber(Series series, String volumeNumber);
    Optional<Book> findBySeriesAndOriginalTitleEnIgnoreCase(Series s, String title);
    Page<Book> findAll(Pageable pageable);
    Page<Book> findBySeries(Series series, Pageable pageable);

    @Query("""
   select b from Book b
   left join fetch b.series s
   left join fetch s.author a
   where b.id = :id
""")
    Optional<Book> findOneWithSeriesAndAuthor(@Param("id") Long id);
}
