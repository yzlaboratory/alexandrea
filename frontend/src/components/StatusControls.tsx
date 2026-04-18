import { useUpdateEntryStatus } from '../api/library';
import type { LibraryEntry, Status } from '../api/types';
import { STATUS_LABEL } from '../api/types';

const ALL_STATUSES: Status[] = ['want', 'watching', 'watched', 'abandoned'];

export function StatusControls({ entry }: { entry: LibraryEntry }) {
  const update = useUpdateEntryStatus();
  return (
    <div role="group" aria-label="Change status" className="flex flex-wrap gap-2">
      {ALL_STATUSES.map((s) => {
        const active = entry.status === s;
        return (
          <button
            key={s}
            type="button"
            disabled={update.isPending || active}
            aria-pressed={active}
            onClick={() => update.mutate({ id: entry.id, status: s })}
            className={
              'rounded-md px-3 py-1.5 text-sm transition ' +
              (active
                ? 'bg-sky-500 text-slate-950'
                : 'bg-slate-800 text-slate-200 ring-1 ring-slate-700 hover:bg-slate-700 disabled:opacity-50')
            }
          >
            {STATUS_LABEL[s]}
          </button>
        );
      })}
    </div>
  );
}
