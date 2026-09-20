import type { ReviewDetail } from '../types/review'

const FIELD_ORDER = ['name', 'email', 'phone', 'address']

export function ReviewEvidence({ review }: { review: ReviewDetail }) {
  return <>
    <h3>Field-level explanation</h3>
    <div className="evidence">
      {FIELD_ORDER.map(field => {
        const evidence = review.evidence?.[field] || {}
        return <div className="evidence-row" key={field}>
          <strong>{field}</strong>
          <span>{String(evidence.leftNormalized ?? 'missing')}</span>
          <span>{String(evidence.rightNormalized ?? 'missing')}</span>
          <b>
            {evidence.similarity == null
              ? 'n/a'
              : `${(Number(evidence.similarity) * 100).toFixed(1)}%`}
          </b>
        </div>
      })}
    </div>
  </>
}
