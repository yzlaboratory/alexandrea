import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/login': 'http://127.0.0.1:8080',
      '/logout': 'http://127.0.0.1:8080',
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: false,
    // E2E lives in ./e2e and runs under vitest.config.e2e.ts in node — keep
    // the default jsdom run from picking those files up.
    exclude: ['node_modules', 'dist', 'e2e/**'],
  },
});
