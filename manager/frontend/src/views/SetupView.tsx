import { ShieldCheck } from 'lucide-react';
import { FormEvent, useState } from 'react';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { Input } from '../components/ui/input';

type SetupViewProps = {
  onCreate: (payload: { username: string; password: string; displayName?: string }) => Promise<void>;
  errorMessage?: string | null;
  disabled?: boolean;
};

export function SetupView({ onCreate, errorMessage, disabled = false }: SetupViewProps) {
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('Administrator');

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (disabled) {
      return;
    }
    await onCreate({ username, password, displayName });
  }

  return (
    <main className="grid min-h-screen place-items-center bg-muted/30 px-4 py-6">
      <Card className="w-full max-w-sm border-border/70">
        <CardHeader>
          <CardTitle className="text-lg">Castrelyx Manager 초기 설정</CardTitle>
          <CardDescription>최초 관리자 계정을 생성하세요.</CardDescription>
        </CardHeader>
        <CardContent>
          <form className="grid gap-3" onSubmit={submit}>
            <label className="grid gap-1 text-sm" htmlFor="setup-username">
              관리자 계정
              <Input
                id="setup-username"
                value={username}
                autoComplete="username"
                disabled={disabled}
                onChange={(event) => setUsername(event.target.value)}
              />
            </label>
            <label className="grid gap-1 text-sm" htmlFor="setup-display-name">
              표시 이름
              <Input
                id="setup-display-name"
                value={displayName}
                autoComplete="name"
                disabled={disabled}
                onChange={(event) => setDisplayName(event.target.value)}
              />
            </label>
            <label className="grid gap-1 text-sm" htmlFor="setup-password">
              비밀번호
              <Input
                id="setup-password"
                type="password"
                value={password}
                autoComplete="new-password"
                disabled={disabled}
                onChange={(event) => setPassword(event.target.value)}
              />
            </label>
            {errorMessage ? <p className="rounded-md border border-destructive/30 bg-destructive/10 p-2 text-xs text-destructive">{errorMessage}</p> : null}
            <Button className="w-full" type="submit" disabled={disabled}>
              <ShieldCheck data-icon="inline-start" />
              {disabled ? '처리 중...' : '초기 설정 완료'}
            </Button>
          </form>
        </CardContent>
      </Card>
    </main>
  );
}
