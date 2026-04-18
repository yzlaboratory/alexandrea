import { Link } from 'react-router-dom';
import { Trash2 } from 'lucide-react';

import { useDeleteEntry, useLibrary } from '../api/library';
import type { LibraryEntry, Status } from '../api/types';
import { Poster } from './Poster';
import { StatusControls } from './StatusControls';

type Props = {
  filter: Status[];
};

export function LibraryList({ filter }: Props) {
  const lib = useLibrary();
  const remove = useDeleteEntry();

  if (lib.isLoading) return <p className="text-slate-500">Loading…</p>;
  if (lib.isError) {
    return (
      <p role="alert" className="text-rose-400">
        Failed to load the library.
      </p>
    );
  }

  const entries = (lib.data ?? []).filter((e) => filter.includes(e.status));
  if (entries.length === 0) {
    return <p className="text-slate-500">Nothing here yet. Use the search above to add a title.</p>;
  }

  return (
    <ul className="space-y-4">
      {entries.map((e) => (
        <EntryRow key={e.id} entry={e} onRemove={() => remove.mutate(e.id)} />
      ))}
    </ul>
  );
}

function EntryRow({ entry, onRemove }: { entry: LibraryEntry; onRemove: () => void }) {
  return (
    <li className="flex gap-4 rounded-lg bg-slate-900 p-4 ring-1 ring-slate-800">
      <Link to={`/title/${entry.title.id}`} className="shrink-0">
        <Poster
          posterPath={entry.title.poster_path}
          title={entry.title.display_title}
          className="h-28 w-20"
        />
      </Link>
      <div className="flex flex-1 flex-col justify-between gap-3">
        <div>
          <Link
            to={`/title/${entry.title.id}`}
            className="text-base font-semibold text-slate-100 hover:text-sky-400"
          >
            {entry.title.display_title}
          </Link>
          <p className="text-sm text-slate-400">
            {entry.title.release_year} · {entry.title.kind}
          </p>
        </div>
        <StatusControls entry={entry} />
      </div>
      <button
        type="button"
        onClick={onRemove}
        aria-label={`Remove ${entry.title.display_title} from library`}
        className="self-start rounded-md p-2 text-slate-500 transition hover:bg-slate-800 hover:text-rose-400"
      >
        <Trash2 className="h-4 w-4" aria-hidden />
      </button>
    </li>
  );
}
