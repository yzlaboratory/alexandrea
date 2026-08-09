import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import CatalogPage from './CatalogPage';
import * as catalogApi from './catalogApi';

vi.mock('./catalogApi', () => ({
  fetchCatalogPage: vi.fn(),
}));

const mockedFetchCatalogPage = vi.mocked(catalogApi.fetchCatalogPage);

describe('CatalogPage', () => {
  beforeEach(() => {
    mockedFetchCatalogPage.mockReset().mockResolvedValue({
      status: 'ok',
      result: { entries: [], page: 1, hasMore: false },
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows a heading naming the known media type', () => {
    render(<CatalogPage mediaType="movies" />);

    expect(
      screen.getByRole('heading', { name: 'Movies catalog' }),
    ).toBeInTheDocument();
  });

  it('falls back to the raw media type string for an unrecognised value', () => {
    render(<CatalogPage mediaType="podcasts" />);

    expect(
      screen.getByRole('heading', { name: 'podcasts catalog' }),
    ).toBeInTheDocument();
  });

  it("fetches that media type's first catalog page", () => {
    render(<CatalogPage mediaType="movies" />);

    expect(mockedFetchCatalogPage).toHaveBeenCalledWith('movies', 1);
  });
});
