import http from 'node:http';
import type { AddressInfo } from 'node:net';

// Canned TMDB responses keyed to ENTLIB_TMDB_BASE_URL. The shape mirrors the
// fields the backend's tmdb client decodes (see backend/internal/tmdb/tmdb.go),
// which is a strict subset of the live TMDB v3 API.

const SEARCH_RESPONSE = {
  page: 1,
  total_pages: 1,
  total_results: 2,
  results: [
    {
      id: 603,
      media_type: 'movie',
      title: 'The Matrix',
      release_date: '1999-03-30',
      poster_path: '/aOIuZAjPaRIE6CMzbazvcHuHXDc.jpg',
      overview:
        'Set in the 22nd century, The Matrix tells the story of a computer hacker who joins a group of underground insurgents fighting the vast and powerful computers who now rule the earth.',
      popularity: 100,
    },
    {
      id: 624860,
      media_type: 'movie',
      title: 'The Matrix Resurrections',
      release_date: '2021-12-16',
      poster_path: '/8c4a8kE7PizaGQQnditMmI1xbRp.jpg',
      overview: 'Plagued by strange memories, Neo’s life takes an unexpected turn.',
      popularity: 50,
    },
  ],
};

const MOVIE_603 = {
  id: 603,
  title: 'The Matrix',
  release_date: '1999-03-30',
  poster_path: '/aOIuZAjPaRIE6CMzbazvcHuHXDc.jpg',
  overview: SEARCH_RESPONSE.results[0].overview,
};

export type TMDBStubHits = { search: number; movie603: number; unknown: number };

export type TMDBStub = {
  url: string;
  hits: TMDBStubHits;
  close: () => Promise<void>;
};

export async function startTMDBStub(): Promise<TMDBStub> {
  const hits: TMDBStubHits = { search: 0, movie603: 0, unknown: 0 };

  const server = http.createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://stub');
    res.setHeader('Content-Type', 'application/json');
    if (url.pathname === '/search/multi') {
      hits.search++;
      res.end(JSON.stringify(SEARCH_RESPONSE));
      return;
    }
    if (url.pathname === '/movie/603') {
      hits.movie603++;
      res.end(JSON.stringify(MOVIE_603));
      return;
    }
    hits.unknown++;
    res.statusCode = 404;
    res.end(JSON.stringify({ error: `tmdb-stub: unhandled ${url.pathname}` }));
  });

  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      server.off('error', reject);
      resolve();
    });
  });
  const { port } = server.address() as AddressInfo;

  return {
    url: `http://127.0.0.1:${port}`,
    hits,
    close: () =>
      new Promise<void>((resolve, reject) => {
        server.close((err) => (err ? reject(err) : resolve()));
      }),
  };
}
