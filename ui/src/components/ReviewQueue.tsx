import type { ReviewPage } from '../types/review'

type Props = {
  page: ReviewPage
  selectedId?: string
  onRefresh: () => void
  onSelect: (reviewId: string) => void
}

export function ReviewQueue({ page, selectedId, onRefresh, onSelect }: Props) {
  return <>
    <div className="aside-title">
      <h2>Review queue</h2>
      <button onClick={onRefresh}>Refresh</button>
    </div>
    <div className="queue">
      {page.items.length === 0 && <p className="empty">No pending items.</p>}
      {page.items.map(item =>
        <button
          key={item.id}
          className={`queue-item ${selectedId === item.id ? 'active' : ''}`}
          onClick={() => onSelect(item.id)}
        >
          <span className={`pill ${item.type.toLowerCase()}`}>
            {item.type.replaceAll('_', ' ')}
          </span>
          <strong>
            {item.score == null ? 'Conflict review' : `${(item.score * 100).toFixed(1)}% match score`}
          </strong>
          <small>{item.context?.reason || item.automatedDecision}</small>
        </button>,
      )}
    </div>
  </>
}
