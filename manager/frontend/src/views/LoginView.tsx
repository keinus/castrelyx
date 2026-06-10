import { LogIn } from 'lucide-react';
import { FormEvent, useState } from 'react';

type LoginViewProps = {
  onLogin: (payload: { username: string; password: string }) => Promise<void>;
};

export function LoginView({ onLogin }: LoginViewProps) {
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    await onLogin({ username, password });
  }

  return (
    <main className="auth-surface">
      <form className="auth-panel" onSubmit={submit}>
        <LogIn aria-hidden="true" />
        <h1>Castrelyx Manager</h1>
        <label>
          계정
          <input value={username} onChange={(event) => setUsername(event.target.value)} />
        </label>
        <label>
          비밀번호
          <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} />
        </label>
        <button type="submit">로그인</button>
      </form>
    </main>
  );
}
