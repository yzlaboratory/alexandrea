import { useState } from 'react';
import { Search as SearchIcon, Plus } from 'lucide-react';

import { useSearch } from '../api/search';
import { useUpsertTitle } from '../api/titles';
import { useAddEntry, useLibrary } from '../api/library';
import { Poster } from './Poster';

export function SearchPanel() {
  const [query, setQuery] = useState('');
  const search = useSearch(query);
  const upsertTitle = useUpsertTitle();
  const addEntry = useAddEntry();
  const library = useLibrary();

  const knownTMDBIDs = new Set((library.data ?? []).map((e) => e.title.tmdb_id));

  async function add(tmdbID: number, kind: 'movie' | 'series') {
    const title = await upsertTitle.mutateAsync({ tmdb_id: tmdbID, kind });
    await addEntry.mutateAsync({ title_id: title.id, status: 'want' });
  }

  return (
    <section aria-labelledby="search-heading" className="space-y-4">
      <h2 id="search-heading" className="sr-only">
        Search TMDB
      </h2>

      <label className="flex items-center gap-3 rounded-lg bg-slate-900 px-4 py-3 ring-1 ring-slate-800 focus-within:ring-sky-500">
        <SearchIcon className="h-5 w-5 text-slate-400" aria-hidden />
        <input
          type="search"
          placeholder="Search movies and shows…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="w-full bg-transparent text-slate-100 placeholder:text-slate-500 focus:outline-none"
        />
      </label>

      {query.trim() && search.isFetching ? (
        <p className="text-sm text-slate-500">Searching…</p>
      ) : null}

      {search.isError ? (
        <p role="alert" className="text-sm text-rose-400">
          Search failed. Try again.
        </p>
      ) : null}

      {search.data && search.data.length === 0 ? (
        <p className="text-sm text-slate-500">No results.</p>
      ) : null}

      {search.data && search.data.length > 0 ? (
        <ul className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
          {search.data.map((r) => {
            const inLibrary = knownTMDBIDs.has(r.tmdb_id);
            const pending =
              (upsertTitle.isPending || addEntry.isPending) &&
              upsertTitle.variables?.tmdb_id === r.tmdb_id;
            return (
              <li
                key={`${r.kind}:${r.tmdb_id}`}
                className="flex flex-col gap-2 rounded-lg bg-slate-900 p-3 ring-1 ring-slate-800"
              >
                <Poster
                  posterPath={r.poster_path}
                  title={r.title}
                  className="aspect-[2/3] w-full"
                />
                <div className="min-h-[3rem] text-sm">
                  <p className="line-clamp-2 font-medium text-slate-100">{r.title}</p>
                  <p className="text-slate-400">
                    {r.year ?? '—'} · {r.kind}
                  </p>
                </div>
                <button
                  type="button"
                  disabled={inLibrary || pending}
                  onClick={() => add(r.tmdb_id, r.kind)}
                  className="inline-flex items-center justify-center gap-1 rounded-md bg-sky-500 px-3 py-1.5 text-sm font-medium text-slate-950 transition hover:bg-sky-400 disabled:cursor-not-allowed disabled:bg-slate-800 disabled:text-slate-500"
                >
                  <Plus className="h-4 w-4" aria-hidden />
                  {inLibrary ? 'In library' : pending ? 'Adding…' : 'Add to library'}
                </button>
              </li>
            );
          })}
        </ul>
      ) : null}
    </section>
  );
}
