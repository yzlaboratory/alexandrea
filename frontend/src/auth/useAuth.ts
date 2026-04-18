import { useQuery, useQueryClient, useMutation } from '@tanstack/react-query';

import { apiJson, apiRequest, ApiError } from '../api/client';

export type Me = {
  user_id: string;
  display_name: string;
};

const ME_KEY = ['me'] as const;

async function fetchMe(): Promise<Me | null> {
  try {
    return await apiJson<Me>('/api/me');
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) return null;
    throw err;
  }
}

export function useMe() {
  return useQuery({
    queryKey: ME_KEY,
    queryFn: fetchMe,
  });
}

export function useLogin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (creds: { username: string; password: string }) => {
      const body = new URLSearchParams();
      body.set('username', creds.username);
      body.set('password', creds.password);
      const res = await apiRequest('/login', {
        method: 'POST',
        body,
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      });
      if (!res.ok) {
        throw new ApiError(res.status, 'login failed');
      }
    },
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ME_KEY });
    },
  });
}

export function useLogout() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const res = await apiRequest('/logout', { method: 'POST' });
      if (!res.ok) throw new ApiError(res.status, 'logout failed');
    },
    onSuccess: () => {
      qc.setQueryData<Me | null>(ME_KEY, null);
    },
  });
}
