import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import CatalogPage from './CatalogPage';
import * as catalogApi from './catalogApi';

vi.mock('./catalogApi', () => ({
  fetchCatalogPage: vi.fn(),
  fetchSortPreference: vi.fn(),
}));

const mockedFetchCatalogPage = vi.mocked(catalogApi.fetchCatalogPage);
const mockedFetchSortPreference = vi.mocked(catalogApi.fetchSortPreference);

describe('CatalogPage', () => {
  beforeEach(() => {
    mockedFetchCatalogPage.mockReset().mockResolvedValue({
      status: 'ok',
      result: { items: [], page: 1, hasMore: false },
    });
    mockedFetchSortPreference
      .mockReset()
      .mockResolvedValue({ sortKey: null, sortDirection: null });
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

  it("fetches that media type's first catalog page once the sort preference resolves", async () => {
    render(<CatalogPage mediaType="movies" />);

    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
        'movies',
        1,
        undefined,
        'popularity',
        'desc',
      );
    });
  });

  it('fetches the persisted sort preference for the given media type on mount', () => {
    render(<CatalogPage mediaType="books" />);

    expect(mockedFetchSortPreference).toHaveBeenCalledWith('books');
  });

  it('shows a loading indicator until the persisted sort preference resolves', () => {
    mockedFetchSortPreference.mockImplementation(
      () => new Promise(() => undefined),
    );

    render(<CatalogPage mediaType="movies" />);

    expect(screen.getByLabelText('Loading')).toBeInTheDocument();
  });

  it('defaults the sort control to popularity/descending when nothing is stored', async () => {
    render(<CatalogPage mediaType="movies" />);

    expect(await screen.findByLabelText('Descending')).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('combobox', { name: 'Sort by' })).toHaveTextContent(
      'Popularity',
    );
  });

  it('restores a persisted sort preference into the sort control on mount', async () => {
    mockedFetchSortPreference.mockResolvedValue({
      sortKey: 'title',
      sortDirection: 'asc',
    });

    render(<CatalogPage mediaType="movies" />);

    expect(await screen.findByLabelText('Ascending')).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('combobox', { name: 'Sort by' })).toHaveTextContent(
      'Title',
    );
    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
        'movies',
        1,
        undefined,
        'title',
        'asc',
      );
    });
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
    await screen.findByLabelText('Descending');

    await user.type(
      screen.getByRole('textbox', { name: /search movies/i }),
      'blade runner{Enter}',
    );

    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
        'movies',
        1,
        'blade runner',
      );
    });
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
    await screen.findByLabelText('Descending');

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
    await screen.findByLabelText('Descending');

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
    await screen.findByLabelText('Descending');
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
    expect(mockedFetchCatalogPage).toHaveBeenLastCalledWith(
      'movies',
      1,
      undefined,
      'popularity',
      'desc',
    );
  });

  it('search state does not survive a remount, the same as a fresh page load', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    const { unmount } = render(<CatalogPage mediaType="movies" />);
    await screen.findByLabelText('Descending');
    await user.type(
      screen.getByRole('textbox', { name: /search movies/i }),
      'blade runner{Enter}',
    );
    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
        'movies',
        1,
        'blade runner',
      );
    });
    unmount();

    render(<CatalogPage mediaType="movies" />);

    expect(screen.getByRole('textbox', { name: /search movies/i })).toHaveValue(
      '',
    );
  });

  it('changing the sort control updates the grid and preserves the current search', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    render(<CatalogPage mediaType="movies" />);
    await screen.findByLabelText('Descending');

    await user.click(screen.getByRole('combobox', { name: 'Sort by' }));
    await user.click(await screen.findByRole('option', { name: 'Title' }));

    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenLastCalledWith(
        'movies',
        1,
        undefined,
        'title',
        'desc',
      );
    });
  });

  it('re-fetches the persisted sort preference when the media type changes', async () => {
    const { rerender } = render(<CatalogPage mediaType="movies" />);
    await screen.findByLabelText('Descending');

    rerender(<CatalogPage mediaType="books" />);

    await waitFor(() => {
      expect(mockedFetchSortPreference).toHaveBeenCalledWith('books');
    });
  });
});
