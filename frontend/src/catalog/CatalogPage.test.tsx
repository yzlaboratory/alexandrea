import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import CatalogPage from './CatalogPage';
import * as catalogApi from './catalogApi';

vi.mock('./catalogApi', () => ({
  fetchCatalogPage: vi.fn(),
}));

const mockedFetchCatalogPage = vi.mocked(catalogApi.fetchCatalogPage);

describe('CatalogPage', () => {
  beforeEach(() => {
    mockedFetchCatalogPage.mockReset().mockResolvedValue({
      status: 'ok',
      result: { items: [], page: 1, hasMore: false },
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows a heading naming the known media type', () => {
    render(<CatalogPage mediaType="movies" />);

    expect(
      screen.getByRole('heading', { name: 'Movies catalog' }),
    ).toBeInTheDocument();
  });

  it('falls back to the raw media type string for an unrecognised value', () => {
    render(<CatalogPage mediaType="podcasts" />);

    expect(
      screen.getByRole('heading', { name: 'podcasts catalog' }),
    ).toBeInTheDocument();
  });

  it("fetches that media type's first catalog page", () => {
    render(<CatalogPage mediaType="movies" />);

    expect(mockedFetchCatalogPage).toHaveBeenCalledWith('movies', 1);
  });

  it('renders a search input that is empty on a fresh mount', () => {
    render(<CatalogPage mediaType="movies" />);

    expect(screen.getByRole('textbox', { name: /search movies/i })).toHaveValue(
      '',
    );
  });

  it('pressing Enter after typing a search commits it immediately, without waiting for the debounce', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    render(<CatalogPage mediaType="movies" />);

    await user.type(
      screen.getByRole('textbox', { name: /search movies/i }),
      'blade runner{Enter}',
    );

    expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
      'movies',
      1,
      'blade runner',
    );
  });

  it('typing a search eventually replaces the popular grid with search results once the debounce settles', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
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
      },
    });
    render(<CatalogPage mediaType="movies" />);

    // Typed without a trailing Enter — this exercises the plain debounce
    // path rather than the immediate-commit-on-Enter shortcut above.
    await user.type(
      screen.getByRole('textbox', { name: /search movies/i }),
      'blade runner',
    );

    expect(await screen.findByText('Blade Runner')).toBeInTheDocument();
  });

  it('a search with no matches shows a message offering to clear the search', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    render(<CatalogPage mediaType="movies" />);

    await user.type(
      screen.getByRole('textbox', { name: /search movies/i }),
      'zzzznomatch{Enter}',
    );

    expect(await screen.findByText(/no results for/i)).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /clear search/i }),
    ).toBeInTheDocument();
  });

  it('clicking "Clear search" empties the search box and reverts to the popular feed', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    render(<CatalogPage mediaType="movies" />);
    const input = screen.getByRole('textbox', { name: /search movies/i });
    await user.type(input, 'zzzznomatch{Enter}');
    await screen.findByRole('button', { name: /clear search/i });

    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [
          {
            provider: 'TMDB',
            externalId: '1',
            mediaType: 'movies',
            title: 'Popular Movie',
            coverUrl: null,
            releaseDate: '2024-01-01',
            externalRating: 7,
            externalRatingScale: 10,
          },
        ],
        page: 1,
        hasMore: false,
      },
    });

    await user.click(screen.getByRole('button', { name: /clear search/i }));

    expect(input).toHaveValue('');
    expect(await screen.findByText('Popular Movie')).toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenLastCalledWith('movies', 1);
  });

  it('search state does not survive a remount, the same as a fresh page load', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    const { unmount } = render(<CatalogPage mediaType="movies" />);
    await user.type(
      screen.getByRole('textbox', { name: /search movies/i }),
      'blade runner{Enter}',
    );
    expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
      'movies',
      1,
      'blade runner',
    );
    unmount();

    render(<CatalogPage mediaType="movies" />);

    expect(screen.getByRole('textbox', { name: /search movies/i })).toHaveValue(
      '',
    );
  });
});
