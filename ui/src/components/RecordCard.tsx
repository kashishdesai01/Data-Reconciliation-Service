type Props = {
  label: string
  payload: Record<string, unknown>
  goldenId?: string
}

export function RecordCard({ label, payload, goldenId }: Props) {
  return <article className="record">
    <div>
      <span>{label}</span>
      {goldenId && <small>Golden {goldenId.slice(0, 8)}</small>}
    </div>
    <h3>{String(payload.fullName || 'Unnamed record')}</h3>
    <dl>
      <dt>Email</dt><dd>{String(payload.email || '—')}</dd>
      <dt>Phone</dt><dd>{String(payload.phone || '—')}</dd>
      <dt>Address</dt>
      <dd>{String(payload.address || '—')} {String(payload.postalCode || '')}</dd>
    </dl>
  </article>
}
