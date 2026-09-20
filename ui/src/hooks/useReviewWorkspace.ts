import { useCallback, useEffect, useState } from 'react'
import { fetchReview, fetchReviewQueue, submitReviewDecision } from '../api/reviewApi'
import type { ReviewDecision, ReviewDetail, ReviewPage } from '../types/review'

const EMPTY_PAGE: ReviewPage = { items: [] }

export function useReviewWorkspace() {
  const [page, setPage] = useState<ReviewPage>(EMPTY_PAGE)
  const [selected, setSelected] = useState<ReviewDetail | null>(null)
  const [credentials, setCredentials] = useState(
    () => sessionStorage.getItem('reviewCredentials') || '',
  )
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const refresh = useCallback(async () => {
    if (!credentials) {
      setPage(EMPTY_PAGE)
      return
    }
    try {
      setPage(await fetchReviewQueue(credentials))
      setError('')
    } catch (failure) {
      setError(message(failure, 'Unable to load review queue'))
    }
  }, [credentials])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const selectReview = useCallback(async (reviewId: string) => {
    try {
      setSelected(await fetchReview(reviewId, credentials))
      setError('')
    } catch (failure) {
      setError(message(failure, 'Unable to load review evidence'))
    }
  }, [credentials])

  const signIn = useCallback((username: string, password: string) => {
    const encoded = btoa(`${username}:${password}`)
    sessionStorage.setItem('reviewCredentials', encoded)
    setCredentials(encoded)
  }, [])

  const signOut = useCallback(() => {
    sessionStorage.removeItem('reviewCredentials')
    setCredentials('')
    setSelected(null)
    setPage(EMPTY_PAGE)
    setError('')
  }, [])

  const decide = useCallback(async (decision: ReviewDecision, reason: string) => {
    if (!selected) return
    if (!credentials) {
      setError('Sign in before submitting a review decision.')
      return
    }
    setBusy(true)
    setError('')
    try {
      await submitReviewDecision(selected, decision, reason, credentials)
      setSelected(null)
      await refresh()
    } catch (failure) {
      setError(message(failure, 'Decision failed'))
    } finally {
      setBusy(false)
    }
  }, [credentials, refresh, selected])

  return {
    page,
    selected,
    signedIn: Boolean(credentials),
    error,
    busy,
    refresh,
    selectReview,
    signIn,
    signOut,
    decide,
  }
}

function message(failure: unknown, fallback: string) {
  return failure instanceof Error ? failure.message : fallback
}
