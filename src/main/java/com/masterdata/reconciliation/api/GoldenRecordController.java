package com.masterdata.reconciliation.api;

import com.masterdata.reconciliation.api.model.SplitRequest;
import com.masterdata.reconciliation.service.GoldenRecordService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/golden-records")
public class GoldenRecordController {
    private final GoldenRecordService service;
    public GoldenRecordController(GoldenRecordService service) { this.service = service; }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable UUID id) { return service.get(id); }

    @PostMapping("/{id}/split")
    Map<String, Object> split(@PathVariable UUID id, @Valid @RequestBody SplitRequest request, Principal principal) {
        return service.split(id, request, principal.getName());
    }
}
