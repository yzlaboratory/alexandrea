import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';

import { apiJson } from './client';
import type { SearchResult } from './types';

type SearchResponse = { results: SearchResult[] };

// Debounce a value: returns the latest value only after `delay` ms of
// stillness. Cuts down on TMDB calls when the user is mid-typing — the
// backend's singleflight handles overlap, but this prevents the call
// entirely when the query is going to change again immediately.
export function useDebounced<T>(value: T, delay = 250): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(t);
  }, [value, delay]);
  return debounced;
}

export function useSearch(query: string) {
  const debounced = useDebounced(query.trim());
  return useQuery({
    queryKey: ['search', debounced],
    enabled: debounced.length > 0,
    queryFn: async () => {
      const res = await apiJson<SearchResponse>(`/api/search?q=${encodeURIComponent(debounced)}`);
      return res.results;
    },
    staleTime: 60_000,
  });
}
