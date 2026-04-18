import { Film } from 'lucide-react';

import { posterURL } from '../api/types';

type Props = {
  posterPath: string | undefined;
  title: string;
  size?: 'w342' | 'w500';
  className?: string;
};

export function Poster({ posterPath, title, size = 'w342', className = '' }: Props) {
  const url = posterURL(posterPath, size);
  if (!url) {
    return (
      <div
        className={`flex items-center justify-center rounded-md bg-slate-800 text-slate-600 ${className}`}
        aria-label={`No poster for ${title}`}
      >
        <Film className="h-8 w-8" aria-hidden />
      </div>
    );
  }
  return (
    <img
      src={url}
      alt={`Poster for ${title}`}
      loading="lazy"
      className={`rounded-md object-cover ${className}`}
    />
  );
}
