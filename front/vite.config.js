import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    exclude: ['browser-tests/**', 'node_modules/**', 'dist/**'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.js', 'src/**/*.vue'],
      exclude: ['src/main.js'],
      thresholds: { lines: 80, branches: 80 },
    },
  },
});
