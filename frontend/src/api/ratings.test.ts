import { describe, expect, test } from 'vitest';

import { averageScore } from './types';
import type { Rating } from './types';

function rating(score: number): Rating {
  return {
    id: `r-${score}`,
    title_id: 't',
    user_id: 'u',
    display_name: 'U',
    score,
    note: '',
    rated_at: '2026-04-18T00:00:00Z',
  };
}

describe('averageScore', () => {
  test('returns null for no ratings', () => {
    expect(averageScore([])).toBe(null);
  });

  test('returns the lone score when only one rating exists', () => {
    expect(averageScore([rating(3)])).toBe(3);
  });

  test('returns the mean when both users have rated', () => {
    expect(averageScore([rating(4), rating(2)])).toBe(3);
  });

  test('handles zero as a valid score (not a missing rating)', () => {
    expect(averageScore([rating(0), rating(4)])).toBe(2);
  });
});
