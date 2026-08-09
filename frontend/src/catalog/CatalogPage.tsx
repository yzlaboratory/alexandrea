import {
  type ChangeEvent,
  type KeyboardEvent,
  type ReactNode,
  useEffect,
  useState,
} from 'react';
import { Stack, TextField, Typography } from '@mui/material';
import CatalogGrid from './CatalogGrid';

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

function CatalogPage({ mediaType }: CatalogPageProps): ReactNode {
  const label = MEDIA_TYPE_LABELS[mediaType] ?? mediaType;
  // searchInput is the uncontrolled-feeling text the user is typing;
  // activeSearch is what actually drives the grid, updated only after the
  // debounce settles (or immediately on Enter). Neither is ever written to
  // localStorage or persisted server-side — search is spec'd transient,
  // always empty on a fresh mount.
  const [searchInput, setSearchInput] = useState('');
  const [activeSearch, setActiveSearch] = useState('');

  useEffect(() => {
    const trimmed = searchInput.trim();
    const timeoutId = window.setTimeout(() => {
      setActiveSearch(trimmed);
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      window.clearTimeout(timeoutId);
    };
  }, [searchInput]);

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

  return (
    <Stack spacing={3}>
      <Typography variant="h4" component="h1">
        {label} catalog
      </Typography>
      <TextField
        label={`Search ${label}`}
        value={searchInput}
        onChange={handleSearchInputChange}
        onKeyDown={commitSearchImmediately}
        size="small"
        sx={{ maxWidth: 400 }}
      />
      {/* key resets CatalogGrid's feed whenever the media type or the
          committed search changes, the same remount-over-reset-effect idiom
          CatalogGrid itself documents. */}
      <CatalogGrid
        key={`${mediaType}:${activeSearch}`}
        mediaType={mediaType}
        search={activeSearch}
        onClearSearch={clearSearch}
      />
    </Stack>
  );
}

export default CatalogPage;
