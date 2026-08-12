import type { ReactNode } from 'react';
import { Chip, MenuItem, Stack, TextField } from '@mui/material';
import { CATALOG_FILTER_FIELDS, type CatalogFilterOption } from './catalogApi';

// The MUI select's own "nothing chosen" sentinel — distinct from a filter's
// value being null, which TextField's controlled value can't represent
// directly.
const NO_VALUE_SELECTED = '';

// Human label and "nothing selected" placeholder text per filter field (ADR
// 0018) — a fixed lookup here, not part of the capability payload: the
// backend owns *which* filter kinds are available for a media type,
// FilterControls owns how each one is presented, the same split CatalogPage
// already makes for media-type labels.
const FILTER_FIELD_PRESENTATION: Record<
  string,
  { label: string; anyOptionLabel: string }
> = {
  genre: { label: 'Genre', anyOptionLabel: 'Any genre' },
  originalLanguage: {
    label: 'Original language',
    anyOptionLabel: 'Any language',
  },
  availableInLanguage: {
    label: 'Available in language',
    anyOptionLabel: 'Any language',
  },
};

interface FilterControlsProps {
  // Driven entirely by the capability payload the backend returns with each
  // page (ADR 0018) — this component never hardcodes which media types get
  // which filter kind. A media type can report more than one field (e.g.
  // Movies: genre and originalLanguage), each rendered as its own
  // independently-selectable control.
  availableFilters: Record<string, CatalogFilterOption[]>;
  // The currently-selected value per filter field; a field absent or mapped
  // to null means no value is selected for it.
  selectedFilters: Record<string, string | null>;
  onFilterChange: (field: string, value: string | null) => void;
}

function FilterControls({
  availableFilters,
  selectedFilters,
  onFilterChange,
}: FilterControlsProps): ReactNode {
  const fields = CATALOG_FILTER_FIELDS.filter(
    (field) => (availableFilters[field]?.length ?? 0) > 0,
  );
  if (fields.length === 0) return null;

  return (
    <Stack
      direction="row"
      spacing={2}
      sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 1 }}
    >
      {fields.map((field) => (
        <SingleFilterControl
          key={field}
          field={field}
          options={availableFilters[field] ?? []}
          value={selectedFilters[field] ?? null}
          onChange={(value) => {
            onFilterChange(field, value);
          }}
        />
      ))}
    </Stack>
  );
}

interface SingleFilterControlProps {
  field: string;
  options: CatalogFilterOption[];
  value: string | null;
  onChange: (value: string | null) => void;
}

function SingleFilterControl({
  field,
  options,
  value,
  onChange,
}: SingleFilterControlProps): ReactNode {
  const presentation = FILTER_FIELD_PRESENTATION[field] ?? {
    label: field,
    anyOptionLabel: 'Any',
  };
  const selectedOption = options.find((option) => option.value === value);

  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
      <TextField
        select
        label={presentation.label}
        size="small"
        value={value ?? NO_VALUE_SELECTED}
        onChange={(event) => {
          const nextValue = event.target.value;
          onChange(nextValue === NO_VALUE_SELECTED ? null : nextValue);
        }}
        sx={{ minWidth: 180 }}
      >
        <MenuItem value={NO_VALUE_SELECTED}>
          <em>{presentation.anyOptionLabel}</em>
        </MenuItem>
        {options.map((option) => (
          <MenuItem key={option.value} value={option.value}>
            {option.label}
          </MenuItem>
        ))}
      </TextField>
      {selectedOption && (
        <Chip
          label={selectedOption.label}
          onDelete={() => {
            onChange(null);
          }}
        />
      )}
    </Stack>
  );
}

export default FilterControls;
