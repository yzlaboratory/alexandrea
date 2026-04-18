import { useMutation, useQueryClient } from '@tanstack/react-query';

import { ApiError, apiRequest } from './client';
import type { LibraryEntry } from './types';

export function useUpsertRating() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { entryID: string; score: number; note?: string }) => {
      const res = await apiRequest(`/api/library/${input.entryID}/rating`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ score: input.score, note: input.note ?? '' }),
      });
      if (!res.ok) throw new ApiError(res.status, 'rate failed');
      return (await res.json()) as LibraryEntry;
    },
    // A rating can flip the entry to `watched`, so invalidate the whole
    // library cache rather than patching one row.
    onSuccess: () => qc.invalidateQueries({ queryKey: ['library'] }),
  });
}

export function useDeleteRating() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (entryID: string) => {
      const res = await apiRequest(`/api/library/${entryID}/rating`, { method: 'DELETE' });
      if (!res.ok && res.status !== 404) {
        throw new ApiError(res.status, 'delete rating failed');
      }
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['library'] }),
  });
}
