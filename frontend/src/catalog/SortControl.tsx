import type { ReactNode } from 'react';
import {
  MenuItem,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
} from '@mui/material';

// Keys/values match what CatalogService validates and CatalogController's
// `sort`/`direction` query params expect verbatim.
export const SORT_OPTIONS: readonly { key: string; label: string }[] = [
  { key: 'popularity', label: 'Popularity' },
  { key: 'release_date', label: 'Release date' },
  { key: 'title', label: 'Title' },
  { key: 'external_rating', label: 'External rating' },
];

interface SortControlProps {
  sortKey: string;
  direction: string;
  onChange: (sortKey: string, direction: string) => void;
  // See ADR 0018 for when sort is locked. This component just renders
  // locked with a note rather than disappearing, and sortKey/direction keep
  // displaying whatever was selected before search started, since this
  // component never clears them itself.
  disabled?: boolean;
}

function SortControl({
  sortKey,
  direction,
  onChange,
  disabled = false,
}: SortControlProps): ReactNode {
  return (
    <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
      <TextField
        select
        label="Sort by"
        size="small"
        value={sortKey}
        disabled={disabled}
        helperText={disabled ? 'Not available while searching' : undefined}
        onChange={(event) => {
          onChange(event.target.value, direction);
        }}
        sx={{ minWidth: 180 }}
      >
        {SORT_OPTIONS.map((option) => (
          <MenuItem key={option.key} value={option.key}>
            {option.label}
          </MenuItem>
        ))}
      </TextField>
      <ToggleButtonGroup
        value={direction}
        exclusive
        size="small"
        disabled={disabled}
        aria-label="Sort direction"
        onChange={(_event, nextDirection: string | null) => {
          if (nextDirection) onChange(sortKey, nextDirection);
        }}
      >
        <ToggleButton value="asc" aria-label="Ascending">
          Ascending
        </ToggleButton>
        <ToggleButton value="desc" aria-label="Descending">
          Descending
        </ToggleButton>
      </ToggleButtonGroup>
    </Stack>
  );
}

export default SortControl;
