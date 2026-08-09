import '@testing-library/jest-dom/vitest';

// jsdom has no IntersectionObserver implementation. Most tests that render
// CatalogGrid (directly or via CatalogPage) don't care about triggering it —
// they just need mounting not to throw. CatalogGrid.test.tsx itself installs
// a richer, triggerable fake via vi.stubGlobal for the tests that do need to
// simulate the sentinel scrolling into view; that per-test stub takes
// precedence over this default and is torn down after those tests.
if (typeof globalThis.IntersectionObserver === 'undefined') {
  class NoopIntersectionObserver implements IntersectionObserver {
    readonly root = null;
    readonly rootMargin = '';
    readonly thresholds: readonly number[] = [];
    observe = (): void => undefined;
    unobserve = (): void => undefined;
    disconnect = (): void => undefined;
    takeRecords(): IntersectionObserverEntry[] {
      return [];
    }
  }
  globalThis.IntersectionObserver = NoopIntersectionObserver;
}
