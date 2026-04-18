import { useState } from 'react';
import { Star, Trash2, X } from 'lucide-react';

import { useDeleteRating, useUpsertRating } from '../api/ratings';
import type { LibraryEntry, Rating } from '../api/types';

type Props = {
  entry: LibraryEntry;
  // The canonical id of this widget's rater. If it matches the current
  // user we show edit controls; otherwise it's read-only.
  userID: string;
  displayName: string;
  // True when this widget represents the signed-in user's own rating.
  isSelf: boolean;
};

// RatingWidget renders one of two side-by-side cards on the title page.
// For the current user it is interactive: pick a score, optionally write a
// note, save, or clear the rating. For the other user it is read-only.
export function RatingWidget({ entry, userID, displayName, isSelf }: Props) {
  const existing = entry.ratings.find((r) => r.user_id === userID);
  if (isSelf) {
    return <SelfRating entry={entry} displayName={displayName} existing={existing} />;
  }
  return <OtherRating displayName={displayName} existing={existing} />;
}

function SelfRating({
  entry,
  displayName,
  existing,
}: {
  entry: LibraryEntry;
  displayName: string;
  existing: Rating | undefined;
}) {
  const [score, setScore] = useState<number | null>(existing?.score ?? null);
  const [note, setNote] = useState(existing?.note ?? '');
  const [editing, setEditing] = useState(!existing);
  const upsert = useUpsertRating();
  const remove = useDeleteRating();

  // When the existing rating changes out from under us (e.g., another tab),
  // resync local state only if the user is not actively editing.
  if (!editing && existing && existing.score !== score) {
    setScore(existing.score);
    setNote(existing.note ?? '');
  }

  async function save() {
    if (score === null) return;
    await upsert.mutateAsync({ entryID: entry.id, score, note });
    setEditing(false);
  }

  async function clear() {
    await remove.mutateAsync(entry.id);
    setScore(null);
    setNote('');
    setEditing(true);
  }

  return (
    <article className="flex flex-col gap-3 rounded-lg bg-slate-900 p-4 ring-1 ring-slate-800">
      <header className="flex items-center justify-between">
        <h3 className="font-medium text-slate-100">{displayName}</h3>
        {existing && !editing ? (
          <button
            type="button"
            onClick={() => setEditing(true)}
            className="text-xs text-sky-400 hover:underline"
          >
            Edit
          </button>
        ) : null}
      </header>

      {editing ? (
        <>
          <StarInput value={score} onChange={setScore} />
          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="One line (optional)"
            maxLength={240}
            rows={2}
            className="w-full resize-none rounded-md bg-slate-800 px-3 py-2 text-sm text-slate-100 ring-1 ring-slate-700 focus:outline-none focus:ring-2 focus:ring-sky-500"
          />
          <div className="flex gap-2">
            <button
              type="button"
              disabled={score === null || upsert.isPending}
              onClick={save}
              className="inline-flex items-center gap-1 rounded-md bg-sky-500 px-3 py-1.5 text-sm font-medium text-slate-950 transition hover:bg-sky-400 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {upsert.isPending ? 'Saving…' : 'Save'}
            </button>
            {existing ? (
              <>
                <button
                  type="button"
                  onClick={() => {
                    setScore(existing.score);
                    setNote(existing.note ?? '');
                    setEditing(false);
                  }}
                  className="inline-flex items-center gap-1 rounded-md bg-slate-800 px-3 py-1.5 text-sm text-slate-200 ring-1 ring-slate-700 hover:bg-slate-700"
                >
                  <X className="h-3.5 w-3.5" aria-hidden />
                  Cancel
                </button>
                <button
                  type="button"
                  onClick={clear}
                  className="ml-auto inline-flex items-center gap-1 rounded-md bg-slate-800 px-3 py-1.5 text-sm text-slate-300 ring-1 ring-slate-700 hover:bg-rose-500 hover:text-slate-950"
                >
                  <Trash2 className="h-3.5 w-3.5" aria-hidden />
                  Remove
                </button>
              </>
            ) : null}
          </div>
        </>
      ) : (
        <ReadOnlyBody score={existing?.score ?? 0} note={existing?.note} />
      )}
    </article>
  );
}

function OtherRating({
  displayName,
  existing,
}: {
  displayName: string;
  existing: Rating | undefined;
}) {
  return (
    <article className="flex flex-col gap-3 rounded-lg bg-slate-900 p-4 ring-1 ring-slate-800">
      <h3 className="font-medium text-slate-100">{displayName}</h3>
      {existing ? (
        <ReadOnlyBody score={existing.score} note={existing.note} />
      ) : (
        <p className="text-sm text-slate-500">No rating yet.</p>
      )}
    </article>
  );
}

function ReadOnlyBody({ score, note }: { score: number; note: string | undefined }) {
  return (
    <>
      <StarRow score={score} />
      {note ? <p className="text-sm text-slate-300">{note}</p> : null}
    </>
  );
}

function StarInput({ value, onChange }: { value: number | null; onChange: (n: number) => void }) {
  return (
    <div role="radiogroup" aria-label="Score" className="flex gap-1">
      {[0, 1, 2, 3, 4, 5].map((n) => {
        const active = value !== null && n <= value;
        return (
          <button
            key={n}
            type="button"
            role="radio"
            aria-checked={value === n}
            onClick={() => onChange(n)}
            className="p-1"
          >
            <Star
              className={`h-6 w-6 ${active ? 'fill-amber-400 text-amber-400' : 'text-slate-600'}`}
              aria-hidden
            />
            <span className="sr-only">{n} of 5</span>
          </button>
        );
      })}
    </div>
  );
}

function StarRow({ score }: { score: number }) {
  return (
    <div className="flex gap-0.5" aria-label={`${score} out of 5`}>
      {[1, 2, 3, 4, 5].map((n) => (
        <Star
          key={n}
          className={`h-5 w-5 ${n <= score ? 'fill-amber-400 text-amber-400' : 'text-slate-700'}`}
          aria-hidden
        />
      ))}
    </div>
  );
}
