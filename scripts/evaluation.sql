WITH labeled_pairs AS (
  SELECT a.group_id, a.source_system left_system, a.source_record_id left_key,
         b.source_system right_system, b.source_record_id right_key
  FROM evaluation_labels a JOIN evaluation_labels b ON a.group_id = b.group_id
  WHERE (a.source_system, a.source_record_id) < (b.source_system, b.source_record_id)
    AND a.group_id LIKE 'duplicate-%'
), candidate_labels AS (
  SELECT mc.id, mc.automated_decision, ll.group_id left_group, rl.group_id right_group
  FROM match_candidates mc
  JOIN source_record_revisions lrev ON lrev.id = mc.left_revision_id
  JOIN source_records lsr ON lsr.id = lrev.source_record_id
  JOIN evaluation_labels ll ON ll.source_system = lsr.source_system AND ll.source_record_id = lsr.source_record_id
  JOIN source_record_revisions rrev ON rrev.id = mc.right_revision_id
  JOIN source_records rsr ON rsr.id = rrev.source_record_id
  JOIN evaluation_labels rl ON rl.source_system = rsr.source_system AND rl.source_record_id = rsr.source_record_id
), metrics AS (
  SELECT
    (SELECT count(*) FROM labeled_pairs) known_duplicate_pairs,
    (SELECT count(*) FROM labeled_pairs lp WHERE EXISTS (
      SELECT 1 FROM candidate_labels cl
      JOIN source_records lsr ON lsr.source_system = lp.left_system AND lsr.source_record_id = lp.left_key
      JOIN source_records rsr ON rsr.source_system = lp.right_system AND rsr.source_record_id = lp.right_key
      JOIN source_record_revisions lr ON lr.source_record_id = lsr.id
      JOIN source_record_revisions rr ON rr.source_record_id = rsr.id
      JOIN match_candidates mc ON mc.id = cl.id
      WHERE (mc.left_revision_id = lr.id AND mc.right_revision_id = rr.id)
         OR (mc.left_revision_id = rr.id AND mc.right_revision_id = lr.id))) duplicate_pairs_candidated,
    (SELECT count(*) FROM candidate_labels) candidate_count,
    (SELECT count(*) FROM candidate_labels WHERE automated_decision = 'AUTO_MATCH') auto_matches,
    (SELECT count(*) FROM candidate_labels WHERE automated_decision = 'AUTO_MATCH' AND left_group = right_group) true_auto_matches,
    (SELECT count(*) FROM candidate_labels WHERE automated_decision = 'REVIEW') review_count
)
SELECT known_duplicate_pairs,
       duplicate_pairs_candidated,
       round(duplicate_pairs_candidated::numeric / known_duplicate_pairs, 4) candidate_recall,
       candidate_count,
       auto_matches,
       true_auto_matches,
       CASE WHEN auto_matches = 0 THEN 1 ELSE round(true_auto_matches::numeric / auto_matches, 4) END auto_precision,
       round(review_count::numeric / greatest(candidate_count, 1), 4) review_rate,
       round(1 - candidate_count::numeric / (1000 * 999 / 2), 4) comparison_reduction
FROM metrics;

DO $$
DECLARE recall numeric; precision numeric;
BEGIN
  WITH labeled_pairs AS (
    SELECT a.group_id, a.source_system ls, a.source_record_id lk, b.source_system rs, b.source_record_id rk
    FROM evaluation_labels a JOIN evaluation_labels b ON a.group_id = b.group_id
    WHERE (a.source_system, a.source_record_id) < (b.source_system, b.source_record_id)
      AND a.group_id LIKE 'duplicate-%'
  ), scored AS (
    SELECT mc.automated_decision, ll.group_id lg, rl.group_id rg,
           lsr.source_system ls, lsr.source_record_id lk, rsr.source_system rs, rsr.source_record_id rk
    FROM match_candidates mc
    JOIN source_record_revisions lr ON lr.id = mc.left_revision_id JOIN source_records lsr ON lsr.id = lr.source_record_id
    JOIN source_record_revisions rr ON rr.id = mc.right_revision_id JOIN source_records rsr ON rsr.id = rr.source_record_id
    JOIN evaluation_labels ll ON ll.source_system = lsr.source_system AND ll.source_record_id = lsr.source_record_id
    JOIN evaluation_labels rl ON rl.source_system = rsr.source_system AND rl.source_record_id = rsr.source_record_id
  )
  SELECT count(*) FILTER (WHERE EXISTS (SELECT 1 FROM scored s WHERE
           (s.ls = lp.ls AND s.lk = lp.lk AND s.rs = lp.rs AND s.rk = lp.rk) OR
           (s.ls = lp.rs AND s.lk = lp.rk AND s.rs = lp.ls AND s.rk = lp.lk)))::numeric / count(*),
         COALESCE((SELECT count(*) FILTER (WHERE lg = rg)::numeric / NULLIF(count(*), 0)
                   FROM scored WHERE automated_decision = 'AUTO_MATCH'), 1)
  INTO recall, precision FROM labeled_pairs lp;
  IF recall < 0.95 THEN RAISE EXCEPTION 'candidate recall gate failed: %', recall; END IF;
  IF precision < 1 THEN RAISE EXCEPTION 'auto-match precision gate failed: %', precision; END IF;
END $$;
