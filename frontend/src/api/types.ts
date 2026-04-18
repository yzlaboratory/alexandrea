// Mirror of the JSON shapes returned by the Go backend. Hand-written for
// now; if drift becomes painful we'll generate per OPEN-QUESTIONS.md.

export type Kind = 'movie' | 'series';

export type Status = 'want' | 'watching' | 'watched' | 'abandoned';

export const STATUS_LABEL: Record<Status, string> = {
  want: 'Want',
  watching: 'Watching',
  watched: 'Watched',
  abandoned: 'Abandoned',
};

export type Title = {
  id: string;
  tmdb_id: number;
  kind: Kind;
  display_title: string;
  release_year: number;
  synopsis: string;
  poster_path?: string;
  added_at: string;
};

export type Rating = {
  id: string;
  title_id: string;
  user_id: string;
  display_name: string;
  score: number;
  note?: string;
  rated_at: string;
};

export type LibraryEntry = {
  id: string;
  status: Status;
  added_at: string;
  status_updated_at: string;
  title: Title;
  ratings: Rating[];
};

// averageScore returns the mean of all ratings on an entry, or null if
// none exist. Used on the Watched tab per spec 03-ratings.md.
export function averageScore(ratings: Rating[]): number | null {
  if (ratings.length === 0) return null;
  const sum = ratings.reduce((acc, r) => acc + r.score, 0);
  return sum / ratings.length;
}

export type SearchResult = {
  tmdb_id: number;
  kind: Kind;
  title: string;
  year?: number;
  poster_path?: string;
  overview?: string;
};

// posterURL renders TMDB's CDN URL given a relative path. Returns null when
// the title has no poster — callers can fall back to a placeholder.
export function posterURL(
  posterPath: string | undefined,
  size: 'w342' | 'w500' = 'w342',
): string | null {
  if (!posterPath) return null;
  return `https://image.tmdb.org/t/p/${size}${posterPath}`;
}
