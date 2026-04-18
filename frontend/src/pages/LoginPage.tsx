import { useState, type FormEvent } from 'react';
import { LogIn } from 'lucide-react';

import { useLogin } from '../auth/useAuth';

export function LoginPage() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const login = useLogin();

  function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    login.mutate({ username, password });
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-950 px-4">
      <form
        onSubmit={onSubmit}
        className="w-full max-w-sm space-y-5 rounded-xl bg-slate-900 p-8 shadow-xl ring-1 ring-slate-800"
      >
        <div className="flex items-center gap-3">
          <LogIn className="h-6 w-6 text-slate-300" aria-hidden />
          <h1 className="text-xl font-semibold text-slate-100">Sign in</h1>
        </div>

        <label className="block space-y-1">
          <span className="text-sm text-slate-300">Username</span>
          <input
            type="text"
            autoComplete="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
            className="w-full rounded-md bg-slate-800 px-3 py-2 text-slate-100 ring-1 ring-slate-700 focus:outline-none focus:ring-2 focus:ring-sky-500"
          />
        </label>

        <label className="block space-y-1">
          <span className="text-sm text-slate-300">Password</span>
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            className="w-full rounded-md bg-slate-800 px-3 py-2 text-slate-100 ring-1 ring-slate-700 focus:outline-none focus:ring-2 focus:ring-sky-500"
          />
        </label>

        {login.isError ? (
          <p role="alert" className="text-sm text-rose-400">
            Sign-in failed.
          </p>
        ) : null}

        <button
          type="submit"
          disabled={login.isPending}
          className="w-full rounded-md bg-sky-500 px-3 py-2 font-medium text-slate-950 transition hover:bg-sky-400 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {login.isPending ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}
