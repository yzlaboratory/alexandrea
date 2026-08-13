import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CatalogSurfaceRoute from './CatalogSurfaceRoute';
import { MEDIA_TYPES, type MediaType } from './AppShell';
import * as catalogApi from '../catalog/catalogApi';

vi.mock('../catalog/catalogApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../catalog/catalogApi')>()),
  fetchCatalogPage: vi.fn(),
  fetchCatalogPreference: vi.fn(),
}));

const mockedFetchCatalogPage = vi.mocked(catalogApi.fetchCatalogPage);
const mockedFetchCatalogPreference = vi.mocked(
  catalogApi.fetchCatalogPreference,
);

// Test fixture only — the media types under test come from AppShell's
// own MEDIA_TYPES (imported below), not a hardcoded copy here.
const EXPECTED_HEADINGS: Record<MediaType, string> = {
  movies: 'Movies catalog',
  tv: 'TV catalog',
  books: 'Books catalog',
  games: 'Games catalog',
};

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
      result: { items: [], page: 1, hasMore: false },
    });
    mockedFetchCatalogPreference
      .mockReset()
      .mockResolvedValue({ sortKey: null, sortDirection: null, filters: {} });
  });

  it.each(MEDIA_TYPES)(
    'renders the real %s catalog page',
    async (mediaType) => {
      renderAt(`/${mediaType}/catalog`);

      expect(
        await screen.findByRole('heading', {
          name: EXPECTED_HEADINGS[mediaType],
        }),
      ).toBeInTheDocument();
      expect(mockedFetchCatalogPage).toHaveBeenCalledWith(
        mediaType,
        1,
        undefined,
        'popularity',
        'desc',
        {
          genre: null,
          originalLanguage: null,
          availableInLanguage: null,
          runtime: null,
          pageCount: null,
        },
      );
    },
  );

  it('renders the generic placeholder for an unrecognized media type', () => {
    renderAt('/podcasts/catalog');

    expect(screen.getByText(/nothing to show/i)).toBeInTheDocument();
    expect(mockedFetchCatalogPage).not.toHaveBeenCalled();
  });
});
