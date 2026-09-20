package com.masterdata.reconciliation.api;

import com.masterdata.reconciliation.api.model.IngestBatchRequest;
import com.masterdata.reconciliation.api.model.IngestBatchResponse;
import com.masterdata.reconciliation.service.IngestionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingest")
public class IngestionController {
    private final IngestionService service;
    public IngestionController(IngestionService service) { this.service = service; }

    @PostMapping("/{sourceSystem}")
    IngestBatchResponse ingest(@PathVariable String sourceSystem, @Valid @RequestBody IngestBatchRequest request) {
        return service.ingest(sourceSystem, request);
    }
}
