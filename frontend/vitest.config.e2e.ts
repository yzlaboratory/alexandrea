import { defineConfig } from 'vitest/config';

// Standalone E2E config. Kept apart from vite.config.ts so the default
// `pnpm test` run stays jsdom-only and fast. Use `pnpm test:e2e`.
export default defineConfig({
  test: {
    include: ['e2e/**/*.test.ts'],
    environment: 'node',
    globals: true,
    pool: 'forks',
    poolOptions: { forks: { singleFork: true } },
    testTimeout: 120_000,
    hookTimeout: 120_000,
    fileParallelism: false,
  },
});
