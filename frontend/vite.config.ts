import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Vite + Vitest config merged. ADR 0014 fixes Vite as the frontend bundler;
// Vitest reuses Vite's transform pipeline so there is no second config to
// maintain. Importing `defineConfig` from `vitest/config` (rather than `vite`)
// is the supported path for typing the merged `test` block.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/setupTests.ts'],
    css: true,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov', 'html'],
      // LCOV is the report SonarQube Cloud consumes (per ADR 0022).
      reportsDirectory: './coverage',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/main.tsx', 'src/vite-env.d.ts'],
    },
  },
});
