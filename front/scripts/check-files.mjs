import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

export async function checkFiles(page, baseURL, tests) {
  const name = '固定中文资料与长名称'.repeat(18) + '.txt';
  const bytes = Buffer.from(
    '\ufeff中文资料\r\n<script>这里只是文本</script>\r\n',
  );
  const observed = [];
  const listener = (response) => {
    const path = new URL(response.url()).pathname;
    if (/\/files(?::upload|\/|$)/.test(path))
      observed.push({
        path,
        method: response.request().method(),
        status: response.status(),
        hasProtection: !!response.request().headers()['x-csrf-token'],
      });
  };
  page.on('response', listener);
  await page
    .getByLabel('选择一份资料')
    .setInputFiles({ name, mimeType: 'text/plain', buffer: bytes });
  const uploaded = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/files:upload'),
  );
  await page.getByRole('button', { name: '上传资料', exact: true }).click();
  const result = await (await uploaded).json();
  assert.equal(result.items[0].outcome, 'created');
  const file = result.items[0].file;
  await page.getByRole('status').filter({ hasText: '资料已保存' }).waitFor();
  await page
    .locator('.dataset-detail > dl dd')
    .filter({ hasText: /^1 份$/ })
    .waitFor();
  await page
    .locator('.dataset-detail > dl dd')
    .filter({ hasText: new RegExp(`^${bytes.length} 字节$`) })
    .waitFor();
  await page
    .getByRole('region', { name: '资料详情', exact: true })
    .getByText(file.latestVersion.contentHash, { exact: true })
    .waitFor();
  await page.getByRole('button', { name: '预览最新资料', exact: true }).click();
  await page.locator('.file-preview pre').waitFor();
  assert.equal(
    await page.locator('.file-preview pre').textContent(),
    bytes.toString('utf8').slice(1).replaceAll('\r\n', '\n'),
  );
  assert.equal(await page.locator('.file-preview script').count(), 0);
  assert.equal(
    await page.evaluate(() =>
      document.activeElement?.getAttribute('aria-label'),
    ),
    '最新资料预览',
  );
  await page.locator('.file-preview').press('Escape');
  assert.equal(
    await page
      .getByRole('button', { name: '预览最新资料', exact: true })
      .evaluate((node) => node === document.activeElement),
    true,
  );
  const downloading = page.waitForEvent('download');
  await page.getByRole('button', { name: '下载原文件', exact: true }).click();
  const download = await downloading;
  assert.equal(download.suggestedFilename(), name);
  assert.deepEqual(await readFile(await download.path()), bytes);
  await page.getByLabel('选择一份资料').setInputFiles({
    name,
    mimeType: 'text/plain',
    buffer: Buffer.from([0, 255]),
  });
  const rejected = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/files:upload'),
  );
  await page.getByRole('button', { name: '上传资料', exact: true }).click();
  const rejectedResult = await (await rejected).json();
  assert.equal(rejectedResult.items[0].outcome, 'failed');
  await page.locator('.files-panel form [role=alert]').waitFor();
  assert.equal(
    await page
      .locator('.files-panel form [role=alert]')
      .evaluate((node) => node === document.activeElement),
    true,
  );
  await page.getByRole('button', { name: '预览最新资料', exact: true }).click();
  await page.locator('.file-preview pre').waitFor();
  assert.equal(
    await page.locator('.file-preview pre').textContent(),
    bytes.toString('utf8').slice(1).replaceAll('\r\n', '\n'),
  );
  await page.waitForLoadState('networkidle');
  await page.reload();
  await page
    .getByRole('button', { name: new RegExp(name.slice(0, 15)) })
    .click();
  await page.getByRole('button', { name: '预览最新资料', exact: true }).click();
  await page.locator('.file-preview pre').waitFor();
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    assert.equal(
      await page
        .locator('.files-panel')
        .evaluate((node) => node.scrollWidth <= node.clientWidth),
      true,
    );
    await page.screenshot({
      path: `artifacts/live-files-${width}.png`,
      fullPage: true,
    });
  }
  assert.equal(
    await page.evaluate(() => localStorage.length + sessionStorage.length),
    0,
  );
  assert.ok(
    observed.some(
      (item) =>
        item.method === 'POST' && item.status === 200 && item.hasProtection,
    ),
  );
  assert.ok(
    observed.some(
      (item) => item.path.endsWith('/latest/preview') && item.status === 200,
    ),
  );
  assert.ok(
    observed.some(
      (item) => item.path.endsWith('/latest/download') && item.status === 200,
    ),
  );
  page.off('response', listener);
  tests.push({
    name: '真实单份文本上传集中元数据安全预览原字节下载及失败保护',
    operationIds: [
      'listDatasetFiles',
      'uploadFiles',
      'getFile',
      'previewLatestFile',
      'downloadLatestFile',
    ],
    constraintIds: [
      'security.1',
      'security.2',
      'security.3',
      'security.5',
      'security.6',
      'security.7',
    ],
    passed: true,
    requests: observed,
    assertions: [
      '真实发行包Axios请求与后端贯通，BOM和CRLF原字节下载核对',
      '危险HTML仅文本显示，Escape回焦点；非法覆盖失败后原正文仍可读取',
      '刷新后按稳定标识重新读取，长文件名三桌面宽度无横向溢出，截图保留',
    ],
  });
}
