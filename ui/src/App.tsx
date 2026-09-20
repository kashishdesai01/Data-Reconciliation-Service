import { ReviewDetailPanel } from './components/ReviewDetailPanel'
import { ReviewerLogin } from './components/ReviewerLogin'
import { ReviewQueue } from './components/ReviewQueue'
import { useReviewWorkspace } from './hooks/useReviewWorkspace'

export default function App() {
  const workspace = useReviewWorkspace()

  return <div className="shell">
    <header>
      <div>
        <p className="eyebrow">Master data</p>
        <h1>Reconciliation review</h1>
      </div>
      <div className="status">
        <span className="dot" /> {workspace.page.items.length} pending
      </div>
    </header>
    {workspace.error && <div className="error" role="alert">{workspace.error}</div>}
    <main>
      <aside>
        <ReviewQueue
          page={workspace.page}
          selectedId={workspace.selected?.id}
          onRefresh={() => void workspace.refresh()}
          onSelect={reviewId => void workspace.selectReview(reviewId)}
        />
        <ReviewerLogin
          signedIn={workspace.signedIn}
          onSignIn={workspace.signIn}
          onSignOut={workspace.signOut}
        />
      </aside>
      <ReviewDetailPanel
        key={workspace.selected?.id || 'empty'}
        review={workspace.selected}
        busy={workspace.busy}
        onDecide={(decision, reason) => void workspace.decide(decision, reason)}
      />
    </main>
  </div>
}
