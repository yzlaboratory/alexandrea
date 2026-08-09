import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CatalogSurfaceRoute from './CatalogSurfaceRoute';
import * as catalogApi from '../catalog/catalogApi';

vi.mock('../catalog/catalogApi', () => ({
  fetchCatalogPage: vi.fn(),
}));

const mockedFetchCatalogPage = vi.mocked(catalogApi.fetchCatalogPage);

function renderAt(path: string): void {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/:mediaType/catalog" element={<CatalogSurfaceRoute />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('CatalogSurfaceRoute', () => {
  beforeEach(() => {
    mockedFetchCatalogPage.mockReset().mockResolvedValue({
      status: 'ok',
      result: { entries: [], page: 1, hasMore: false },
    });
  });

  it('renders the real Movies catalog page on /movies/catalog', async () => {
    renderAt('/movies/catalog');

    expect(
      await screen.findByRole('heading', { name: 'Movies catalog' }),
    ).toBeInTheDocument();
    expect(mockedFetchCatalogPage).toHaveBeenCalledWith('movies', 1);
  });

  it('renders the generic placeholder for a media type with no real catalog yet', () => {
    renderAt('/tv/catalog');

    expect(screen.getByText(/later slice/i)).toBeInTheDocument();
    expect(mockedFetchCatalogPage).not.toHaveBeenCalled();
  });
});
