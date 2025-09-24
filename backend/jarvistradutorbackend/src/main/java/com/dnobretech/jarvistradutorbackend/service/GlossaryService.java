package com.dnobretech.jarvistradutorbackend.service;

import com.dnobretech.jarvistradutorbackend.domain.Glossary;

import java.util.List;

public interface GlossaryService {
    int bulkUpsert(List<Glossary> items);

}
