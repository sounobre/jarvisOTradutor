// BookpairInboxController.java
package com.dnobretech.jarvistradutorbackend.controller;

import com.dnobretech.jarvistradutorbackend.service.BookpairInboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequiredArgsConstructor
@RequestMapping("/tm/inbox/bookpair")
public class BookpairInboxController {

    private static final Set<String> ALLOWED_STATUS = Set.of("pending","approved","rejected");

    private final BookpairInboxService service;

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "pending") String status,
            @RequestParam(required = false) Long seriesId,
            @RequestParam(required = false) Long bookId,
            @RequestParam(required = false) String sourceTag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        if (!ALLOWED_STATUS.contains(status)) {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "ok", false,
                    "error", "status inválido. Use pending|approved|rejected"
            ));
        }
        int p = Math.max(0, page);
        int s = Math.max(1, Math.min(500, size));

        var items = service.list(status, seriesId, bookId, sourceTag, p, s);
        long total = service.count(status, seriesId, bookId, sourceTag);
        long totalPages = (total + s - 1) / s;

        return ResponseEntity.ok(java.util.Map.of(
                "ok", true,
                "status", status,
                "total", total,
                "totalPages", totalPages,
                "page", p,
                "size", s,
                "items", items
        ));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable long id,
                                     @RequestParam(required = false) String reviewer) {
        int n = service.approve(id, reviewer);
        return ResponseEntity.ok(java.util.Map.of("ok", true, "updated", n));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable long id,
                                    @RequestParam(required = false) String reviewer,
                                    @RequestParam(required = false) String reason) {
        int n = service.reject(id, reviewer, reason);
        return ResponseEntity.ok(java.util.Map.of("ok", true, "updated", n));
    }

    // OPCIONAL: versão com body JSON (melhor pro frontend)
    public record ReviewBody(String reviewer, String reason) {}
    @PostMapping("/{id}/reject-json")
    public ResponseEntity<?> rejectJson(@PathVariable long id, @RequestBody ReviewBody body) {
        int n = service.reject(id, body == null ? null : body.reviewer(), body == null ? null : body.reason());
        return ResponseEntity.ok(java.util.Map.of("ok", true, "updated", n));
    }

    @PostMapping("/consolidate")
    public ResponseEntity<?> consolidate() {
        int effects = service.consolidateApproved();
        return ResponseEntity.ok(java.util.Map.of("ok", true, "effects", effects));
    }
}
