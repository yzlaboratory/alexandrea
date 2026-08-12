import type { ReactNode } from 'react';
import { Chip, MenuItem, Stack, TextField } from '@mui/material';
import type { CatalogFilterOption } from './catalogApi';

// The MUI select's own "nothing chosen" sentinel — distinct from `genre`
// being null, which TextField's controlled value can't represent directly.
const NO_GENRE_SELECTED = '';

interface FilterControlsProps {
  // Driven entirely by the capability payload the backend returns with each
  // page (ADR 0018) — this component never hardcodes which media types get
  // a genre filter, so #43 adding a Books entry needs no change here.
  availableFilters: Record<string, CatalogFilterOption[]>;
  genre: string | null;
  onGenreChange: (genre: string | null) => void;
}

function FilterControls({
  availableFilters,
  genre,
  onGenreChange,
}: FilterControlsProps): ReactNode {
  const genreOptions = availableFilters.genre;
  if (!genreOptions || genreOptions.length === 0) return null;

  const selectedOption = genreOptions.find((option) => option.value === genre);

  return (
    <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
      <TextField
        select
        label="Genre"
        size="small"
        value={genre ?? NO_GENRE_SELECTED}
        onChange={(event) => {
          const nextValue = event.target.value;
          onGenreChange(nextValue === NO_GENRE_SELECTED ? null : nextValue);
        }}
        sx={{ minWidth: 180 }}
      >
        <MenuItem value={NO_GENRE_SELECTED}>
          <em>Any genre</em>
        </MenuItem>
        {genreOptions.map((option) => (
          <MenuItem key={option.value} value={option.value}>
            {option.label}
          </MenuItem>
        ))}
      </TextField>
      {selectedOption && (
        <Chip
          label={selectedOption.label}
          onDelete={() => {
            onGenreChange(null);
          }}
        />
      )}
    </Stack>
  );
}

export default FilterControls;
