// src/main/java/com/dnobretech/jarvistradutorbackend/repository/SeriesRepository.java
package com.dnobretech.jarvistradutorbackend.repository;

import com.dnobretech.jarvistradutorbackend.domain.Author;
import com.dnobretech.jarvistradutorbackend.domain.Series;
import com.dnobretech.jarvistradutorbackend.dto.SeriesDTO;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SeriesRepository extends JpaRepository<Series, Long> {
    boolean existsBySlug(String slug);

    Optional<Series> findByNameIgnoreCaseAndAuthor(String name, Author author);
    Optional<Series> findByNameIgnoreCaseAndAuthor_Id(String name, Long authorId);

    List<SeriesDTO> findAllByAuthor_Id(Long id);
}
