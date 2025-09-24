package com.dnobretech.jarvistradutorbackend.repository;

import com.dnobretech.jarvistradutorbackend.domain.ImportCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportCheckpointRepository extends JpaRepository<ImportCheckpoint, String> {
    // fileKey = ID
}
