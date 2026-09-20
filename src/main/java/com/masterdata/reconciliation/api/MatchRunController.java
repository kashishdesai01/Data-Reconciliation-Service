package com.masterdata.reconciliation.api;

import com.masterdata.reconciliation.api.model.MatchRunResponse;
import com.masterdata.reconciliation.service.ReconciliationPipelineService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/match-runs")
public class MatchRunController {
    private final ReconciliationPipelineService pipeline;
    private final JobLauncher launcher;
    private final Job job;

    public MatchRunController(ReconciliationPipelineService pipeline,
                              @Qualifier("reconciliationJobLauncher") JobLauncher launcher,
                              Job reconciliationJob) {
        this.pipeline = pipeline;
        this.launcher = launcher;
        this.job = reconciliationJob;
    }

    @PostMapping
    ResponseEntity<MatchRunResponse> start() throws Exception {
        UUID runId = pipeline.createRun();
        launch(runId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new MatchRunResponse(runId, "STARTING"));
    }

    @PostMapping("/{id}/restart")
    ResponseEntity<MatchRunResponse> restart(@PathVariable UUID id) throws Exception {
        Map<String, Object> status = pipeline.runStatus(id);
        if (!"FAILED".equals(status.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "RUN_NOT_FAILED", "Only failed runs can be restarted");
        }
        launch(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new MatchRunResponse(id, "STARTING"));
    }

    @GetMapping("/{id}")
    Map<String, Object> status(@PathVariable UUID id) { return pipeline.runStatus(id); }

    private void launch(UUID runId) throws Exception {
        launcher.run(job, new JobParametersBuilder().addString("runId", runId.toString(), true).toJobParameters());
    }
}
