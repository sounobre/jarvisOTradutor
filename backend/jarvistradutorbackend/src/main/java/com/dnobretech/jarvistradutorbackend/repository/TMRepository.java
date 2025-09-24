package com.dnobretech.jarvistradutorbackend.repository;


import com.dnobretech.jarvistradutorbackend.domain.TM;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TMRepository extends JpaRepository<TM, Long> {
    // consultas sofisticadas (trgm/ANN) serão via JdbcTemplate no service
}