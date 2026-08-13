import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import CatalogPage from './CatalogPage';
import * as catalogApi from './catalogApi';

vi.mock('./catalogApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./catalogApi')>()),
  fetchCatalogPage: vi.fn(),
  fetchCatalogPreference: vi.fn(),
}));

const mockedFetchCatalogPage = vi.mocked(catalogApi.fetchCatalogPage);
const mockedFetchCatalogPreference = vi.mocked(
  catalogApi.fetchCatalogPreference,
);

// What CatalogPage sends once its persisted preference has loaded and no
// filter has ever been touched — every known field explicitly cleared,
// not omitted, the same "always manages every field" contract the single
// genre field had before generalizing past it.
const NO_FILTERS_SENT = {
  genre: null,
  originalLanguage: null,
  availableInLanguage: null,
  runtime: null,
  pageCount: null,
};

describe('CatalogPage', () => {
  beforeEach(() => {
    mockedFetchCatalogPage.mockReset().mockResolvedValue({
      status: 'ok',
      result: { items: [], page: 1, hasMore: false },
    });
    mockedFetchCatalogPreference
      .mockReset()
      .mockResolvedValue({ sortKey: null, sortDirection: null, filters: {} });
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
        NO_FILTERS_SENT,
      );
    });
  });

  it('fetches the persisted preference for the given media type on mount', () => {
    render(<CatalogPage mediaType="books" />);

    expect(mockedFetchCatalogPreference).toHaveBeenCalledWith('books');
  });

  it('shows a loading indicator until the persisted preference resolves', () => {
    mockedFetchCatalogPreference.mockImplementation(
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
    mockedFetchCatalogPreference.mockResolvedValue({
      sortKey: 'title',
      sortDirection: 'asc',
      filters: {},
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
        NO_FILTERS_SENT,
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

    // The still-default sort/filters ride along with the search — the
    // backend (not CatalogGrid) decides per media type which of them its
    // search endpoint can honor (issue 47) and drops the rest.
    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
        'movies',
        1,
        'blade runner',
        'popularity',
        'desc',
        NO_FILTERS_SENT,
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
      NO_FILTERS_SENT,
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
        'popularity',
        'desc',
        NO_FILTERS_SENT,
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
        NO_FILTERS_SENT,
      );
    });
  });

  it('re-fetches the persisted preference when the media type changes', async () => {
    const { rerender } = render(<CatalogPage mediaType="movies" />);
    await screen.findByLabelText('Descending');

    rerender(<CatalogPage mediaType="books" />);

    await waitFor(() => {
      expect(mockedFetchCatalogPreference).toHaveBeenCalledWith('books');
    });
  });

  it('renders no filter controls when the fetched page reports no available filters', async () => {
    render(<CatalogPage mediaType="books" />);
    await screen.findByLabelText('Descending');

    expect(
      screen.queryByRole('combobox', { name: 'Genre' }),
    ).not.toBeInTheDocument();
  });

  it('renders a genre control once the grid reports genre as an available filter', async () => {
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: { genre: [{ value: '28', label: 'Action' }] },
      },
    });

    render(<CatalogPage mediaType="movies" />);

    expect(
      await screen.findByRole('combobox', { name: 'Genre' }),
    ).toBeInTheDocument();
  });

  it('renders both a genre and an original-language control for Movies when both are reported available', async () => {
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: {
          genre: [{ value: '28', label: 'Action' }],
          originalLanguage: [{ value: 'ja', label: 'Japanese' }],
        },
      },
    });

    render(<CatalogPage mediaType="movies" />);

    expect(
      await screen.findByRole('combobox', { name: 'Genre' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('combobox', { name: 'Original language' }),
    ).toBeInTheDocument();
  });

  it('renders a genre and an available-in-language control for Books when both are reported available', async () => {
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: {
          genre: [{ value: 'science_fiction', label: 'Science Fiction' }],
          availableInLanguage: [{ value: 'ger', label: 'German' }],
        },
      },
    });

    render(<CatalogPage mediaType="books" />);

    expect(
      await screen.findByRole('combobox', { name: 'Genre' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('combobox', { name: 'Available in language' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('combobox', { name: 'Original language' }),
    ).not.toBeInTheDocument();
  });

  it('restores a persisted genre into the filter control and its chip on mount', async () => {
    mockedFetchCatalogPreference.mockResolvedValue({
      sortKey: null,
      sortDirection: null,
      filters: { genre: '28' },
    });
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: { genre: [{ value: '28', label: 'Action' }] },
      },
    });

    render(<CatalogPage mediaType="movies" />);

    expect(
      await screen.findByRole('combobox', { name: 'Genre' }),
    ).toHaveTextContent('Action');
    expect(await screen.findAllByText('Action')).toHaveLength(2);
    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
        'movies',
        1,
        undefined,
        'popularity',
        'desc',
        { ...NO_FILTERS_SENT, genre: '28' },
      );
    });
  });

  it('restores a persisted original language alongside genre, each in its own control', async () => {
    mockedFetchCatalogPreference.mockResolvedValue({
      sortKey: null,
      sortDirection: null,
      filters: { genre: '28', originalLanguage: 'ja' },
    });
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: {
          genre: [{ value: '28', label: 'Action' }],
          originalLanguage: [{ value: 'ja', label: 'Japanese' }],
        },
      },
    });

    render(<CatalogPage mediaType="movies" />);

    expect(
      await screen.findByRole('combobox', { name: 'Genre' }),
    ).toHaveTextContent('Action');
    expect(
      screen.getByRole('combobox', { name: 'Original language' }),
    ).toHaveTextContent('Japanese');
    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
        'movies',
        1,
        undefined,
        'popularity',
        'desc',
        {
          genre: '28',
          originalLanguage: 'ja',
          availableInLanguage: null,
          runtime: null,
          pageCount: null,
        },
      );
    });
  });

  it('selecting a genre updates the grid and preserves the current sort and search', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: {
          genre: [
            { value: '28', label: 'Action' },
            { value: '35', label: 'Comedy' },
          ],
        },
      },
    });
    render(<CatalogPage mediaType="movies" />);
    await screen.findByRole('combobox', { name: 'Genre' });

    await user.click(screen.getByRole('combobox', { name: 'Genre' }));
    await user.click(await screen.findByRole('option', { name: 'Comedy' }));

    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenLastCalledWith(
        'movies',
        1,
        undefined,
        'popularity',
        'desc',
        { ...NO_FILTERS_SENT, genre: '35' },
      );
    });
  });

  it('selecting an original language updates the grid and preserves the current genre', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    mockedFetchCatalogPreference.mockResolvedValue({
      sortKey: null,
      sortDirection: null,
      filters: { genre: '28' },
    });
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: {
          genre: [{ value: '28', label: 'Action' }],
          originalLanguage: [{ value: 'ja', label: 'Japanese' }],
        },
      },
    });
    render(<CatalogPage mediaType="movies" />);
    await screen.findByRole('combobox', { name: 'Original language' });

    await user.click(
      screen.getByRole('combobox', { name: 'Original language' }),
    );
    await user.click(await screen.findByRole('option', { name: 'Japanese' }));

    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenLastCalledWith(
        'movies',
        1,
        undefined,
        'popularity',
        'desc',
        {
          genre: '28',
          originalLanguage: 'ja',
          availableInLanguage: null,
          runtime: null,
          pageCount: null,
        },
      );
    });
    // The genre chip survives selecting the unrelated original-language
    // control — two different filter kinds combine rather than one
    // replacing the other. "Action" appears twice: once as the genre
    // dropdown's selected display value, once as the chip's label.
    expect(screen.getAllByText('Action')).toHaveLength(2);
  });

  it('selecting a different genre replaces rather than combines with the previous one', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    mockedFetchCatalogPreference.mockResolvedValue({
      sortKey: null,
      sortDirection: null,
      filters: { genre: '28' },
    });
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: {
          genre: [
            { value: '28', label: 'Action' },
            { value: '35', label: 'Comedy' },
          ],
        },
      },
    });
    render(<CatalogPage mediaType="movies" />);
    await screen.findByRole('combobox', { name: 'Genre' });

    await user.click(screen.getByRole('combobox', { name: 'Genre' }));
    await user.click(await screen.findByRole('option', { name: 'Comedy' }));

    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenLastCalledWith(
        'movies',
        1,
        undefined,
        'popularity',
        'desc',
        { ...NO_FILTERS_SENT, genre: '35' },
      );
    });
    expect(screen.queryByText('Action')).not.toBeInTheDocument();
  });

  it('does not render a "Clear filters" button when no filter is active', async () => {
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: { genre: [{ value: '28', label: 'Action' }] },
      },
    });

    render(<CatalogPage mediaType="movies" />);
    await screen.findByRole('combobox', { name: 'Genre' });

    expect(
      screen.queryByRole('button', { name: 'Clear filters' }),
    ).not.toBeInTheDocument();
  });

  it('clicking "Clear filters" with several filters active resets them all in one action, preserving the current sort', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    mockedFetchCatalogPreference.mockResolvedValue({
      sortKey: 'title',
      sortDirection: 'asc',
      filters: { genre: '28', originalLanguage: 'ja' },
    });
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: {
          genre: [{ value: '28', label: 'Action' }],
          originalLanguage: [{ value: 'ja', label: 'Japanese' }],
        },
      },
    });
    render(<CatalogPage mediaType="movies" />);
    await screen.findByRole('button', { name: 'Clear filters' });

    await user.click(screen.getByRole('button', { name: 'Clear filters' }));

    // One fetch reflecting both fields cleared at once, not one per field —
    // and the persisted sort (title/asc) survives untouched.
    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenLastCalledWith(
        'movies',
        1,
        undefined,
        'title',
        'asc',
        NO_FILTERS_SENT,
      );
    });
    expect(screen.queryByText('Action')).not.toBeInTheDocument();
    expect(screen.queryByText('Japanese')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Clear filters' }),
    ).not.toBeInTheDocument();
  });

  it('clicking "Clear filters" does not touch an active search', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    mockedFetchCatalogPreference.mockResolvedValue({
      sortKey: null,
      sortDirection: null,
      filters: { genre: '28' },
    });
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: { genre: [{ value: '28', label: 'Action' }] },
      },
    });
    render(<CatalogPage mediaType="movies" />);
    await user.type(
      screen.getByRole('textbox', { name: /search movies/i }),
      'blade runner{Enter}',
    );
    await screen.findByRole('button', { name: 'Clear filters' });

    await user.click(screen.getByRole('button', { name: 'Clear filters' }));

    expect(screen.getByRole('textbox', { name: /search movies/i })).toHaveValue(
      'blade runner',
    );
    // Search stays untouched by "Clear filters", and the just-cleared genre
    // rides along with the search request too — same "send everything,
    // let the backend decide" contract as every other search fetch.
    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenLastCalledWith(
        'movies',
        1,
        'blade runner',
        'popularity',
        'desc',
        NO_FILTERS_SENT,
      );
    });
  });

  it('deselecting the genre chip clears the filter and reverts to the unfiltered grid', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    mockedFetchCatalogPreference.mockResolvedValue({
      sortKey: null,
      sortDirection: null,
      filters: { genre: '28' },
    });
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: { genre: [{ value: '28', label: 'Action' }] },
      },
    });
    render(<CatalogPage mediaType="movies" />);
    await screen.findByTestId('CancelIcon');

    await user.click(screen.getByTestId('CancelIcon'));

    // null, not omitted: CatalogService distinguishes an explicit clear
    // from "genre wasn't mentioned" (which falls back to whatever's
    // persisted) — sending undefined here would silently resurrect the
    // just-deselected genre server-side instead of clearing it.
    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenLastCalledWith(
        'movies',
        1,
        undefined,
        'popularity',
        'desc',
        NO_FILTERS_SENT,
      );
    });
  });

  // --- Issue 47: a search disables the sort/filter fields ADR 0018's
  // "Behavior under active text search" table says the provider's search
  // endpoint can't honor, shows a "not available while searching" note on
  // the affected controls, and restores their exact prior values once the
  // search clears.

  it('a Movies search disables genre and sort with a note, and clearing it restores the exact prior values', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    mockedFetchCatalogPreference.mockResolvedValue({
      sortKey: 'title',
      sortDirection: 'asc',
      filters: { genre: '28' },
    });
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: { genre: [{ value: '28', label: 'Action' }] },
      },
    });
    render(<CatalogPage mediaType="movies" />);
    expect(
      await screen.findByRole('combobox', { name: 'Genre' }),
    ).toHaveTextContent('Action');

    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: { genre: [{ value: '28', label: 'Action' }] },
        disabledFilters: ['genre', 'originalLanguage', 'runtime'],
        sortDisabled: true,
      },
    });
    await user.type(
      screen.getByRole('textbox', { name: /search movies/i }),
      'blade runner{Enter}',
    );

    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: 'Genre' })).toHaveAttribute(
        'aria-disabled',
        'true',
      );
    });
    expect(screen.getByRole('combobox', { name: 'Sort by' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    expect(
      screen.getAllByText('Not available while searching').length,
    ).toBeGreaterThan(0);
    // The prior values are preserved, not reset, while locked.
    expect(screen.getByRole('combobox', { name: 'Genre' })).toHaveTextContent(
      'Action',
    );
    expect(screen.getByRole('combobox', { name: 'Sort by' })).toHaveTextContent(
      'Title',
    );

    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: { genre: [{ value: '28', label: 'Action' }] },
      },
    });
    await user.click(screen.getByRole('button', { name: /clear search/i }));

    await waitFor(() => {
      expect(
        screen.getByRole('combobox', { name: 'Genre' }),
      ).not.toHaveAttribute('aria-disabled', 'true');
    });
    expect(screen.getByRole('combobox', { name: 'Genre' })).toHaveTextContent(
      'Action',
    );
    expect(
      screen.getByRole('combobox', { name: 'Sort by' }),
    ).not.toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('combobox', { name: 'Sort by' })).toHaveTextContent(
      'Title',
    );
    expect(
      screen.queryByText('Not available while searching'),
    ).not.toBeInTheDocument();
    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenLastCalledWith(
        'movies',
        1,
        undefined,
        'title',
        'asc',
        { ...NO_FILTERS_SENT, genre: '28' },
      );
    });
  });

  it('a locked genre control does not open its options while a Movies search is active', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    mockedFetchCatalogPreference.mockResolvedValue({
      sortKey: null,
      sortDirection: null,
      filters: { genre: '28' },
    });
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: {
          genre: [
            { value: '28', label: 'Action' },
            { value: '35', label: 'Comedy' },
          ],
        },
      },
    });
    render(<CatalogPage mediaType="movies" />);
    await screen.findByRole('combobox', { name: 'Genre' });

    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: {
          genre: [
            { value: '28', label: 'Action' },
            { value: '35', label: 'Comedy' },
          ],
        },
        disabledFilters: ['genre', 'originalLanguage', 'runtime'],
        sortDisabled: true,
      },
    });
    await user.type(
      screen.getByRole('textbox', { name: /search movies/i }),
      'blade runner{Enter}',
    );
    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: 'Genre' })).toHaveAttribute(
        'aria-disabled',
        'true',
      );
    });

    await user.click(screen.getByRole('combobox', { name: 'Genre' }));

    expect(
      screen.queryByRole('option', { name: 'Comedy' }),
    ).not.toBeInTheDocument();
  });

  it('a Games search disables only sort, leaving genre selectable and combined with the search request', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    mockedFetchCatalogPreference.mockResolvedValue({
      sortKey: 'title',
      sortDirection: 'asc',
      filters: { genre: '5' },
    });
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: { genre: [{ value: '5', label: 'Shooter' }] },
      },
    });
    render(<CatalogPage mediaType="games" />);
    await screen.findByRole('combobox', { name: 'Genre' });

    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: { genre: [{ value: '5', label: 'Shooter' }] },
        disabledFilters: [],
        sortDisabled: true,
      },
    });
    await user.type(
      screen.getByRole('textbox', { name: /search games/i }),
      'witcher{Enter}',
    );

    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: 'Sort by' })).toHaveAttribute(
        'aria-disabled',
        'true',
      );
    });
    expect(screen.getByRole('combobox', { name: 'Genre' })).not.toHaveAttribute(
      'aria-disabled',
      'true',
    );
    expect(screen.getByRole('combobox', { name: 'Genre' })).toHaveTextContent(
      'Shooter',
    );
    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenLastCalledWith(
        'games',
        1,
        'witcher',
        'title',
        'asc',
        { ...NO_FILTERS_SENT, genre: '5' },
      );
    });
  });

  it('a Books search disables nothing — sort, genre, and page count all stay active and combine with the search', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    mockedFetchCatalogPreference.mockResolvedValue({
      sortKey: 'external_rating',
      sortDirection: 'desc',
      filters: { genre: 'science_fiction', pageCount: '300,400' },
    });
    mockedFetchCatalogPage.mockResolvedValue({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: {
          genre: [{ value: 'science_fiction', label: 'Science Fiction' }],
          pageCount: [],
        },
      },
    });
    render(<CatalogPage mediaType="books" />);
    await screen.findByRole('combobox', { name: 'Genre' });

    mockedFetchCatalogPage.mockResolvedValueOnce({
      status: 'ok',
      result: {
        items: [],
        page: 1,
        hasMore: false,
        availableFilters: {
          genre: [{ value: 'science_fiction', label: 'Science Fiction' }],
          pageCount: [],
        },
        disabledFilters: [],
        sortDisabled: false,
      },
    });
    await user.type(
      screen.getByRole('textbox', { name: /search books/i }),
      'dune{Enter}',
    );

    await waitFor(() => {
      expect(mockedFetchCatalogPage).toHaveBeenLastCalledWith(
        'books',
        1,
        'dune',
        'external_rating',
        'desc',
        { ...NO_FILTERS_SENT, genre: 'science_fiction', pageCount: '300,400' },
      );
    });
    expect(
      screen.getByRole('combobox', { name: 'Sort by' }),
    ).not.toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('combobox', { name: 'Genre' })).not.toHaveAttribute(
      'aria-disabled',
      'true',
    );
    expect(screen.getByLabelText('Min')).not.toBeDisabled();
    expect(screen.getByLabelText('Max')).not.toBeDisabled();
    expect(
      screen.queryByText('Not available while searching'),
    ).not.toBeInTheDocument();
  });
});
