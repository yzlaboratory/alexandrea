import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import CatalogTile, {
  formatExternalRating,
  formatReleaseDate,
} from './CatalogTile';
import type { CatalogEntry } from './catalogApi';

const BASE_ENTRY: CatalogEntry = {
  provider: 'TMDB',
  externalId: '603692',
  mediaType: 'movies',
  title: 'John Wick: Chapter 4',
  coverUrl: 'https://image.tmdb.org/t/p/w500/x.jpg',
  releaseDate: '2023-03-22',
  externalRating: 7.8,
  externalRatingScale: 10,
};

describe('formatExternalRating', () => {
  it('formats a present rating with one decimal, the native scale, and the provider', () => {
    expect(formatExternalRating(BASE_ENTRY)).toBe('7.8/10 TMDB');
  });

  it('formats a whole-number rating with a trailing .0 rather than dropping it', () => {
    expect(formatExternalRating({ ...BASE_ENTRY, externalRating: 8 })).toBe(
      '8.0/10 TMDB',
    );
  });

  it('formats a rating of exactly zero as a real rating, not as "no rating"', () => {
    expect(formatExternalRating({ ...BASE_ENTRY, externalRating: 0 })).toBe(
      '0.0/10 TMDB',
    );
  });

  it('shows a not-yet-rated message when the rating is null', () => {
    expect(formatExternalRating({ ...BASE_ENTRY, externalRating: null })).toBe(
      'Not yet rated',
    );
  });
});

describe('formatReleaseDate', () => {
  it('passes a present date through unchanged', () => {
    expect(formatReleaseDate('2023-03-22')).toBe('2023-03-22');
  });

  it('shows an unknown-date message when the date is null', () => {
    expect(formatReleaseDate(null)).toBe('Release date unknown');
  });
});

describe('CatalogTile', () => {
  it('renders the title, release date, and rating', () => {
    render(<CatalogTile entry={BASE_ENTRY} />);

    expect(
      screen.getByRole('heading', { name: 'John Wick: Chapter 4' }),
    ).toBeInTheDocument();
    expect(screen.getByText('2023-03-22')).toBeInTheDocument();
    expect(screen.getByText('7.8/10 TMDB')).toBeInTheDocument();
  });

  it('renders the cover image with alt text naming the title', () => {
    render(<CatalogTile entry={BASE_ENTRY} />);

    expect(
      screen.getByRole('img', { name: 'John Wick: Chapter 4 cover' }),
    ).toHaveAttribute('src', BASE_ENTRY.coverUrl);
  });

  it('renders a placeholder instead of a broken image when there is no cover', () => {
    render(<CatalogTile entry={{ ...BASE_ENTRY, coverUrl: null }} />);

    expect(
      screen.getByRole('img', { name: /has no cover art/i }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('img', { name: /cover$/ }),
    ).not.toBeInTheDocument();
  });

  it('shows the not-yet-rated message for an entry with no rating', () => {
    render(<CatalogTile entry={{ ...BASE_ENTRY, externalRating: null }} />);

    expect(screen.getByText('Not yet rated')).toBeInTheDocument();
  });

  it('shows the unknown-date message for an entry with no release date', () => {
    render(<CatalogTile entry={{ ...BASE_ENTRY, releaseDate: null }} />);

    expect(screen.getByText('Release date unknown')).toBeInTheDocument();
  });
});
