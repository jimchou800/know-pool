import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { releaseOptions } from './release-options.js';

const { baseURL, channel } = releaseOptions(process.argv.slice(2));
const browser = await chromium.launch({ channel, headless: true });
try {
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  const response = await page.goto(baseURL);
  assert.equal(response.status(), 200);
  await page
    .getByRole('status')
    .filter({ hasText: '本机服务已连接' })
    .waitFor();
  const status = await page.request.get(`${baseURL}/api/v1/system/status`);
  assert.equal(status.status(), 200);
  const datasets = await page.request.get(
    `${baseURL}/api/v1/datasets?limit=20`,
  );
  assert.equal(datasets.status(), 200);
  assert.ok(Array.isArray((await datasets.json()).items));
  assert.deepEqual(errors, [], '首页出现未处理的页面异常');
  await mkdir(`artifacts/${channel}`, { recursive: true });
  await page.screenshot({ path: `artifacts/${channel}/runtime-home.png` });
  console.log('程序启动、首页、会话与数据集读取通过。');
} finally {
  await browser.close();
}
