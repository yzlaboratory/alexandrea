import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import SortControl from './SortControl';

describe('SortControl', () => {
  it('shows the given sort key as the selected option', () => {
    render(<SortControl sortKey="title" direction="asc" onChange={vi.fn()} />);

    expect(screen.getByRole('combobox', { name: 'Sort by' })).toHaveTextContent(
      'Title',
    );
  });

  it('marks the given direction as pressed', () => {
    render(
      <SortControl sortKey="popularity" direction="desc" onChange={vi.fn()} />,
    );

    expect(screen.getByLabelText('Descending')).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByLabelText('Ascending')).toHaveAttribute(
      'aria-pressed',
      'false',
    );
  });

  it('calls onChange with the new sort key and the current direction when the sort dropdown changes', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    const onChange = vi.fn();
    render(
      <SortControl sortKey="popularity" direction="desc" onChange={onChange} />,
    );

    await user.click(screen.getByRole('combobox', { name: 'Sort by' }));
    await user.click(await screen.findByRole('option', { name: 'Title' }));

    expect(onChange).toHaveBeenCalledWith('title', 'desc');
  });

  it('calls onChange with the current sort key and the new direction when the direction toggle changes', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    const onChange = vi.fn();
    render(
      <SortControl sortKey="title" direction="desc" onChange={onChange} />,
    );

    await user.click(screen.getByLabelText('Ascending'));

    expect(onChange).toHaveBeenCalledWith('title', 'asc');
  });

  it('does not call onChange when clicking the already-selected direction toggle', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    const onChange = vi.fn();
    render(<SortControl sortKey="title" direction="asc" onChange={onChange} />);

    // MUI's exclusive ToggleButtonGroup reports null for re-clicking the
    // already-selected button — this pins that onChange is not called with
    // a nonsensical null direction in that case.
    await user.click(screen.getByLabelText('Ascending'));

    expect(onChange).not.toHaveBeenCalled();
  });

  it('offers all four ADR 0018 sorts', async () => {
    const user = (await import('@testing-library/user-event')).default.setup();
    render(
      <SortControl sortKey="popularity" direction="desc" onChange={vi.fn()} />,
    );

    await user.click(screen.getByRole('combobox', { name: 'Sort by' }));

    expect(
      screen.getByRole('option', { name: 'Popularity' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('option', { name: 'Release date' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Title' })).toBeInTheDocument();
    expect(
      screen.getByRole('option', { name: 'External rating' }),
    ).toBeInTheDocument();
  });
});
