import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, Plus, Trash2 } from 'lucide-react';

import { useMe } from '../auth/useAuth';
import { useTitle } from '../api/titles';
import { useAddEntry, useDeleteEntry, useLibrary } from '../api/library';
import type { LibraryEntry } from '../api/types';
import { Poster } from '../components/Poster';
import { StatusControls } from '../components/StatusControls';
import { RatingWidget } from '../components/RatingWidget';

export function TitlePage() {
  const { id } = useParams<{ id: string }>();
  const title = useTitle(id);
  const lib = useLibrary();
  const { data: me } = useMe();
  const addEntry = useAddEntry();
  const deleteEntry = useDeleteEntry();

  if (!id) return null;

  if (title.isLoading || lib.isLoading) {
    return <FullscreenStatus>Loading…</FullscreenStatus>;
  }
  if (title.isError || !title.data) {
    return (
      <FullscreenStatus>
        <p role="alert" className="text-rose-400">
          Title not found.
        </p>
        <Link to="/" className="mt-4 text-sky-400 hover:underline">
          Back to library
        </Link>
      </FullscreenStatus>
    );
  }

  const t = title.data;
  const entry = (lib.data ?? []).find((e) => e.title.id === id);

  return (
    <main className="min-h-screen bg-slate-950 px-6 py-8">
      <div className="mx-auto max-w-3xl space-y-6">
        <Link
          to="/"
          className="inline-flex items-center gap-2 text-sm text-slate-400 hover:text-slate-200"
        >
          <ArrowLeft className="h-4 w-4" aria-hidden />
          Library
        </Link>

        <article className="flex gap-6">
          <Poster
            posterPath={t.poster_path}
            title={t.display_title}
            size="w500"
            className="h-72 w-48 shrink-0"
          />
          <div className="flex flex-col gap-4">
            <header>
              <h1 className="text-2xl font-semibold text-slate-100">{t.display_title}</h1>
              <p className="text-sm text-slate-400">
                {t.release_year} · {t.kind}
              </p>
            </header>

            {t.synopsis ? <p className="leading-relaxed text-slate-300">{t.synopsis}</p> : null}

            {entry ? (
              <div className="space-y-3">
                <StatusControls entry={entry} />
                <button
                  type="button"
                  onClick={() => deleteEntry.mutate(entry.id)}
                  className="inline-flex items-center gap-2 rounded-md bg-slate-800 px-3 py-2 text-sm text-slate-200 ring-1 ring-slate-700 transition hover:bg-rose-500 hover:text-slate-950"
                >
                  <Trash2 className="h-4 w-4" aria-hidden />
                  Remove from library
                </button>
              </div>
            ) : (
              <button
                type="button"
                disabled={addEntry.isPending}
                onClick={() => addEntry.mutate({ title_id: t.id, status: 'want' })}
                className="inline-flex w-fit items-center gap-2 rounded-md bg-sky-500 px-3 py-2 text-sm font-medium text-slate-950 transition hover:bg-sky-400 disabled:opacity-60"
              >
                <Plus className="h-4 w-4" aria-hidden />
                {addEntry.isPending ? 'Adding…' : 'Add to library'}
              </button>
            )}
          </div>
        </article>

        {entry && me ? <Ratings entry={entry} meID={me.user_id} meName={me.display_name} /> : null}
      </div>
    </main>
  );
}

// Ratings renders the side-by-side rating cards. Per spec the UI shows
// two slots (one per user). At v0 we only know the other user's display
// name if they've already rated; if they haven't, we render a placeholder
// card labelled "Partner" so the layout still has both slots.
function Ratings({ entry, meID, meName }: { entry: LibraryEntry; meID: string; meName: string }) {
  const otherRating = entry.ratings.find((r) => r.user_id !== meID);
  const otherID = otherRating?.user_id ?? '__partner__';
  const otherName = otherRating?.display_name ?? 'Partner';

  return (
    <section aria-labelledby="ratings-heading" className="space-y-3">
      <h2 id="ratings-heading" className="text-lg font-semibold text-slate-100">
        Ratings
      </h2>
      <div className="grid gap-4 sm:grid-cols-2">
        <RatingWidget entry={entry} userID={meID} displayName={meName} isSelf />
        <RatingWidget entry={entry} userID={otherID} displayName={otherName} isSelf={false} />
      </div>
    </section>
  );
}

function FullscreenStatus({ children }: { children: React.ReactNode }) {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center bg-slate-950 px-6 py-12 text-slate-400">
      {children}
    </main>
  );
}
