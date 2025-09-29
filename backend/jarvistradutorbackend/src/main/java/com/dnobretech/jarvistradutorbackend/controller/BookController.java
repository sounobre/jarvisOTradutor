package com.dnobretech.jarvistradutorbackend.controller;

import com.dnobretech.jarvistradutorbackend.domain.Book;
import com.dnobretech.jarvistradutorbackend.dto.BookCreateDTO;
import com.dnobretech.jarvistradutorbackend.dto.BookUpdateDTO;
import com.dnobretech.jarvistradutorbackend.service.BookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookController {

    private final BookService service;

    @PostMapping
    public Book create(@RequestBody @Valid BookCreateDTO dto) {
        return service.create(dto);
    }

    @GetMapping("/{id}")
    public Book get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping
    public List<Book> list(@RequestParam(required = false) Long seriesId) {
        if (seriesId != null)
            return service.listBySeries(seriesId);
        return service.list();
    }

    @PatchMapping("/{id}")
    public Book update(@PathVariable Long id, @RequestBody @Valid BookUpdateDTO dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
