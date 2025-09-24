package com.dnobretech.jarvistradutorbackend.repository;

import com.dnobretech.jarvistradutorbackend.domain.Glossary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GlossaryRepository extends JpaRepository<Glossary, Long> {
    Optional<Glossary> findBySrcIgnoreCase(String src);     // achar termo por src (case-insensitive)
}
