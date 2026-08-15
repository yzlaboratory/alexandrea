import { type ReactNode, useEffect, useState } from 'react';
import {
  Button,
  Chip,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import {
  CATALOG_FILTER_FIELDS,
  CATALOG_RANGE_FILTER_FIELDS,
  type CatalogFilterOption,
} from './catalogApi';

// The MUI select's own "nothing chosen" sentinel — distinct from a filter's
// value being null, which TextField's controlled value can't represent
// directly.
const NO_VALUE_SELECTED = '';

// Human label and "nothing selected" placeholder text per filter field (ADR
// 0018) — a fixed lookup here, not part of the capability payload: the
// backend owns *which* filter kinds are available for a media type,
// FilterControls owns how each one is presented, the same split CatalogPage
// already makes for media-type labels. anyOptionLabel is unused for the two
// range fields (there's no "any" menu item to render for those).
const FILTER_FIELD_PRESENTATION: Record<
  string,
  { label: string; anyOptionLabel?: string }
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
  runtime: { label: 'Runtime (minutes)' },
  pageCount: { label: 'Page count' },
};

function isRangeField(field: (typeof CATALOG_FILTER_FIELDS)[number]): boolean {
  return (CATALOG_RANGE_FILTER_FIELDS as readonly string[]).includes(field);
}

// A range field reports as available via key presence alone (its options
// array is always empty by construction — see CATALOG_RANGE_FILTER_FIELDS)
// while the other fields report availability through a non-empty options
// array; an empty array from one of those means "temporarily unreachable
// vocabulary" (CatalogService), which correctly hides the control rather
// than rendering a picker with nothing to pick.
function isFieldAvailable(
  field: (typeof CATALOG_FILTER_FIELDS)[number],
  availableFilters: Record<string, CatalogFilterOption[]>,
): boolean {
  return isRangeField(field)
    ? field in availableFilters
    : (availableFilters[field]?.length ?? 0) > 0;
}

// Mirrors exactly what makes SingleFilterControl/RangeFilterControl render
// their own delete chip for this field, so "Clear filters" derives its
// visibility and its click action from that same per-field check rather
// than inventing a new signal.
function isFieldActive(
  field: (typeof CATALOG_FILTER_FIELDS)[number],
  value: string | null,
  availableFilters: Record<string, CatalogFilterOption[]>,
): boolean {
  if (value === null) return false;
  if (isRangeField(field)) return true;
  return (availableFilters[field] ?? []).some(
    (option) => option.value === value,
  );
}

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
  // Fields ADR 0018 locks right now because a text search is active
  // (CatalogService's per-provider "Behavior under active text search"
  // table) — distinct from availableFilters, which is about whether a
  // field exists for this media type at all, not whether it's usable this
  // instant. A locked field keeps rendering with its current value; this
  // component never clears it, so it reactivates with that value intact
  // the moment the caller stops passing it in this list.
  disabledFields?: readonly string[];
}

function FilterControls({
  availableFilters,
  selectedFilters,
  onFilterChange,
  disabledFields = [],
}: FilterControlsProps): ReactNode {
  const fields = CATALOG_FILTER_FIELDS.filter((field) =>
    isFieldAvailable(field, availableFilters),
  );
  if (fields.length === 0) return null;

  const activeFields = fields.filter((field) =>
    isFieldActive(field, selectedFilters[field] ?? null, availableFilters),
  );
  const clearableFields = activeFields.filter(
    (field) => !disabledFields.includes(field),
  );

  function clearAllFilters(): void {
    for (const field of clearableFields) {
      onFilterChange(field, null);
    }
  }

  return (
    <Stack
      direction="row"
      spacing={2}
      sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 1 }}
    >
      {fields.map((field) =>
        isRangeField(field) ? (
          <RangeFilterControl
            key={field}
            field={field}
            value={selectedFilters[field] ?? null}
            disabled={disabledFields.includes(field)}
            onChange={(value) => {
              onFilterChange(field, value);
            }}
          />
        ) : (
          <SingleFilterControl
            key={field}
            field={field}
            options={availableFilters[field] ?? []}
            value={selectedFilters[field] ?? null}
            disabled={disabledFields.includes(field)}
            onChange={(value) => {
              onFilterChange(field, value);
            }}
          />
        ),
      )}
      {clearableFields.length > 0 && (
        <Button size="small" onClick={clearAllFilters}>
          Clear filters
        </Button>
      )}
    </Stack>
  );
}

interface SingleFilterControlProps {
  field: string;
  options: CatalogFilterOption[];
  value: string | null;
  onChange: (value: string | null) => void;
  disabled?: boolean;
}

function SingleFilterControl({
  field,
  options,
  value,
  onChange,
  disabled = false,
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
        disabled={disabled}
        helperText={disabled ? 'Not available while searching' : undefined}
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
          disabled={disabled}
          onDelete={
            disabled
              ? undefined
              : () => {
                  onChange(null);
                }
          }
        />
      )}
    </Stack>
  );
}

interface RangeFilterControlProps {
  field: string;
  // The "<min>,<max>" encoding CatalogRange parses server-side (either side
  // blank means open-ended); null means no range selected at all.
  value: string | null;
  onChange: (value: string | null) => void;
  disabled?: boolean;
}

// Typing digit-by-digit into Min/Max shouldn't fire a fetch (or send a
// malformed intermediate value like "9,") on every keystroke — the same
// debounce idiom CatalogPage's search box uses, applied here because
// nothing else in this control's commit path (FilterControls'
// onFilterChange, CatalogPage's setPreference, CatalogGrid's remount key)
// debounces on its own.
const RANGE_COMMIT_DEBOUNCE_MS = 300;

function RangeFilterControl({
  field,
  value,
  onChange,
  disabled = false,
}: RangeFilterControlProps): ReactNode {
  const presentation = FILTER_FIELD_PRESENTATION[field] ?? { label: field };
  const [min, max] = decodeRange(value);
  // The visible text while the user is mid-edit; committed via onChange
  // only once RANGE_COMMIT_DEBOUNCE_MS has passed with no further change to
  // either field. min/max (derived straight from the `value` prop) is the
  // last *committed* pair, so a caller-driven change to `value` — a
  // restored preference, the chip's delete, "Clear filters" — always wins
  // over an in-flight, not-yet-committed edit.
  const [localMin, setLocalMin] = useState(min);
  const [localMax, setLocalMax] = useState(max);

  useEffect(() => {
    setLocalMin(min);
    setLocalMax(max);
  }, [min, max]);

  useEffect(() => {
    // Nothing to commit — either nothing has been typed since the last
    // commit, or this run is just the sync effect above catching up to a
    // caller-driven `value` change. Skipping this avoids re-sending an
    // unchanged value (and, since FilterControls always passes a fresh
    // object literal down, avoids an extra CatalogGrid remount for no
    // actual change).
    if (localMin === min && localMax === max) return undefined;
    const timeoutId = window.setTimeout(() => {
      onChange(
        localMin === '' && localMax === '' ? null : `${localMin},${localMax}`,
      );
    }, RANGE_COMMIT_DEBOUNCE_MS);
    return () => {
      window.clearTimeout(timeoutId);
    };
  }, [localMin, localMax, min, max, onChange]);

  return (
    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
      <Typography variant="body2" color="text.secondary">
        {presentation.label}
      </Typography>
      <TextField
        type="number"
        label="Min"
        size="small"
        value={localMin}
        disabled={disabled}
        slotProps={{ htmlInput: { min: 0 } }}
        onChange={(event) => {
          setLocalMin(event.target.value);
        }}
        sx={{ width: 90 }}
      />
      <TextField
        type="number"
        label="Max"
        size="small"
        value={localMax}
        disabled={disabled}
        helperText={disabled ? 'Not available while searching' : undefined}
        slotProps={{ htmlInput: { min: 0 } }}
        onChange={(event) => {
          setLocalMax(event.target.value);
        }}
        sx={{ width: 90 }}
      />
      {value !== null && (
        <Chip
          label={presentation.label}
          disabled={disabled}
          onDelete={
            disabled
              ? undefined
              : () => {
                  onChange(null);
                }
          }
        />
      )}
    </Stack>
  );
}

// The inverse of RangeFilterControl's own commit() encoding — split rather
// than parsed/validated here, since an invalid or malformed value can only
// ever originate from this same component's own onChange (CatalogPage just
// stores whatever string it's given), and CatalogService already validates
// and drops anything malformed server-side rather than trusting the client.
function decodeRange(value: string | null): [string, string] {
  if (value === null) return ['', ''];
  const [min = '', max = ''] = value.split(',');
  return [min, max];
}

export default FilterControls;
