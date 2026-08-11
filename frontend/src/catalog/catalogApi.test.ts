import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  fetchCatalogPage,
  fetchCatalogPreference,
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

  it('appends a url-encoded genre param when a genre is given', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { items: [], page: 1, hasMore: false }),
    );

    await fetchCatalogPage('movies', 1, undefined, undefined, undefined, '28');

    const [path] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/catalog/movies?page=1&genre=28');
  });

  it('omits the genre param entirely when no genre is given', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { items: [], page: 1, hasMore: false }),
    );

    await fetchCatalogPage('movies', 1);

    const [path] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/catalog/movies?page=1');
  });

  it('combines sort, direction, and genre params in one request', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { items: [], page: 1, hasMore: false }),
    );

    await fetchCatalogPage('movies', 1, undefined, 'popularity', 'desc', '28');

    const [path] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe(
      '/api/catalog/movies?page=1&sort=popularity&direction=desc&genre=28',
    );
  });

  it('resolves to an ok outcome carrying a genre-filtered page just like a popular-feed page', async () => {
    const result: CatalogPageResult = {
      items: [
        {
          provider: 'TMDB',
          externalId: '9',
          mediaType: 'movies',
          title: 'An Action Movie',
          coverUrl: null,
          releaseDate: '2024-01-01',
          externalRating: 7,
          externalRatingScale: 10,
        },
      ],
      page: 1,
      hasMore: false,
      availableFilters: { genre: [{ value: '28', label: 'Action' }] },
    };
    fetchMock.mockResolvedValueOnce(response(200, result));

    expect(
      await fetchCatalogPage(
        'movies',
        1,
        undefined,
        undefined,
        undefined,
        '28',
      ),
    ).toEqual({ status: 'ok', result });
  });
});

describe('fetchCatalogPreference', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('requests the preference for the given media type with same-origin credentials', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { sortKey: 'title', sortDirection: 'asc', genre: null }),
    );

    await fetchCatalogPreference('books');

    const [path, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/catalog/books/preference');
    expect(init.credentials).toBe('same-origin');
  });

  it('resolves to the stored sort key, direction, and genre on a 200', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, {
        sortKey: 'external_rating',
        sortDirection: 'desc',
        genre: '28',
      }),
    );

    expect(await fetchCatalogPreference('books')).toEqual({
      sortKey: 'external_rating',
      sortDirection: 'desc',
      genre: '28',
    });
  });

  it('resolves to null fields when nothing is stored', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { sortKey: null, sortDirection: null, genre: null }),
    );

    expect(await fetchCatalogPreference('movies')).toEqual({
      sortKey: null,
      sortDirection: null,
      genre: null,
    });
  });

  it('resolves to a stored genre with no sort chosen yet', async () => {
    fetchMock.mockResolvedValueOnce(
      response(200, { sortKey: null, sortDirection: null, genre: '28' }),
    );

    expect(await fetchCatalogPreference('movies')).toEqual({
      sortKey: null,
      sortDirection: null,
      genre: '28',
    });
  });

  it('degrades to null fields rather than rejecting on a non-2xx response', async () => {
    fetchMock.mockResolvedValueOnce(response(401));

    expect(await fetchCatalogPreference('movies')).toEqual({
      sortKey: null,
      sortDirection: null,
      genre: null,
    });
  });

  it('degrades to null fields rather than rejecting when fetch itself fails', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));

    await expect(fetchCatalogPreference('movies')).resolves.toEqual({
      sortKey: null,
      sortDirection: null,
      genre: null,
    });
  });

  it('degrades to null fields when the body is not valid JSON', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: () => Promise.reject(new Error('not json')),
    });

    expect(await fetchCatalogPreference('movies')).toEqual({
      sortKey: null,
      sortDirection: null,
      genre: null,
    });
  });
});
