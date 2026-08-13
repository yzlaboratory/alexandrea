import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import FilterControls from './FilterControls';

const MOVIE_GENRES = [
  { value: '28', label: 'Action' },
  { value: '35', label: 'Comedy' },
];

const ORIGINAL_LANGUAGES = [
  { value: 'en', label: 'English' },
  { value: 'ja', label: 'Japanese' },
];

describe('FilterControls', () => {
  it('renders nothing when the capability payload has no filter entries for this media type', () => {
    const { container } = render(
      <FilterControls
        availableFilters={{}}
        selectedFilters={{}}
        onFilterChange={vi.fn()}
      />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when the genre entry is present but empty', () => {
    const { container } = render(
      <FilterControls
        availableFilters={{ genre: [] }}
        selectedFilters={{}}
        onFilterChange={vi.fn()}
      />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('offers every genre option from the capability payload', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    render(
      <FilterControls
        availableFilters={{ genre: MOVIE_GENRES }}
        selectedFilters={{}}
        onFilterChange={vi.fn()}
      />,
    );

    await user.click(screen.getByRole('combobox', { name: 'Genre' }));

    expect(screen.getByRole('option', { name: 'Action' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Comedy' })).toBeInTheDocument();
    expect(
      screen.getByRole('option', { name: 'Any genre' }),
    ).toBeInTheDocument();
  });

  it('shows no chip when no genre is selected', () => {
    render(
      <FilterControls
        availableFilters={{ genre: MOVIE_GENRES }}
        selectedFilters={{}}
        onFilterChange={vi.fn()}
      />,
    );

    expect(screen.queryByText('Action')).not.toBeInTheDocument();
    expect(screen.queryByText('Comedy')).not.toBeInTheDocument();
  });

  it('shows the given genre as the selected dropdown value and as a chip', () => {
    render(
      <FilterControls
        availableFilters={{ genre: MOVIE_GENRES }}
        selectedFilters={{ genre: '28' }}
        onFilterChange={vi.fn()}
      />,
    );

    expect(screen.getByRole('combobox', { name: 'Genre' })).toHaveTextContent(
      'Action',
    );
    // "Action" appears twice: once as the dropdown's selected display value,
    // once as the chip's label.
    expect(screen.getAllByText('Action')).toHaveLength(2);
  });

  it('shows no chip when the given genre does not match any available option', () => {
    render(
      <FilterControls
        availableFilters={{ genre: MOVIE_GENRES }}
        selectedFilters={{ genre: '999-unknown' }}
        onFilterChange={vi.fn()}
      />,
    );

    expect(screen.queryByText('Action')).not.toBeInTheDocument();
    expect(screen.queryByText('Comedy')).not.toBeInTheDocument();
  });

  it('selecting a different genre calls onFilterChange with the field and new value, replacing rather than adding', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    const onFilterChange = vi.fn();
    render(
      <FilterControls
        availableFilters={{ genre: MOVIE_GENRES }}
        selectedFilters={{ genre: '28' }}
        onFilterChange={onFilterChange}
      />,
    );

    await user.click(screen.getByRole('combobox', { name: 'Genre' }));
    await user.click(screen.getByRole('option', { name: 'Comedy' }));

    expect(onFilterChange).toHaveBeenCalledExactlyOnceWith('genre', '35');
  });

  it('selecting "Any genre" deselects the current genre', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    const onFilterChange = vi.fn();
    render(
      <FilterControls
        availableFilters={{ genre: MOVIE_GENRES }}
        selectedFilters={{ genre: '28' }}
        onFilterChange={onFilterChange}
      />,
    );

    await user.click(screen.getByRole('combobox', { name: 'Genre' }));
    await user.click(screen.getByRole('option', { name: 'Any genre' }));

    expect(onFilterChange).toHaveBeenCalledExactlyOnceWith('genre', null);
  });

  it('deleting the chip deselects the current genre', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    const onFilterChange = vi.fn();
    render(
      <FilterControls
        availableFilters={{ genre: MOVIE_GENRES }}
        selectedFilters={{ genre: '28' }}
        onFilterChange={onFilterChange}
      />,
    );

    await user.click(screen.getByTestId('CancelIcon'));

    expect(onFilterChange).toHaveBeenCalledExactlyOnceWith('genre', null);
  });

  it('renders both a genre and an original-language control for Movies, each independently', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    const onFilterChange = vi.fn();
    render(
      <FilterControls
        availableFilters={{
          genre: MOVIE_GENRES,
          originalLanguage: ORIGINAL_LANGUAGES,
        }}
        selectedFilters={{ genre: '28', originalLanguage: 'ja' }}
        onFilterChange={onFilterChange}
      />,
    );

    expect(screen.getByRole('combobox', { name: 'Genre' })).toHaveTextContent(
      'Action',
    );
    expect(
      screen.getByRole('combobox', { name: 'Original language' }),
    ).toHaveTextContent('Japanese');

    await user.click(
      screen.getByRole('combobox', { name: 'Original language' }),
    );
    await user.click(screen.getByRole('option', { name: 'English' }));

    // The genre control is unaffected by changing the other field.
    expect(onFilterChange).toHaveBeenCalledExactlyOnceWith(
      'originalLanguage',
      'en',
    );
    expect(screen.getByRole('combobox', { name: 'Genre' })).toHaveTextContent(
      'Action',
    );
  });

  it('renders the fields in a fixed order regardless of the capability payload key order', () => {
    render(
      <FilterControls
        availableFilters={{
          originalLanguage: ORIGINAL_LANGUAGES,
          genre: MOVIE_GENRES,
        }}
        selectedFilters={{}}
        onFilterChange={vi.fn()}
      />,
    );

    const comboboxes = screen.getAllByRole('combobox');
    expect(comboboxes[0]).toHaveAccessibleName('Genre');
    expect(comboboxes[1]).toHaveAccessibleName('Original language');
  });

  it('does not show a "Clear filters" button when no filter is active', () => {
    render(
      <FilterControls
        availableFilters={{ genre: MOVIE_GENRES }}
        selectedFilters={{}}
        onFilterChange={vi.fn()}
      />,
    );

    expect(
      screen.queryByRole('button', { name: 'Clear filters' }),
    ).not.toBeInTheDocument();
  });

  it('does not show a "Clear filters" button when the only selected value is stale (matches no option)', () => {
    render(
      <FilterControls
        availableFilters={{ genre: MOVIE_GENRES }}
        selectedFilters={{ genre: '999-unknown' }}
        onFilterChange={vi.fn()}
      />,
    );

    expect(
      screen.queryByRole('button', { name: 'Clear filters' }),
    ).not.toBeInTheDocument();
  });

  it('shows a "Clear filters" button once one filter is active', () => {
    render(
      <FilterControls
        availableFilters={{ genre: MOVIE_GENRES }}
        selectedFilters={{ genre: '28' }}
        onFilterChange={vi.fn()}
      />,
    );

    expect(
      screen.getByRole('button', { name: 'Clear filters' }),
    ).toBeInTheDocument();
  });

  it('clicking "Clear filters" with one filter active clears just that field', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    const onFilterChange = vi.fn();
    render(
      <FilterControls
        availableFilters={{ genre: MOVIE_GENRES }}
        selectedFilters={{ genre: '28' }}
        onFilterChange={onFilterChange}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Clear filters' }));

    expect(onFilterChange).toHaveBeenCalledExactlyOnceWith('genre', null);
  });

  it('clicking "Clear filters" with several filters active clears every one of them and none of the untouched fields', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    const onFilterChange = vi.fn();
    render(
      <FilterControls
        availableFilters={{
          genre: MOVIE_GENRES,
          originalLanguage: ORIGINAL_LANGUAGES,
          runtime: [],
        }}
        selectedFilters={{
          genre: '28',
          originalLanguage: 'ja',
          runtime: '90,180',
        }}
        onFilterChange={onFilterChange}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Clear filters' }));

    expect(onFilterChange).toHaveBeenCalledTimes(3);
    expect(onFilterChange).toHaveBeenCalledWith('genre', null);
    expect(onFilterChange).toHaveBeenCalledWith('originalLanguage', null);
    expect(onFilterChange).toHaveBeenCalledWith('runtime', null);
  });

  it('clicking "Clear filters" leaves an already-unset field alone, calling onFilterChange only for the active ones', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    const onFilterChange = vi.fn();
    render(
      <FilterControls
        availableFilters={{
          genre: MOVIE_GENRES,
          originalLanguage: ORIGINAL_LANGUAGES,
        }}
        selectedFilters={{ genre: '28' }}
        onFilterChange={onFilterChange}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Clear filters' }));

    expect(onFilterChange).toHaveBeenCalledExactlyOnceWith('genre', null);
  });

  it('renders only the available-in-language control for Books, not original language', () => {
    render(
      <FilterControls
        availableFilters={{
          genre: MOVIE_GENRES,
          availableInLanguage: [{ value: 'ger', label: 'German' }],
        }}
        selectedFilters={{}}
        onFilterChange={vi.fn()}
      />,
    );

    expect(
      screen.getByRole('combobox', { name: 'Available in language' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('combobox', { name: 'Original language' }),
    ).not.toBeInTheDocument();
  });
});
