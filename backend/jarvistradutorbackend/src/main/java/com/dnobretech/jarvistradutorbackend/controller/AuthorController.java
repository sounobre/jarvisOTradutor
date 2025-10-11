// src/main/java/com/dnobretech/jarvistradutorbackend/controller/AuthorController.java
package com.dnobretech.jarvistradutorbackend.controller;

import com.dnobretech.jarvistradutorbackend.dto.AuthorCreateDTO;
import com.dnobretech.jarvistradutorbackend.dto.AuthorDTO;
import com.dnobretech.jarvistradutorbackend.dto.AuthorFromSeriesSearchDto;
import com.dnobretech.jarvistradutorbackend.dto.AuthorUpdateDTO;
import com.dnobretech.jarvistradutorbackend.service.AuthorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/authors")
@RequiredArgsConstructor
public class AuthorController {

    private final AuthorService service;

    @PostMapping
    public AuthorDTO create(@RequestBody @Valid AuthorCreateDTO dto) {
        return service.create(dto);
    }

    @GetMapping("/{id}")
    public AuthorDTO get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/by-slug/{slug}")
    public AuthorDTO getBySlug(@PathVariable String slug) {
        return service.getBySlug(slug);
    }

    @GetMapping
    public Page<AuthorDTO> list(@RequestParam(required = false) String q, Pageable pageable) {
        return service.list(pageable, q);
    }

    @GetMapping("/list")
    public List<AuthorFromSeriesSearchDto> findAllSortedByName() {
        return service.findAllSortedByName();
    }

    @PatchMapping("/{id}")
    public AuthorDTO update(@PathVariable Long id, @RequestBody @Valid AuthorUpdateDTO dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
