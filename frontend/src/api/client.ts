import { CSRF_HEADER, readCsrfToken } from './csrf';

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

type RequestOpts = {
  method?: string;
  body?: BodyInit;
  headers?: Record<string, string>;
};

export async function apiRequest(path: string, opts: RequestOpts = {}): Promise<Response> {
  const method = opts.method ?? 'GET';
  const headers: Record<string, string> = { ...(opts.headers ?? {}) };
  const mutating = method !== 'GET' && method !== 'HEAD';
  if (mutating) {
    const token = readCsrfToken();
    if (token) headers[CSRF_HEADER] = token;
  }
  const init: RequestInit = {
    method,
    headers,
    credentials: 'same-origin',
  };
  if (opts.body !== undefined) init.body = opts.body;
  const res = await fetch(path, init);
  return res;
}

export async function apiJson<T>(path: string, opts: RequestOpts = {}): Promise<T> {
  const res = await apiRequest(path, opts);
  if (!res.ok) {
    throw new ApiError(res.status, `request failed: ${res.status}`);
  }
  return (await res.json()) as T;
}
