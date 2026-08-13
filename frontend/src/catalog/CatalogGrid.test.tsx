import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import CatalogGrid from './CatalogGrid';
import * as catalogApi from './catalogApi';
import type { CatalogItem, FetchCatalogPageOutcome } from './catalogApi';

vi.mock('./catalogApi', () => ({
  fetchCatalogPage: vi.fn(),
}));

const mockedFetchCatalogPage = vi.mocked(catalogApi.fetchCatalogPage);

// jsdom does not implement IntersectionObserver, so CatalogGrid's own module
// under test can't run without a stand-in. This fake captures the callback
// each `new IntersectionObserver(...)` call registers, so a test can fire it
// manually to simulate the sentinel scrolling into view.
class FakeIntersectionObserver implements IntersectionObserver {
  static instances: FakeIntersectionObserver[] = [];
  readonly root = null;
  readonly rootMargin = '';
  readonly thresholds: readonly number[] = [];
  private readonly callback: IntersectionObserverCallback;

  constructor(callback: IntersectionObserverCallback) {
    this.callback = callback;
    FakeIntersectionObserver.instances.push(this);
  }

  observe = vi.fn();
  unobserve = vi.fn();
  disconnect = vi.fn();
  takeRecords(): IntersectionObserverEntry[] {
    return [];
  }

  trigger(isIntersecting: boolean): void {
    this.callback([{ isIntersecting } as IntersectionObserverEntry], this);
  }
}

function item(overrides: Partial<CatalogItem> = {}): CatalogItem {
  return {
    provider: 'TMDB',
    externalId: '1',
    mediaType: 'movies',
    title: 'A Movie',
    coverUrl: null,
    releaseDate: '2024-01-01',
    externalRating: 7.5,
    externalRatingScale: 10,
    ...overrides,
  };
}

// Every test in this file that reaches this point has already awaited a
// render that installs exactly one observer, so a missing one is a real bug
// worth failing loudly on rather than a possibly-undefined access to thread
// through every call site.
function lastObserver(): FakeIntersectionObserver {
  const observer = FakeIntersectionObserver.instances.at(-1);
  if (!observer) throw new Error('No IntersectionObserver was created');
  return observer;
}

describe('CatalogGrid', () => {
  beforeEach(() => {
    FakeIntersectionObserver.instances = [];
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
    mockedFetchCatalogPage.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('loads and renders the first page on mount', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'First Movie' })],
        page: 1,
        hasMore: true,
      },
    });

    render(<CatalogGrid mediaType="movies" />);

    expect(await screen.findByText('First Movie')).toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
      'movies',
      1,
      undefined,
      undefined,
      undefined,
      undefined,
    );
  });

  it('fetches the next page when the sentinel intersects, appending to the existing items', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ externalId: '1', title: 'Page One Movie' })],
        page: 1,
        hasMore: true,
      },
    });
    render(<CatalogGrid mediaType="movies" />);
    await screen.findByText('Page One Movie');

    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ externalId: '2', title: 'Page Two Movie' })],
        page: 2,
        hasMore: true,
      },
    });
    lastObserver().trigger(true);

    expect(await screen.findByText('Page Two Movie')).toBeInTheDocument();
    // The first page's items are still there — appended to, not replaced.
    expect(screen.getByText('Page One Movie')).toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenNthCalledWith(
      2,
      'movies',
      2,
      undefined,
      undefined,
      undefined,
      undefined,
    );
  });

  it('does not fetch again once the last page has been reached', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: { items: [item()], page: 1, hasMore: false },
    });
    render(<CatalogGrid mediaType="movies" />);
    await screen.findByText('A Movie');

    lastObserver().trigger(true);

    // Give any errant async fetch a chance to fire before asserting it didn't.
    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenCalledTimes(1);
    });
  });

  it('ignores a non-intersecting observer callback', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: { items: [item()], page: 1, hasMore: true },
    });
    render(<CatalogGrid mediaType="movies" />);
    await screen.findByText('A Movie');

    lastObserver().trigger(false);

    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenCalledTimes(1);
    });
  });

  it('shows a "no items" message when the upstream page comes back empty', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: { items: [], page: 1, hasMore: false },
    });

    render(<CatalogGrid mediaType="movies" />);

    expect(await screen.findByText('No items found.')).toBeInTheDocument();
  });

  it('shows an error with a retry action when the first page fails to load', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({ status: 'error' });

    render(<CatalogGrid mediaType="movies" />);

    expect(
      await screen.findByText(/temporarily unavailable/i),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });

  it('renders an ok page with no "stale" affordance, indistinguishable from a fresh one', async () => {
    // ADR 0015: a page served from the stale-while-error fallback is a
    // plain 'ok' outcome, identical in shape to a freshly-fetched one — the
    // grid has no way to tell the two apart and must never try to.
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'A Recently Refetched Movie' })],
        page: 1,
        hasMore: false,
      },
    });

    render(<CatalogGrid mediaType="movies" />);

    expect(
      await screen.findByText('A Recently Refetched Movie'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/stale/i)).not.toBeInTheDocument();
  });

  it('retries the same page and recovers after a failed load', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({ status: 'error' });
    render(<CatalogGrid mediaType="movies" />);
    await screen.findByText(/temporarily unavailable/i);

    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'Recovered Movie' })],
        page: 1,
        hasMore: false,
      },
    });
    const user = (await import('@testing-library/user-event')).default.setup();
    await user.click(screen.getByRole('button', { name: /retry/i }));

    expect(await screen.findByText('Recovered Movie')).toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenNthCalledWith(
      2,
      'movies',
      1,
      undefined,
      undefined,
      undefined,
      undefined,
    );
  });

  it('starts a fresh feed when remounted for a different media type, as CatalogPage does via key', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'Movies Title' })],
        page: 1,
        hasMore: false,
      },
    });
    // CatalogPage renders <CatalogGrid key={mediaType} .../> — a changed key
    // forces React to unmount the old instance and mount a new one, which is
    // what actually resets state here (not an internal effect). Rerendering
    // with a different `key` reproduces that from the test.
    const { rerender } = render(
      <CatalogGrid key="movies" mediaType="movies" />,
    );
    await screen.findByText('Movies Title');

    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'TV Title', mediaType: 'tv' })],
        page: 1,
        hasMore: false,
      },
    });
    rerender(<CatalogGrid key="tv" mediaType="tv" />);

    expect(await screen.findByText('TV Title')).toBeInTheDocument();
    expect(screen.queryByText('Movies Title')).not.toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenNthCalledWith(
      2,
      'tv',
      1,
      undefined,
      undefined,
      undefined,
      undefined,
    );
  });

  it('does not stack a second concurrent fetch while one is already in flight', async () => {
    let resolveFirst: (outcome: FetchCatalogPageOutcome) => void = () =>
      undefined;
    mockedFetchCatalogPage.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveFirst = resolve;
        }),
    );
    render(<CatalogGrid mediaType="movies" />);
    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenCalledTimes(1);
    });

    // The sentinel intersecting while the first request is still pending
    // must not trigger a second, overlapping fetch.
    lastObserver().trigger(true);
    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenCalledTimes(1);
    });

    resolveFirst({
      status: 'ok',
      result: { items: [item()], page: 1, hasMore: true },
    });
    await screen.findByText('A Movie');
  });

  it('requests the given search query alongside the media type and page', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'Blade Runner' })],
        page: 1,
        hasMore: true,
      },
    });

    render(<CatalogGrid mediaType="movies" search="blade runner" />);

    expect(await screen.findByText('Blade Runner')).toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
      'movies',
      1,
      'blade runner',
      undefined,
      undefined,
      undefined,
    );
  });

  it('carries the same search query into the next page fetch when the sentinel intersects', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ externalId: '1', title: 'Page One' })],
        page: 1,
        hasMore: true,
      },
    });
    render(<CatalogGrid mediaType="movies" search="blade runner" />);
    await screen.findByText('Page One');

    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ externalId: '2', title: 'Page Two' })],
        page: 2,
        hasMore: false,
      },
    });
    lastObserver().trigger(true);

    expect(await screen.findByText('Page Two')).toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenNthCalledWith(
      2,
      'movies',
      2,
      'blade runner',
      undefined,
      undefined,
      undefined,
    );
  });

  it('sends an empty search the same as no search at all', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: { items: [], page: 1, hasMore: false },
    });

    render(<CatalogGrid mediaType="movies" search="" />);

    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
        'movies',
        1,
        '',
        undefined,
        undefined,
        undefined,
      );
    });
  });

  it('shows a "no results" message with a clear-search affordance when a search page comes back empty', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: { items: [], page: 1, hasMore: false },
    });

    render(
      <CatalogGrid
        mediaType="movies"
        search="zzzznomatch"
        onClearSearch={vi.fn()}
      />,
    );

    expect(await screen.findByText(/no results for/i)).toBeInTheDocument();
    expect(screen.getByText(/zzzznomatch/)).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /clear search/i }),
    ).toBeInTheDocument();
  });

  it('does not show the clear-search affordance on an empty popular-feed page', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: { items: [], page: 1, hasMore: false },
    });

    render(<CatalogGrid mediaType="movies" />);

    expect(await screen.findByText('No items found.')).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: /clear search/i }),
    ).not.toBeInTheDocument();
  });

  it('requests the given sort and direction alongside the media type and page', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'Sorted Movie' })],
        page: 1,
        hasMore: true,
      },
    });

    render(<CatalogGrid mediaType="movies" sort="title" direction="asc" />);

    expect(await screen.findByText('Sorted Movie')).toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
      'movies',
      1,
      undefined,
      'title',
      'asc',
      undefined,
    );
  });

  it('carries the same sort and direction into the next page fetch when the sentinel intersects', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ externalId: '1', title: 'Page One' })],
        page: 1,
        hasMore: true,
      },
    });
    render(<CatalogGrid mediaType="movies" sort="title" direction="asc" />);
    await screen.findByText('Page One');

    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ externalId: '2', title: 'Page Two' })],
        page: 2,
        hasMore: false,
      },
    });
    lastObserver().trigger(true);

    expect(await screen.findByText('Page Two')).toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenNthCalledWith(
      2,
      'movies',
      2,
      undefined,
      'title',
      'asc',
      undefined,
    );
  });

  it('sends no sort params at all when no sort is given', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: { items: [], page: 1, hasMore: false },
    });

    render(<CatalogGrid mediaType="movies" />);

    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
        'movies',
        1,
        undefined,
        undefined,
        undefined,
        undefined,
      );
    });
  });

  it('starts a fresh feed when remounted for a different sort, as CatalogPage does via key', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'Popularity Sorted' })],
        page: 1,
        hasMore: false,
      },
    });
    const { rerender } = render(
      <CatalogGrid
        key="popularity:desc"
        mediaType="movies"
        sort="popularity"
        direction="desc"
      />,
    );
    await screen.findByText('Popularity Sorted');

    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'Title Sorted' })],
        page: 1,
        hasMore: false,
      },
    });
    rerender(
      <CatalogGrid
        key="title:asc"
        mediaType="movies"
        sort="title"
        direction="asc"
      />,
    );

    expect(await screen.findByText('Title Sorted')).toBeInTheDocument();
    expect(screen.queryByText('Popularity Sorted')).not.toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenNthCalledWith(
      2,
      'movies',
      1,
      undefined,
      'title',
      'asc',
      undefined,
    );
  });

  it('requests the given filters alone alongside the media type and page', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'Action Movie' })],
        page: 1,
        hasMore: true,
      },
    });

    render(<CatalogGrid mediaType="movies" filters={{ genre: '28' }} />);

    expect(await screen.findByText('Action Movie')).toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
      'movies',
      1,
      undefined,
      undefined,
      undefined,
      { genre: '28' },
    );
  });

  it('requests the given sort and filters together alongside the media type and page', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'Sorted Action Movie' })],
        page: 1,
        hasMore: true,
      },
    });

    render(
      <CatalogGrid
        mediaType="movies"
        sort="title"
        direction="asc"
        filters={{ genre: '28' }}
      />,
    );

    expect(await screen.findByText('Sorted Action Movie')).toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
      'movies',
      1,
      undefined,
      'title',
      'asc',
      { genre: '28' },
    );
  });

  it('requests filters of a different kind just as generically, e.g. an original-language filter', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'A Japanese Film' })],
        page: 1,
        hasMore: true,
      },
    });

    render(
      <CatalogGrid mediaType="movies" filters={{ originalLanguage: 'ja' }} />,
    );

    expect(await screen.findByText('A Japanese Film')).toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
      'movies',
      1,
      undefined,
      undefined,
      undefined,
      { originalLanguage: 'ja' },
    );
  });

  it('requests multiple filter fields combined in one object', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: { items: [item()], page: 1, hasMore: true },
    });

    render(
      <CatalogGrid
        mediaType="movies"
        filters={{ genre: '28', originalLanguage: 'ja' }}
      />,
    );

    await screen.findByText('A Movie');
    expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
      'movies',
      1,
      undefined,
      undefined,
      undefined,
      { genre: '28', originalLanguage: 'ja' },
    );
  });

  it('carries the same filters into the next page fetch when the sentinel intersects', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ externalId: '1', title: 'Page One' })],
        page: 1,
        hasMore: true,
      },
    });
    render(<CatalogGrid mediaType="movies" filters={{ genre: '28' }} />);
    await screen.findByText('Page One');

    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ externalId: '2', title: 'Page Two' })],
        page: 2,
        hasMore: false,
      },
    });
    lastObserver().trigger(true);

    expect(await screen.findByText('Page Two')).toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenNthCalledWith(
      2,
      'movies',
      2,
      undefined,
      undefined,
      undefined,
      { genre: '28' },
    );
  });

  it('sends an explicit null filter value rather than omitting it, alongside a sort', async () => {
    // null is CatalogPage's definite "no value selected" for that field
    // (e.g. a deselected chip) and must reach fetchCatalogPage as an
    // explicit value, not be collapsed into "not mentioned" —
    // CatalogService relies on that distinction to actually clear a
    // previously-chosen filter rather than falling back to it.
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: { items: [item()], page: 1, hasMore: true },
    });

    render(
      <CatalogGrid
        mediaType="movies"
        sort="title"
        direction="asc"
        filters={{ genre: null }}
      />,
    );

    await screen.findByText('A Movie');
    expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
      'movies',
      1,
      undefined,
      'title',
      'asc',
      { genre: null },
    );
  });

  it('sends an explicit null filter value rather than omitting it, with no sort given', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: { items: [item()], page: 1, hasMore: true },
    });

    render(<CatalogGrid mediaType="movies" filters={{ genre: null }} />);

    await screen.findByText('A Movie');
    expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
      'movies',
      1,
      undefined,
      undefined,
      undefined,
      { genre: null },
    );
  });

  it('combines search with filters when both are given, leaving the backend to decide what survives (issue 47)', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'Blade Runner' })],
        page: 1,
        hasMore: false,
      },
    });

    render(
      <CatalogGrid
        mediaType="movies"
        search="blade runner"
        filters={{ genre: '28' }}
      />,
    );

    expect(await screen.findByText('Blade Runner')).toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
      'movies',
      1,
      'blade runner',
      undefined,
      undefined,
      { genre: '28' },
    );
  });

  it('combines search with sort and filters together when all three are given', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'A Sci-Fi Book' })],
        page: 1,
        hasMore: false,
      },
    });

    render(
      <CatalogGrid
        mediaType="books"
        search="dune"
        sort="title"
        direction="asc"
        filters={{ genre: 'science_fiction' }}
      />,
    );

    expect(await screen.findByText('A Sci-Fi Book')).toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
      'books',
      1,
      'dune',
      'title',
      'asc',
      { genre: 'science_fiction' },
    );
  });

  it('combines search with sort alone when no filters prop is given', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: { items: [item()], page: 1, hasMore: false },
    });

    render(
      <CatalogGrid
        mediaType="games"
        search="witcher"
        sort="popularity"
        direction="desc"
      />,
    );

    await screen.findByText('A Movie');
    expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
      'games',
      1,
      'witcher',
      'popularity',
      'desc',
      undefined,
    );
  });

  it('reports the disabledFilters/sortDisabled from a successful fetch to onSearchCapabilitiesChange', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item()],
        page: 1,
        hasMore: false,
        disabledFilters: ['genre', 'runtime'],
        sortDisabled: true,
      },
    });
    const onSearchCapabilitiesChange = vi.fn();

    render(
      <CatalogGrid
        mediaType="movies"
        search="blade runner"
        onSearchCapabilitiesChange={onSearchCapabilitiesChange}
      />,
    );

    await screen.findByText('A Movie');
    expect(onSearchCapabilitiesChange).toHaveBeenCalledExactlyOnceWith({
      disabledFilters: ['genre', 'runtime'],
      sortDisabled: true,
    });
  });

  it('reports nothing disabled when the response omits disabledFilters/sortDisabled', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: { items: [item()], page: 1, hasMore: false },
    });
    const onSearchCapabilitiesChange = vi.fn();

    render(
      <CatalogGrid
        mediaType="books"
        onSearchCapabilitiesChange={onSearchCapabilitiesChange}
      />,
    );

    await screen.findByText('A Movie');
    expect(onSearchCapabilitiesChange).toHaveBeenCalledExactlyOnceWith({
      disabledFilters: [],
      sortDisabled: false,
    });
  });

  it('does not report search capabilities when a fetch fails', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({ status: 'error' });
    const onSearchCapabilitiesChange = vi.fn();

    render(
      <CatalogGrid
        mediaType="movies"
        onSearchCapabilitiesChange={onSearchCapabilitiesChange}
      />,
    );

    await screen.findByText(/temporarily unavailable/i);
    expect(onSearchCapabilitiesChange).not.toHaveBeenCalled();
  });

  it('starts a fresh feed when remounted for different filters, as CatalogPage does via key', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'Action Movie' })],
        page: 1,
        hasMore: false,
      },
    });
    const { rerender } = render(
      <CatalogGrid
        key="genre-28"
        mediaType="movies"
        filters={{ genre: '28' }}
      />,
    );
    await screen.findByText('Action Movie');

    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'Comedy Movie' })],
        page: 1,
        hasMore: false,
      },
    });
    rerender(
      <CatalogGrid
        key="genre-35"
        mediaType="movies"
        filters={{ genre: '35' }}
      />,
    );

    expect(await screen.findByText('Comedy Movie')).toBeInTheDocument();
    expect(screen.queryByText('Action Movie')).not.toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenNthCalledWith(
      2,
      'movies',
      1,
      undefined,
      undefined,
      undefined,
      { genre: '35' },
    );
  });

  it('reports the availableFilters from a successful fetch to onAvailableFiltersChange', async () => {
    const availableFilters = { genre: [{ value: '28', label: 'Action' }] };
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: { items: [item()], page: 1, hasMore: false, availableFilters },
    });
    const onAvailableFiltersChange = vi.fn();

    render(
      <CatalogGrid
        mediaType="movies"
        onAvailableFiltersChange={onAvailableFiltersChange}
      />,
    );

    await screen.findByText('A Movie');
    expect(onAvailableFiltersChange).toHaveBeenCalledExactlyOnceWith(
      availableFilters,
    );
  });

  it('reports an empty availableFilters object when the response omits it', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: { items: [item()], page: 1, hasMore: false },
    });
    const onAvailableFiltersChange = vi.fn();

    render(
      <CatalogGrid
        mediaType="books"
        onAvailableFiltersChange={onAvailableFiltersChange}
      />,
    );

    await screen.findByText('A Movie');
    expect(onAvailableFiltersChange).toHaveBeenCalledExactlyOnceWith({});
  });

  it('does not report availableFilters when a fetch fails', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({ status: 'error' });
    const onAvailableFiltersChange = vi.fn();

    render(
      <CatalogGrid
        mediaType="movies"
        onAvailableFiltersChange={onAvailableFiltersChange}
      />,
    );

    await screen.findByText(/temporarily unavailable/i);
    expect(onAvailableFiltersChange).not.toHaveBeenCalled();
  });

  it('invokes onClearSearch when the clear-search affordance is clicked', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: { items: [], page: 1, hasMore: false },
    });
    const onClearSearch = vi.fn();

    render(
      <CatalogGrid
        mediaType="movies"
        search="zzzznomatch"
        onClearSearch={onClearSearch}
      />,
    );
    const user = (await import('@testing-library/user-event')).default.setup();
    await user.click(
      await screen.findByRole('button', { name: /clear search/i }),
    );

    expect(onClearSearch).toHaveBeenCalledTimes(1);
  });

  it('shows an error with a retry action when a search page fails to load, just like the popular feed', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({ status: 'error' });

    render(<CatalogGrid mediaType="movies" search="blade runner" />);

    expect(
      await screen.findByText(/temporarily unavailable/i),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });

  it('starts a fresh feed when remounted for a different search query, as CatalogPage does via key', async () => {
    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'Popular Movie' })],
        page: 1,
        hasMore: false,
      },
    });
    // CatalogPage renders <CatalogGrid key={`${mediaType}:${activeSearch}`}
    // .../> — a changed key forces React to unmount the old instance and
    // mount a new one, which is what actually resets state here (not an
    // internal effect). Rerendering with a different `key` reproduces that
    // from the test, mirroring the media-type remount test above.
    const { rerender } = render(
      <CatalogGrid key="movies:" mediaType="movies" search="" />,
    );
    await screen.findByText('Popular Movie');

    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [item({ title: 'Blade Runner' })],
        page: 1,
        hasMore: false,
      },
    });
    rerender(
      <CatalogGrid
        key="movies:blade runner"
        mediaType="movies"
        search="blade runner"
      />,
    );

    expect(await screen.findByText('Blade Runner')).toBeInTheDocument();
    expect(screen.queryByText('Popular Movie')).not.toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenNthCalledWith(
      2,
      'movies',
      1,
      'blade runner',
      undefined,
      undefined,
      undefined,
    );
  });
});
