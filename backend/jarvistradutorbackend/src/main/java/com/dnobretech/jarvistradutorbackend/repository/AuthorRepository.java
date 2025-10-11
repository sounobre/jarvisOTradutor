// src/main/java/com/dnobretech/jarvistradutorbackend/repository/AuthorRepository.java
package com.dnobretech.jarvistradutorbackend.repository;

import com.dnobretech.jarvistradutorbackend.domain.Author;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthorRepository extends JpaRepository<Author, Long> {
    boolean existsBySlug(String slug);
    Optional<Author> findBySlug(String slug);
    Page<Author> findByNameContainingIgnoreCase(String name, Pageable pageable);
    boolean existsByNameIgnoreCaseAndCountryIgnoreCase(String name, String country);
}
