import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fetchCatalogPage,
  fetchSortPreference,
  type CatalogPageResult,
} from './catalogApi';

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
      response(200, { items: [], page: 3, hasMore: false }),
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
      items: [
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

  it('resolves to an ok outcome with an empty items array on an empty upstream page', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { items: [], page: 50, hasMore: false }),
    );

    expect(await fetchCatalogPage('movies', 50)).toEqual({
      status: 'ok',
      result: { items: [], page: 50, hasMore: false },
    });
  });

  it('resolves to an error outcome on a non-2xx response', async () => {
    fetchMock.mockResolvedValueOnce(response(503));

    expect(await fetchCatalogPage('movies', 1)).toEqual({ status: 'error' });
  });

  it('resolves to an error outcome rather than rejecting when fetch itself fails', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));

    await expect(fetchCatalogPage('movies', 1)).resolves.toEqual({
      status: 'error',
    });
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
      response(200, { items: [], page: 12, hasMore: false }),
    );

    await fetchCatalogPage('movies', 12);

    const [path] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/catalog/movies?page=12');
  });

  it('appends a url-encoded search param when a search query is given', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { items: [], page: 1, hasMore: false }),
    );

    await fetchCatalogPage('movies', 1, 'blade runner');

    const [path] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/catalog/movies?page=1&search=blade%20runner');
  });

  it('omits the search param entirely when no search is given', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { items: [], page: 1, hasMore: false }),
    );

    await fetchCatalogPage('movies', 1);

    const [path] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/catalog/movies?page=1');
  });

  it('omits the search param when the search string is empty', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { items: [], page: 1, hasMore: false }),
    );

    await fetchCatalogPage('movies', 1, '');

    const [path] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/catalog/movies?page=1');
  });

  it('resolves to an ok outcome carrying search results just like a popular-feed page', async () => {
    const result: CatalogPageResult = {
      items: [
        {
          provider: 'TMDB',
          externalId: '78',
          mediaType: 'movies',
          title: 'Blade Runner',
          coverUrl: null,
          releaseDate: '1982-06-25',
          externalRating: 7.9,
          externalRatingScale: 10,
        },
      ],
      page: 1,
      hasMore: false,
    };
    fetchMock.mockResolvedValueOnce(response(200, result));

    expect(await fetchCatalogPage('movies', 1, 'blade runner')).toEqual({
      status: 'ok',
      result,
    });
  });

  it('resolves to an error outcome on a search upstream failure just like popular', async () => {
    fetchMock.mockResolvedValueOnce(response(503));

    expect(await fetchCatalogPage('movies', 1, 'blade runner')).toEqual({
      status: 'error',
    });
  });

  it('appends sort and direction params when a sort is given', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { items: [], page: 1, hasMore: false }),
    );

    await fetchCatalogPage('movies', 1, undefined, 'popularity', 'desc');

    const [path] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe(
      '/api/catalog/movies?page=1&sort=popularity&direction=desc',
    );
  });

  it('omits the sort and direction params entirely when no sort is given', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { items: [], page: 1, hasMore: false }),
    );

    await fetchCatalogPage('movies', 1);

    const [path] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/catalog/movies?page=1');
  });

  it('resolves to an ok outcome carrying a sorted page just like a popular-feed page', async () => {
    const result: CatalogPageResult = {
      items: [
        {
          provider: 'TMDB',
          externalId: '1',
          mediaType: 'movies',
          title: 'A Title',
          coverUrl: null,
          releaseDate: '2024-01-01',
          externalRating: 5,
          externalRatingScale: 10,
        },
      ],
      page: 1,
      hasMore: false,
    };
    fetchMock.mockResolvedValueOnce(response(200, result));

    expect(
      await fetchCatalogPage('movies', 1, undefined, 'title', 'asc'),
    ).toEqual({ status: 'ok', result });
  });
});

describe('fetchSortPreference', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('requests the sort preference for the given media type with same-origin credentials', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { sortKey: 'title', sortDirection: 'asc' }),
    );

    await fetchSortPreference('books');

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/catalog/books/sort-preference');
    expect(init.credentials).toBe('same-origin');
  });

  it('resolves to the stored sort key and direction on a 200', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { sortKey: 'external_rating', sortDirection: 'desc' }),
    );

    expect(await fetchSortPreference('books')).toEqual({
      sortKey: 'external_rating',
      sortDirection: 'desc',
    });
  });

  it('resolves to null fields when nothing is stored', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { sortKey: null, sortDirection: null }),
    );

    expect(await fetchSortPreference('movies')).toEqual({
      sortKey: null,
      sortDirection: null,
    });
  });

  it('degrades to null fields rather than rejecting on a non-2xx response', async () => {
    fetchMock.mockResolvedValueOnce(response(401));

    expect(await fetchSortPreference('movies')).toEqual({
      sortKey: null,
      sortDirection: null,
    });
  });

  it('degrades to null fields rather than rejecting when fetch itself fails', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));

    await expect(fetchSortPreference('movies')).resolves.toEqual({
      sortKey: null,
      sortDirection: null,
    });
  });

  it('degrades to null fields when the body is not valid JSON', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.reject(new Error('not json')),
    });

    expect(await fetchSortPreference('movies')).toEqual({
      sortKey: null,
      sortDirection: null,
    });
  });
});
