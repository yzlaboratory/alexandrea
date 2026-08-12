import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import FilterControls from './FilterControls';

const MOVIE_GENRES = [
  { value: '28', label: 'Action' },
  { value: '35', label: 'Comedy' },
];

describe('FilterControls', () => {
  it('renders nothing when the capability payload has no genre entry for this media type', () => {
    const { container } = render(
      <FilterControls
        availableFilters={{}}
        genre={null}
        onGenreChange={vi.fn()}
      />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when the genre entry is present but empty', () => {
    const { container } = render(
      <FilterControls
        availableFilters={{ genre: [] }}
        genre={null}
        onGenreChange={vi.fn()}
      />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('offers every genre option from the capability payload', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    render(
      <FilterControls
        availableFilters={{ genre: MOVIE_GENRES }}
        genre={null}
        onGenreChange={vi.fn()}
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
        genre={null}
        onGenreChange={vi.fn()}
      />,
    );

    expect(screen.queryByText('Action')).not.toBeInTheDocument();
    expect(screen.queryByText('Comedy')).not.toBeInTheDocument();
  });

  it('shows the given genre as the selected dropdown value and as a chip', () => {
    render(
      <FilterControls
        availableFilters={{ genre: MOVIE_GENRES }}
        genre="28"
        onGenreChange={vi.fn()}
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
        genre="999-unknown"
        onGenreChange={vi.fn()}
      />,
    );

    expect(screen.queryByText('Action')).not.toBeInTheDocument();
    expect(screen.queryByText('Comedy')).not.toBeInTheDocument();
  });

  it('selecting a different genre calls onGenreChange with the new value, replacing rather than adding', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    const onGenreChange = vi.fn();
    render(
      <FilterControls
        availableFilters={{ genre: MOVIE_GENRES }}
        genre="28"
        onGenreChange={onGenreChange}
      />,
    );

    await user.click(screen.getByRole('combobox', { name: 'Genre' }));
    await user.click(screen.getByRole('option', { name: 'Comedy' }));

    expect(onGenreChange).toHaveBeenCalledExactlyOnceWith('35');
  });

  it('selecting "Any genre" deselects the current genre', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    const onGenreChange = vi.fn();
    render(
      <FilterControls
        availableFilters={{ genre: MOVIE_GENRES }}
        genre="28"
        onGenreChange={onGenreChange}
      />,
    );

    await user.click(screen.getByRole('combobox', { name: 'Genre' }));
    await user.click(screen.getByRole('option', { name: 'Any genre' }));

    expect(onGenreChange).toHaveBeenCalledExactlyOnceWith(null);
  });

  it('deleting the chip deselects the current genre', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    const onGenreChange = vi.fn();
    render(
      <FilterControls
        availableFilters={{ genre: MOVIE_GENRES }}
        genre="28"
        onGenreChange={onGenreChange}
      />,
    );

    await user.click(screen.getByTestId('CancelIcon'));

    expect(onGenreChange).toHaveBeenCalledExactlyOnceWith(null);
  });
});
