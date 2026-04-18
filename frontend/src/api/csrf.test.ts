import { afterEach, describe, expect, test } from 'vitest';

import { readCsrfToken } from './csrf';

function setCookies(...pairs: string[]) {
  // jsdom implements `document.cookie` as an additive setter like browsers do.
  Object.defineProperty(document, 'cookie', {
    configurable: true,
    writable: true,
    value: pairs.join('; '),
  });
}

afterEach(() => {
  setCookies();
});

describe('readCsrfToken', () => {
  test('returns empty string when cookie is missing', () => {
    setCookies('other=1');
    expect(readCsrfToken()).toBe('');
  });

  test('extracts token regardless of position', () => {
    setCookies('a=1', 'ENTLIB_CSRF=abc.def-xyz', 'b=2');
    expect(readCsrfToken()).toBe('abc.def-xyz');
  });

  test('percent-decodes values', () => {
    setCookies('ENTLIB_CSRF=' + encodeURIComponent('a b+c'));
    expect(readCsrfToken()).toBe('a b+c');
  });

  test('ignores cookies that only share a prefix', () => {
    setCookies('ENTLIB_CSRF_OTHER=wrong', 'ENTLIB_CSRF=right');
    expect(readCsrfToken()).toBe('right');
  });
});
