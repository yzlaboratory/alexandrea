import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchCatalogPage, type CatalogPageResult } from './catalogApi';

function response(status: number, body?: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

describe('catalogApi', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('requests the given media type and page with same-origin credentials', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { entries: [], page: 3, hasMore: false }),
    );

    await fetchCatalogPage('movies', 3);

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/catalog/movies?page=3');
    expect(init.credentials).toBe('same-origin');
    // A GET carries no CSRF header, unlike authApi's state-changing calls.
    expect(init.headers).toBeUndefined();
  });

  it('resolves to an ok outcome carrying the parsed page on a 200', async () => {
    const result: CatalogPageResult = {
      entries: [
        {
          provider: 'TMDB',
          externalId: '603692',
          mediaType: 'movies',
          title: 'John Wick: Chapter 4',
          coverUrl: 'https://image.tmdb.org/t/p/w500/x.jpg',
          releaseDate: '2023-03-22',
          externalRating: 7.8,
          externalRatingScale: 10,
        },
      ],
      page: 1,
      hasMore: true,
    };
    fetchMock.mockResolvedValueOnce(response(200, result));

    expect(await fetchCatalogPage('movies', 1)).toEqual({
      status: 'ok',
      result,
    });
  });

  it('resolves to an ok outcome with an empty entries array on an empty upstream page', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { entries: [], page: 50, hasMore: false }),
    );

    expect(await fetchCatalogPage('movies', 50)).toEqual({
      status: 'ok',
      result: { entries: [], page: 50, hasMore: false },
    });
  });

  it('resolves to an error outcome on a non-2xx response', async () => {
    fetchMock.mockResolvedValueOnce(response(503));

    expect(await fetchCatalogPage('movies', 1)).toEqual({ status: 'error' });
  });

  it('resolves to an error outcome on a 404 (unsupported media type)', async () => {
    fetchMock.mockResolvedValueOnce(response(404));

    expect(await fetchCatalogPage('games', 1)).toEqual({ status: 'error' });
  });

  it('resolves to an error outcome when the body is not valid JSON', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.reject(new Error('not json')),
    });

    expect(await fetchCatalogPage('movies', 1)).toEqual({ status: 'error' });
  });

  it('formats the page number as a plain integer in the query string', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { entries: [], page: 12, hasMore: false }),
    );

    await fetchCatalogPage('movies', 12);

    const [path] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/catalog/movies?page=12');
  });
});
