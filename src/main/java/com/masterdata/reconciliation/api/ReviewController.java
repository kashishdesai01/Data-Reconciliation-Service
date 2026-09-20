package com.masterdata.reconciliation.api;

import com.masterdata.reconciliation.api.model.CursorPage;
import com.masterdata.reconciliation.api.model.DecisionRequest;
import com.masterdata.reconciliation.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/review-queue")
public class ReviewController {
    private final ReviewService service;
    public ReviewController(ReviewService service) { this.service = service; }

    @GetMapping
    CursorPage<Map<String, Object>> list(@RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) String type,
                                         @RequestParam(required = false) UUID run,
                                         @RequestParam(defaultValue = "25") int limit) {
        return service.list(cursor, type, run, limit);
    }

    @GetMapping("/{id}")
    Map<String, Object> detail(@PathVariable UUID id) { return service.detail(id); }

    @PostMapping("/{id}/decisions")
    Map<String, Object> decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request, Principal principal) {
        return service.decide(id, request, principal.getName());
    }
}
