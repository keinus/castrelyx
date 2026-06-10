import { ShieldCheck } from 'lucide-react';
import { FormEvent, useState } from 'react';

type SetupViewProps = {
  onCreate: (payload: { username: string; password: string; displayName?: string }) => Promise<void>;
};

export function SetupView({ onCreate }: SetupViewProps) {
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('Administrator');

  async function submit(event: FormEvent) {
    event.preventDefault();
    await onCreate({ username, password, displayName });
  }

  return (
    <main className="auth-surface">
      <form className="auth-panel" onSubmit={submit}>
        <ShieldCheck aria-hidden="true" />
        <h1>Castrelyx Manager</h1>
        <label>
          관리자 계정
          <input value={username} onChange={(event) => setUsername(event.target.value)} />
        </label>
        <label>
          표시 이름
          <input value={displayName} onChange={(event) => setDisplayName(event.target.value)} />
        </label>
        <label>
          비밀번호
          <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} />
        </label>
        <button type="submit">초기 설정 완료</button>
      </form>
    </main>
  );
}
