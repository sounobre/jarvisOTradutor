// src/main/java/com/dnobretech/jarvistradutorbackend/controller/SeriesController.java
package com.dnobretech.jarvistradutorbackend.controller;

import com.dnobretech.jarvistradutorbackend.dto.SeriesCreateDTO;
import com.dnobretech.jarvistradutorbackend.dto.SeriesDTO;
import com.dnobretech.jarvistradutorbackend.dto.SeriesUpdateDTO;
import com.dnobretech.jarvistradutorbackend.service.SeriesService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/series")
@RequiredArgsConstructor
public class SeriesController {

    private final SeriesService service;

    @PostMapping
    public SeriesDTO create(@RequestBody @Valid SeriesCreateDTO dto) {
        return service.create(dto);
    }

    @GetMapping("/{id}")
    public SeriesDTO get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping
    public List<SeriesDTO> list() {
        return service.list();
    }

    @GetMapping("/author/{id}")
    public List<SeriesDTO> listByAuthorId(@PathVariable Long id) {
        return service.findAllByAuthor(id);
    }

    @PutMapping("/{id}")
    public SeriesDTO update(@PathVariable Long id, @RequestBody @Valid SeriesUpdateDTO dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
