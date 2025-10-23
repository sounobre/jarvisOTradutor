// controller/ReviewController.java
package com.dnobretech.jarvistradutorbackend.controller;

import com.dnobretech.jarvistradutorbackend.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tm/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService review;

    // Aprovar / Rejeitar corpora
    @PostMapping("/corpora/approve")
    public ResponseEntity<?> approveCorpora(@RequestParam String reviewer, @RequestBody List<Long> ids) {
        int n = review.approveCorpora(ids, reviewer);
        return ResponseEntity.ok(java.util.Map.of("ok", true, "approved", n));
    }
    @PostMapping("/corpora/reject")
    public ResponseEntity<?> rejectCorpora(@RequestParam String reviewer, @RequestBody List<Long> ids) {
        int n = review.rejectCorpora(ids, reviewer);
        return ResponseEntity.ok(java.util.Map.of("ok", true, "rejected", n));
    }

    // Commit corpora approved -> TM
    @PostMapping("/corpora/commit")
    public ResponseEntity<?> commitCorpora() {
        int n = review.commitCorporaApproved();
        return ResponseEntity.ok(java.util.Map.of("ok", true, "tmUpserts", n));
    }

    // Aprovar / Rejeitar bookpairs
    @PostMapping("/bookpairs/approve")
    public ResponseEntity<?> approveBookpairs(@RequestParam String reviewer, @RequestBody List<Long> ids) {
        int n = review.approveBookpairs(ids, reviewer);
        return ResponseEntity.ok(java.util.Map.of("ok", true, "approved", n));
    }
    @PostMapping("/bookpairs/reject")
    public ResponseEntity<?> rejectBookpairs(@RequestParam String reviewer, @RequestBody List<Long> ids) {
        int n = review.rejectBookpairs(ids, reviewer);
        return ResponseEntity.ok(java.util.Map.of("ok", true, "rejected", n));
    }

    // Commit bookpairs approved -> TM + tm_occurrence + embeddings
    @PostMapping("/bookpairs/commit")
    public ResponseEntity<?> commitBookpairs() {
        int n = review.commitBookpairsApproved();
        return ResponseEntity.ok(java.util.Map.of("ok", true, "affected", n));
    }

    @PostMapping
    public Map<String,Object> run(
            @RequestParam(name="max", defaultValue="0.41") double max,
            @RequestParam(name="limit", defaultValue="10") int limit
    ) {
        int n = review.reviewPairs(max, limit);
        return Map.of("updated", n, "max", max, "limit", limit);
    }
}
