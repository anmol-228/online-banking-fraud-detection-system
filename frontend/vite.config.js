import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * The site is served from the domain root during local development, but from a repository
 * sub-path when published as a GitHub Pages project site. The Pages workflow sets
 * VITE_BASE_PATH accordingly, so the same source builds correctly for both.
 */
const base = process.env.VITE_BASE_PATH || '/';

export default defineConfig({
  base,
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});
