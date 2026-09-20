import { useState, type FormEvent } from 'react'

type Props = {
  signedIn: boolean
  onSignIn: (username: string, password: string) => void
  onSignOut: () => void
}

export function ReviewerLogin({ signedIn, onSignIn, onSignOut }: Props) {
  const [username, setUsername] = useState('reviewer')
  const [password, setPassword] = useState('')

  function submit(event: FormEvent) {
    event.preventDefault()
    onSignIn(username, password)
    setPassword('')
  }

  return <form className="login" onSubmit={submit}>
    <h3>{signedIn ? 'Reviewer signed in' : 'Reviewer sign-in'}</h3>
    {!signedIn ? <>
      <input
        aria-label="Username"
        value={username}
        onChange={event => setUsername(event.target.value)}
        placeholder="Username"
      />
      <input
        aria-label="Password"
        type="password"
        value={password}
        onChange={event => setPassword(event.target.value)}
        placeholder="Password"
      />
      <button type="submit">Save for this tab</button>
    </> : <button type="button" onClick={onSignOut}>Sign out</button>}
  </form>
}
