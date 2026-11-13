import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
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
