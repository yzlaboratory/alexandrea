import {
  type ChangeEvent,
  type KeyboardEvent,
  type ReactNode,
  useEffect,
  useState,
} from 'react';
import { CircularProgress, Stack, TextField, Typography } from '@mui/material';
import CatalogGrid from './CatalogGrid';
import FilterControls from './FilterControls';
import SortControl from './SortControl';
import { fetchCatalogPreference, type CatalogFilterOption } from './catalogApi';

interface CatalogPageProps {
  mediaType: string;
}

const MEDIA_TYPE_LABELS: Record<string, string> = {
  movies: 'Movies',
  tv: 'TV',
  books: 'Books',
  games: 'Games',
};

// Long enough that a fast typist doesn't fire a request per keystroke, short
// enough that the grid still feels responsive once they pause.
const SEARCH_DEBOUNCE_MS = 300;

// The sort a user who has never chosen one sees — matches the Examples table
// in #3's "Sorting catalog results" scenario.
const DEFAULT_SORT_KEY = 'popularity';
const DEFAULT_SORT_DIRECTION = 'desc';

interface PreferenceState {
  sortKey: string;
  direction: string;
  // null means "no genre filter applied" — distinct from the whole state
  // being null, which means "still loading the persisted preference".
  genre: string | null;
}

function CatalogPage({ mediaType }: CatalogPageProps): ReactNode {
  const label = MEDIA_TYPE_LABELS[mediaType] ?? mediaType;
  // searchInput is the uncontrolled-feeling text the user is typing;
  // activeSearch is what actually drives the grid, updated only after the
  // debounce settles (or immediately on Enter). Neither is ever written to
  // localStorage or persisted server-side — search is spec'd transient,
  // always empty on a fresh mount.
  const [searchInput, setSearchInput] = useState('');
  const [activeSearch, setActiveSearch] = useState('');
  // null means "still loading the persisted preference" — the grid, sort
  // control, and filter controls all wait for it so the page never flashes
  // the defaults and then jumps to a different restored state.
  const [preference, setPreference] = useState<PreferenceState | null>(null);
  // Which filters are currently available for this media_type (ADR 0018),
  // as reported by CatalogGrid's most recent fetch — FilterControls renders
  // from this rather than a hardcoded per-media-type table.
  const [availableFilters, setAvailableFilters] = useState<
    Record<string, CatalogFilterOption[]>
  >({});

  useEffect(() => {
    const trimmed = searchInput.trim();
    const timeoutId = window.setTimeout(() => {
      setActiveSearch(trimmed);
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      window.clearTimeout(timeoutId);
    };
  }, [searchInput]);

  // Fetch-on-display: resolve this media type's persisted Catalog sort and
  // genre filter as soon as the page mounts, the same idiom as
  // SessionContext's own session fetch. preference's initial value is
  // already null (the "loading" state), so — unlike a component that
  // survives a prop change — this effect needs no explicit reset:
  // CatalogSurfaceRoute remounts CatalogPage on every media-type change via
  // `key={mediaType}`, so a fresh instance (and a fresh null) is what "the
  // media type changed" already looks like here. Guards against a stale
  // response landing after unmount.
  useEffect(() => {
    let cancelled = false;
    void fetchCatalogPreference(mediaType).then((stored) => {
      if (cancelled) return;
      setPreference({
        sortKey: stored.sortKey ?? DEFAULT_SORT_KEY,
        direction: stored.sortDirection ?? DEFAULT_SORT_DIRECTION,
        genre: stored.genre,
      });
    });
    return () => {
      cancelled = true;
    };
  }, [mediaType]);

  function handleSearchInputChange(event: ChangeEvent<HTMLInputElement>): void {
    setSearchInput(event.target.value);
  }

  function commitSearchImmediately(
    event: KeyboardEvent<HTMLInputElement>,
  ): void {
    if (event.key === 'Enter') {
      setActiveSearch(searchInput.trim());
    }
  }

  function clearSearch(): void {
    setSearchInput('');
    setActiveSearch('');
  }

  function handleSortChange(sortKey: string, direction: string): void {
    setPreference((current) => current && { ...current, sortKey, direction });
  }

  function handleGenreChange(genre: string | null): void {
    setPreference((current) => current && { ...current, genre });
  }

  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">
        {label} catalog
      </Typography>
      <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap' }}>
        <TextField
          label={`Search ${label}`}
          value={searchInput}
          onChange={handleSearchInputChange}
          onKeyDown={commitSearchImmediately}
          size="small"
          sx={{ maxWidth: 400 }}
        />
        {preference && (
          <SortControl
            sortKey={preference.sortKey}
            direction={preference.direction}
            onChange={handleSortChange}
          />
        )}
        {preference && (
          <FilterControls
            availableFilters={availableFilters}
            genre={preference.genre}
            onGenreChange={handleGenreChange}
          />
        )}
      </Stack>
      {preference ? (
        // key resets CatalogGrid's feed whenever the media type, the
        // committed search, the sort, or the genre changes, the same
        // remount-over-reset-effect idiom CatalogGrid itself documents.
        <CatalogGrid
          key={`${mediaType}:${activeSearch}:${preference.sortKey}:${preference.direction}:${preference.genre ?? ''}`}
          mediaType={mediaType}
          search={activeSearch}
          sort={preference.sortKey}
          direction={preference.direction}
          genre={preference.genre ?? undefined}
          onAvailableFiltersChange={setAvailableFilters}
          onClearSearch={clearSearch}
        />
      ) : (
        <Stack sx={{ alignItems: 'center', py: 2 }}>
          <CircularProgress size={24} aria-label="Loading" />
        </Stack>
      )}
    </Stack>
  );
}

export default CatalogPage;
