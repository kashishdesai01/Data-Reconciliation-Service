import { useState } from 'react'
import type { ReviewDecision, ReviewDetail } from '../types/review'
import { RecordCard } from './RecordCard'
import { ReviewEvidence } from './ReviewEvidence'

type Props = {
  review: ReviewDetail | null
  busy: boolean
  onDecide: (decision: ReviewDecision, reason: string) => void
}

export function ReviewDetailPanel({ review, busy, onDecide }: Props) {
  const [reason, setReason] = useState('')

  if (!review) {
    return <section className="detail">
      <div className="placeholder">
        <span>↗</span>
        <h2>Select a review item</h2>
        <p>Inspect field evidence, current membership, and blocking rules before deciding.</p>
      </div>
    </section>
  }

  return <section className="detail">
    <div className="detail-head">
      <div>
        <span className={`pill ${review.type.toLowerCase()}`}>
          {review.type.replaceAll('_', ' ')}
        </span>
        <h2>Candidate evidence</h2>
      </div>
      <div className="score">
        {review.score == null ? '—' : `${(review.score * 100).toFixed(1)}%`}
        <small>weighted score</small>
      </div>
    </div>
    <p className="reason">{review.decisionReason || review.context?.reason}</p>
    <div className="records">
      <RecordCard label="Left record" payload={review.leftPayload} goldenId={review.leftGoldenId} />
      <RecordCard label="Right record" payload={review.rightPayload} goldenId={review.rightGoldenId} />
    </div>
    <ReviewEvidence review={review} />
    <div className="blocking">
      <strong>Candidate blocks</strong>
      {(review.blockingRules || []).map(rule => <code key={rule}>{rule}</code>)}
    </div>
    <div className="actions">
      <input
        value={reason}
        onChange={event => setReason(event.target.value)}
        placeholder="Decision reason (optional)"
      />
      <button className="reject" disabled={busy} onClick={() => onDecide('NO_MATCH', reason)}>
        Not a match
      </button>
      <button className="accept" disabled={busy} onClick={() => onDecide('MATCH', reason)}>
        Confirm match
      </button>
    </div>
  </section>
}
