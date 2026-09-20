package com.masterdata.reconciliation.service;

import com.masterdata.reconciliation.api.model.CursorPage;
import com.masterdata.reconciliation.api.model.DecisionRequest;
import com.masterdata.reconciliation.service.review.ReviewDecisionService;
import com.masterdata.reconciliation.service.review.ReviewQueryService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/** Application facade for review queue reads and reviewer decisions. */
@Service
public class ReviewService {
    private final ReviewQueryService queries;
    private final ReviewDecisionService decisions;

    public ReviewService(ReviewQueryService queries, ReviewDecisionService decisions) {
        this.queries = queries;
        this.decisions = decisions;
    }

    public CursorPage<Map<String, Object>> list(String cursor, String type, UUID runId, int limit) {
        return queries.list(cursor, type, runId, limit);
    }

    public Map<String, Object> detail(UUID reviewId) {
        return queries.detail(reviewId);
    }

    public Map<String, Object> decide(UUID reviewId, DecisionRequest request, String actor) {
        return decisions.decide(reviewId, request, actor);
    }
}
