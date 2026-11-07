import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * The site is served from the domain root during local development, but from a repository
 * sub-path when published as a GitHub Pages project site. The Pages workflow sets
 * VITE_BASE_PATH accordingly, so the same source builds correctly for both.
 */
const base = process.env.VITE_BASE_PATH || '/';

/**
 * A build meant for the public internet (VITE_PUBLIC_BUILD=true) must name its backend explicitly.
 *
 * The application falls back to http://localhost:8080 when VITE_API_BASE_URL is empty, which is
 * right on a developer machine and silently wrong anywhere else: the page would load, every call
 * would fail, and nothing would say why. For a public build that fallback is refused at build time,
 * and so is any API address that is not https:// or that points at this machine.
 */
function requirePublicApiUrl(mode) {
  const env = { ...loadEnv(mode, process.cwd(), 'VITE_'), ...process.env };
  if (env.VITE_PUBLIC_BUILD !== 'true' || env.VITE_APP_MODE === 'showcase') {
    return;
  }
  const url = (env.VITE_API_BASE_URL || '').trim();
  const problem = !url
    ? 'VITE_API_BASE_URL is not set'
    : !url.startsWith('https://')
      ? `VITE_API_BASE_URL (${url}) must start with https://`
      : /\/\/(localhost|127\.|\[::1\])/i.test(url)
        ? `VITE_API_BASE_URL (${url}) points at this machine`
        : null;
  if (problem) {
    throw new Error(`Public build refused: ${problem}. Set it to the deployed backend's https URL.`);
  }
}

export default defineConfig(({ mode }) => {
  requirePublicApiUrl(mode);
  return {
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
  };
});
