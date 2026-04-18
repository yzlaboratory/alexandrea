import { renderHook, act } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

import { useDebounced } from './search';

describe('useDebounced', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  test('returns the initial value immediately', () => {
    const { result } = renderHook(() => useDebounced('hello', 250));
    expect(result.current).toBe('hello');
  });

  test('does not update before delay elapses', () => {
    const { result, rerender } = renderHook(({ v }) => useDebounced(v, 250), {
      initialProps: { v: 'a' },
    });
    rerender({ v: 'ab' });
    rerender({ v: 'abc' });

    act(() => {
      vi.advanceTimersByTime(100);
    });
    expect(result.current).toBe('a');
  });

  test('settles on the latest value after delay elapses', () => {
    const { result, rerender } = renderHook(({ v }) => useDebounced(v, 250), {
      initialProps: { v: 'a' },
    });
    rerender({ v: 'ab' });
    rerender({ v: 'abc' });

    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(result.current).toBe('abc');
  });
});
