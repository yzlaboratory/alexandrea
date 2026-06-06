import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import App from './App';

describe('App', () => {
  it('renders the scaffold heading', () => {
    render(<App />);
    expect(
      screen.getByRole('heading', { name: /alexandrea/i }),
    ).toBeInTheDocument();
  });

  it('renders the scaffold-alive body copy', () => {
    render(<App />);
    expect(screen.getByText(/scaffold is alive/i)).toBeInTheDocument();
  });
});
