import { LogOut } from 'lucide-react';

import { useLogout, useMe } from '../auth/useAuth';

export function HomePage() {
  const { data } = useMe();
  const logout = useLogout();

  return (
    <main className="min-h-screen bg-slate-950 px-6 py-12">
      <div className="mx-auto flex max-w-2xl items-start justify-between gap-8">
        <div>
          <h1 className="text-3xl font-semibold text-slate-100">
            Hello, {data?.display_name ?? 'there'}
          </h1>
          <p className="mt-2 text-slate-400">
            Your library is empty. Titles and ratings arrive in the next slice.
          </p>
        </div>

        <button
          type="button"
          onClick={() => logout.mutate()}
          disabled={logout.isPending}
          className="inline-flex items-center gap-2 rounded-md bg-slate-800 px-3 py-2 text-sm text-slate-200 ring-1 ring-slate-700 transition hover:bg-slate-700 disabled:opacity-60"
        >
          <LogOut className="h-4 w-4" aria-hidden />
          {logout.isPending ? 'Signing out…' : 'Sign out'}
        </button>
      </div>
    </main>
  );
}
