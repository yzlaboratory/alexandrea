import { useState } from 'react';
import { LogOut } from 'lucide-react';

import { useLogout, useMe } from '../auth/useAuth';
import type { Status } from '../api/types';
import { LibraryList } from '../components/LibraryList';
import { SearchPanel } from '../components/SearchPanel';

type Tab = 'plate' | 'watched' | 'abandoned';

const TAB_FILTER: Record<Tab, Status[]> = {
  plate: ['want', 'watching'],
  watched: ['watched'],
  abandoned: ['abandoned'],
};

const TAB_LABEL: Record<Tab, string> = {
  plate: 'On our plate',
  watched: 'Watched',
  abandoned: 'Abandoned',
};

const TABS: Tab[] = ['plate', 'watched', 'abandoned'];

export function HomePage() {
  const { data: me } = useMe();
  const logout = useLogout();
  const [tab, setTab] = useState<Tab>('plate');

  return (
    <main className="min-h-screen bg-slate-950 px-6 py-8">
      <div className="mx-auto max-w-4xl space-y-8">
        <header className="flex items-start justify-between gap-6">
          <div>
            <h1 className="text-2xl font-semibold text-slate-100">
              Hello, {me?.display_name ?? 'there'}
            </h1>
            <p className="text-sm text-slate-400">Your shared library.</p>
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
        </header>

        <SearchPanel />

        <section aria-labelledby="library-heading" className="space-y-4">
          <h2 id="library-heading" className="sr-only">
            Library
          </h2>

          <div role="tablist" aria-label="Library filter" className="flex gap-2">
            {TABS.map((t) => (
              <button
                key={t}
                role="tab"
                type="button"
                aria-selected={tab === t}
                onClick={() => setTab(t)}
                className={
                  'rounded-md px-3 py-1.5 text-sm transition ' +
                  (tab === t
                    ? 'bg-sky-500 text-slate-950'
                    : 'bg-slate-900 text-slate-300 ring-1 ring-slate-800 hover:bg-slate-800')
                }
              >
                {TAB_LABEL[t]}
              </button>
            ))}
          </div>

          <LibraryList filter={TAB_FILTER[tab]} />
        </section>
      </div>
    </main>
  );
}
