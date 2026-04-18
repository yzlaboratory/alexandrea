import { describe, expect, test } from 'vitest';

import { posterURL } from './types';

describe('posterURL', () => {
  test('returns null when poster_path is missing', () => {
    expect(posterURL(undefined)).toBe(null);
    expect(posterURL('')).toBe(null);
  });

  test('builds an image.tmdb.org URL with default w342 size', () => {
    expect(posterURL('/abc.jpg')).toBe('https://image.tmdb.org/t/p/w342/abc.jpg');
  });

  test('honors an explicit size', () => {
    expect(posterURL('/abc.jpg', 'w500')).toBe('https://image.tmdb.org/t/p/w500/abc.jpg');
  });
});
