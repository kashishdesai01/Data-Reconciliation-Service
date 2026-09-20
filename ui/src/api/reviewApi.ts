import type { ReviewDecision, ReviewDetail, ReviewPage } from '../types/review'

function authorization(credentials: string) {
  return { Authorization: `Basic ${credentials}` }
}

async function requireSuccess(response: Response, fallback: string) {
  if (response.ok) return response
  const problem = await response.json().catch(() => ({})) as { detail?: string }
  throw new Error(problem.detail || `${fallback} (${response.status})`)
}

export async function fetchReviewQueue(credentials: string): Promise<ReviewPage> {
  const response = await fetch('/api/review-queue?limit=50', {
    headers: authorization(credentials),
  })
  await requireSuccess(response, 'Unable to load review queue')
  return response.json() as Promise<ReviewPage>
}

export async function fetchReview(reviewId: string, credentials: string): Promise<ReviewDetail> {
  const response = await fetch(`/api/review-queue/${reviewId}`, {
    headers: authorization(credentials),
  })
  await requireSuccess(response, 'Unable to load review evidence')
  return response.json() as Promise<ReviewDetail>
}

export async function submitReviewDecision(
  review: ReviewDetail,
  decision: ReviewDecision,
  reason: string,
  credentials: string,
): Promise<void> {
  const response = await fetch(`/api/review-queue/${review.id}/decisions`, {
    method: 'POST',
    headers: {
      ...authorization(credentials),
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      decision,
      expectedVersion: review.version,
      idempotencyKey: crypto.randomUUID(),
      reason: reason || undefined,
    }),
  })
  await requireSuccess(response, 'Decision failed')
}
