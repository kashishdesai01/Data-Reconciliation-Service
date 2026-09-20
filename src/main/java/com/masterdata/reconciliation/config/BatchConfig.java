package com.masterdata.reconciliation.config;

import com.masterdata.reconciliation.service.ReconciliationPipelineService;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;
import java.util.function.Consumer;

@Configuration
public class BatchConfig {
    @Bean("reconciliationJobLauncher")
    JobLauncher reconciliationJobLauncher(JobRepository jobRepository,
                                           @Qualifier("batchLauncherExecutor") TaskExecutor executor) throws Exception {
        var launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(executor);
        launcher.afterPropertiesSet();
        return launcher;
    }

    @Bean
    Job reconciliationJob(JobRepository repository, PlatformTransactionManager transactionManager,
                          ReconciliationPipelineService pipeline) {
        Step claim = step("claimRevisions", repository, transactionManager, pipeline::claim);
        Step normalize = step("normalize", repository, transactionManager, pipeline::normalize);
        Step candidates = step("generateCandidates", repository, transactionManager, pipeline::generateCandidates);
        Step score = step("scoreCandidates", repository, transactionManager, pipeline::scoreCandidates);
        Step resolve = step("resolveClusters", repository, transactionManager, pipeline::resolve);
        Step reviews = step("publishReviews", repository, transactionManager, pipeline::publishReviews);
        Step finish = step("finish", repository, transactionManager, pipeline::finish);
        return new JobBuilder("reconciliationJob", repository)
                .listener(new JobExecutionListener() {
                    @Override public void afterJob(JobExecution execution) {
                        if (execution.getStatus() == BatchStatus.FAILED) {
                            UUID runId = UUID.fromString(execution.getJobParameters().getString("runId"));
                            Throwable failure = execution.getAllFailureExceptions().stream().findFirst().orElse(
                                    new IllegalStateException("Batch job failed"));
                            pipeline.fail(runId, failure);
                        }
                    }
                })
                .start(claim).next(normalize).next(candidates).next(score).next(resolve).next(reviews).next(finish)
                .build();
    }

    private Step step(String name, JobRepository repository, PlatformTransactionManager transactionManager,
                      Consumer<UUID> action) {
        return new StepBuilder(name, repository).tasklet((contribution, context) -> {
            UUID runId = UUID.fromString(context.getStepContext().getJobParameters().get("runId").toString());
            action.accept(runId);
            return RepeatStatus.FINISHED;
        }, transactionManager).build();
    }
}

