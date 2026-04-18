import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ApiError, apiJson, apiRequest } from './client';
import type { LibraryEntry, Status } from './types';

const LIBRARY_KEY = ['library'] as const;

type ListResponse = { entries: LibraryEntry[] };

async function fetchLibrary(statuses?: Status[]): Promise<LibraryEntry[]> {
  const qs = statuses && statuses.length ? `?status=${statuses.join(',')}` : '';
  const res = await apiJson<ListResponse>(`/api/library${qs}`);
  return res.entries;
}

// useLibrary fetches all entries (no server-side filter) so the same cache
// powers every tab. Tabs filter client-side from the same array.
export function useLibrary() {
  return useQuery({
    queryKey: LIBRARY_KEY,
    // Empty array ([]) means "no filter" — without it we'd get the
    // server's default (want+watching), which would hide watched.
    queryFn: () => fetchLibrary(['want', 'watching', 'watched', 'abandoned']),
  });
}

export function useAddEntry() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { title_id: string; status?: Status }) => {
      const res = await apiRequest('/api/library', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input),
      });
      // 409 = already in library; treat as a soft success — the UI's
      // mental model is "title is now somewhere in the library."
      if (res.status === 409) return null;
      if (!res.ok) throw new ApiError(res.status, 'add to library failed');
      return (await res.json()) as LibraryEntry;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: LIBRARY_KEY }),
  });
}

export function useUpdateEntryStatus() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { id: string; status: Status }) => {
      const res = await apiRequest(`/api/library/${input.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: input.status }),
      });
      if (!res.ok) throw new ApiError(res.status, 'update status failed');
      return (await res.json()) as LibraryEntry;
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: LIBRARY_KEY }),
  });
}

export function useDeleteEntry() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      const res = await apiRequest(`/api/library/${id}`, { method: 'DELETE' });
      if (!res.ok && res.status !== 404) {
        throw new ApiError(res.status, 'delete failed');
      }
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: LIBRARY_KEY }),
  });
}
