export type ReviewSummary = {
  id: string
  type: string
  version: number
  score?: number
  automatedDecision?: string
  leftRevisionId: string
  rightRevisionId: string
  context?: { reason?: string }
}

export type ReviewPage = {
  items: ReviewSummary[]
  nextCursor?: string
}

export type ReviewDetail = ReviewSummary & {
  status: string
  blockingRules: string[]
  evidence: Record<string, Record<string, unknown>>
  leftPayload: Record<string, unknown>
  rightPayload: Record<string, unknown>
  leftGoldenId?: string
  rightGoldenId?: string
  decisionReason?: string
}

export type ReviewDecision = 'MATCH' | 'NO_MATCH'
