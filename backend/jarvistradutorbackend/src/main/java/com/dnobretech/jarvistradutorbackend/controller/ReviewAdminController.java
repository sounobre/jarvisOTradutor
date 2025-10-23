package com.dnobretech.jarvistradutorbackend.controller;

import com.dnobretech.jarvistradutorbackend.service.PairAutoReviewer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/review")
public class ReviewAdminController {

    private final PairAutoReviewer reviewer;
    private final JdbcTemplate jdbc;

    @PostMapping
    public String run(@RequestParam double min,
                      @RequestParam double max,
                      @RequestParam(defaultValue = "200") int limit,
                      @RequestParam(defaultValue = "5") int k,
                      @RequestParam(defaultValue = "auto") String mode) {
        int n = reviewer.reviewBatch(min, max, limit, k, mode);
        return "Revisados=" + n + " (range=" + min + ".." + max + ", limit=" + limit + ", k=" + k + ", mode=" + mode + ")";
    }

    @PostMapping("/approve")
    public String approve(@RequestParam long id, @RequestParam int index) throws JsonProcessingException {
        // pega candidates e escolhe index
        var row = jdbc.queryForObject(
                "SELECT review_candidates FROM tm_bookpair_inbox WHERE id = ?",
                (rs, i) -> rs.getString(1), id);
        if (row == null) return "not found";
        List<String> cands = new ObjectMapper().readValue(row, new TypeReference<>() {});
        if (index < 0 || index >= cands.size()) return "bad index";

        String chosen = cands.get(index);
        int n = jdbc.update("""
      UPDATE tm_bookpair_inbox
         SET review_suggestion = ?, review_best_index = ?
       WHERE id = ?
    """, chosen, index, id);
        return "updated=" + n;
    }


}
