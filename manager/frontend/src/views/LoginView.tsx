import { LogIn } from 'lucide-react';
import { FormEvent, useState } from 'react';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { Input } from '../components/ui/input';

type LoginViewProps = {
  onLogin: (payload: { username: string; password: string }) => Promise<void>;
  errorMessage?: string | null;
  disabled?: boolean;
};

export function LoginView({ onLogin, errorMessage, disabled = false }: LoginViewProps) {
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (disabled) {
      return;
    }
    await onLogin({ username, password });
  }

  return (
    <main className="grid min-h-screen place-items-center bg-muted/30 px-4 py-6">
      <Card className="w-full max-w-sm border-border/70">
        <CardHeader>
          <CardTitle className="text-lg">Castrelyx Manager</CardTitle>
          <CardDescription>콘솔에 접근하려면 계정 정보를 입력하세요.</CardDescription>
        </CardHeader>
        <CardContent>
          <form className="grid gap-3" onSubmit={submit}>
            <label className="grid gap-1 text-sm" htmlFor="username">
              계정
              <Input
                id="username"
                value={username}
                autoComplete="username"
                disabled={disabled}
                onChange={(event) => setUsername(event.target.value)}
              />
            </label>
            <label className="grid gap-1 text-sm" htmlFor="password">
              비밀번호
              <Input
                id="password"
                type="password"
                value={password}
                autoComplete="current-password"
                disabled={disabled}
                onChange={(event) => setPassword(event.target.value)}
              />
            </label>
            {errorMessage ? <p className="rounded-md border border-destructive/30 bg-destructive/10 p-2 text-xs text-destructive">{errorMessage}</p> : null}
            <Button className="w-full" type="submit" disabled={disabled}>
              <LogIn data-icon="inline-start" />
              {disabled ? '로그인 중...' : '로그인'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </main>
  );
}
