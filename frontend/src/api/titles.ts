import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { ApiError, apiJson, apiRequest } from './client';
import type { Kind, Title } from './types';

export function useTitle(id: string | undefined) {
  return useQuery({
    queryKey: ['title', id ?? ''],
    enabled: !!id,
    queryFn: () => apiJson<Title>(`/api/titles/${id}`),
  });
}

// useUpsertTitle calls POST /api/titles, which is server-side idempotent on
// tmdb_id (returns 200 if the row already exists, 201 if newly created).
// Either way the caller gets back the local Title with its assigned id.
export function useUpsertTitle() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { tmdb_id: number; kind: Kind }) => {
      const res = await apiRequest('/api/titles', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input),
      });
      if (!res.ok) throw new ApiError(res.status, 'add title failed');
      return (await res.json()) as Title;
    },
    onSuccess: (title) => {
      qc.setQueryData(['title', title.id], title);
    },
  });
}
