import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { readFile, readdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';

const pdfRoot = dirname(
  createRequire(import.meta.url).resolve('pdfjs-dist/package.json'),
);
const pdfAssets = {
  name: 'pdf-local-assets',
  async generateBundle() {
    for (const directory of ['cmaps', 'standard_fonts', 'wasm']) {
      for (const name of await readdir(join(pdfRoot, directory))) {
        this.emitFile({
          type: 'asset',
          fileName: `assets/pdfjs/${directory}/${name}`,
          source: await readFile(join(pdfRoot, directory, name)),
        });
      }
    }
  },
  configureServer(server) {
    server.middlewares.use('/assets/pdfjs', async (request, response, next) => {
      const match = /^\/(cmaps|standard_fonts|wasm)\/([\w.-]+)$/.exec(
        request.url,
      );
      if (!match) return next();
      try {
        response.setHeader(
          'Content-Type',
          match[2].endsWith('.wasm')
            ? 'application/wasm'
            : 'application/octet-stream',
        );
        response.end(await readFile(join(pdfRoot, match[1], match[2])));
      } catch {
        next();
      }
    });
  },
};

export default defineConfig({
  plugins: [vue(), pdfAssets],
  server: {
    proxy: {
      '/api': {
        target: process.env.KNOW_POOL_BACKEND_URL || 'http://127.0.0.1:8080',
      },
    },
  },
  test: {
    environment: 'jsdom',
    exclude: [
      'browser-tests/**',
      'node_modules/**',
      'dist/**',
      'target/**',
      'artifacts/**',
    ],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.js', 'src/**/*.vue'],
      exclude: ['src/main.js', 'src/prototype-main.js'],
      thresholds: { lines: 80, branches: 80 },
    },
  },
});
