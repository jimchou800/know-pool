import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './browser-tests',
  outputDir: './artifacts/browser',
  reporter: 'list',
  use: { baseURL: 'http://127.0.0.1:4173', channel: 'msedge', headless: true },
  webServer: {
    command: 'pnpm dev --port 4173 --strictPort',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: true,
  },
});
