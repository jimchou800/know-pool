import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

export async function checkDocumentPreviews(page, tests) {
  const measurements = [];
  const outside = [];
  const requests = [];
  const resourceResponses = [];
  const resourceFailures = [];
  const isPdfResource = (url) =>
    url.includes('/assets/pdfjs/') || url.includes('pdf.worker');
  const responseListener = (response) => {
    if (isPdfResource(response.url()))
      resourceResponses.push({
        path: new URL(response.url()).pathname,
        status: response.status(),
      });
  };
  const failureListener = (request) => {
    if (isPdfResource(request.url()))
      resourceFailures.push(new URL(request.url()).pathname);
  };
  const origin = new URL(page.url()).origin;
  const listener = (request) => {
    if (
      new URL(request.url()).origin !== origin &&
      !request.url().startsWith('blob:') &&
      !request.url().startsWith('data:')
    )
      outside.push(request.url());
    if (
      request.url().includes('/assets/pdfjs/') ||
      request.url().includes('pdf.worker')
    )
      requests.push(new URL(request.url()).pathname);
  };
  page.on('request', listener);
  page.on('response', responseListener);
  page.on('requestfailed', failureListener);
  await page.evaluate(() => {
    window.previewInjected = false;
  });
  const markdown = Buffer.from(
    '\ufeff# 中文安全预览\r\n\r\n|项目|结果|\r\n|-|-|\r\n|资料|保留|\r\n\r\n<script>window.previewInjected=true</script>\r\n\r\n![危险图片](https://blocked.invalid/image)\r\n\r\n[外链](https://blocked.invalid/)\r\n\r\n<img src="https://blocked.invalid/x" onerror="window.previewInjected=true">\r\n',
  );
  async function upload(name, mimeType, buffer) {
    await page
      .getByLabel('选择一份资料', { exact: true })
      .setInputFiles({ name, mimeType, buffer });
    const response = page.waitForResponse(
      (item) =>
        item.url().includes('/files:upload') &&
        item.request().method() === 'POST',
    );
    await page.getByRole('button', { name: '上传资料', exact: true }).click();
    const result = await (await response).json();
    assert.equal(result.items[0].outcome, 'created');
    await page.getByRole('status').filter({ hasText: '资料已保存' }).waitFor();
    await page
      .getByRole('button', { name: '预览最新资料', exact: true })
      .click();
  }
  async function download(bytes) {
    const event = page.waitForEvent('download');
    await page.getByRole('button', { name: '下载原文件', exact: true }).click();
    assert.deepEqual(await readFile(await (await event).path()), bytes);
  }
  await upload('固定危险内容.md', 'text/markdown', markdown);
  await page.locator('.markdown-body h1').waitFor();
  assert.equal(
    await page.locator('.markdown-body h1').textContent(),
    '中文安全预览',
  );
  assert.equal(
    await page
      .locator(
        '.markdown-body script,.markdown-body img,.markdown-body a,.markdown-body [href],.markdown-body [src]',
      )
      .count(),
    0,
  );
  assert.equal(await page.evaluate(() => window.previewInjected), false);
  await page.getByLabel('源行号', { exact: true }).fill('5');
  await page.getByRole('button', { name: '定位源行', exact: true }).click();
  assert.equal(
    await page.evaluate(() => document.activeElement.dataset.line),
    '5',
  );
  await page.getByLabel('源行号', { exact: true }).fill('9999');
  await page.getByRole('button', { name: '定位源行', exact: true }).click();
  await page
    .locator('.text-preview [role=alert]')
    .filter({ hasText: '请输入1至' })
    .waitFor();
  await download(markdown);
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: `artifacts/live-markdown-${width}.png`,
      fullPage: true,
    });
  }
  for (const sample of [
    {
      name: '接近字符预算.md',
      type: 'text/markdown',
      text: '**资料** '.repeat(27000),
      limited: false,
    },
    {
      name: '接近行数预算.txt',
      type: 'text/plain',
      text: '资料\n'.repeat(9998),
      limited: false,
    },
    {
      name: '超过字符预算.md',
      type: 'text/markdown',
      text: '甲'.repeat(200001),
      limited: true,
    },
    {
      name: '超过行数预算.txt',
      type: 'text/plain',
      text: '\n'.repeat(10001),
      limited: true,
    },
  ]) {
    const bytes = Buffer.from(sample.text);
    const started = Date.now();
    await upload(sample.name, sample.type, bytes);
    if (sample.limited) {
      await page
        .locator('.file-preview [role=alert]')
        .filter({ hasText: '资源限制' })
        .waitFor();
    } else {
      await page
        .locator(
          sample.type === 'text/markdown' ? '.markdown-body' : '.source-text',
        )
        .waitFor();
    }
    measurements.push({
      name: sample.name,
      bytes: bytes.length,
      elapsedMilliseconds: Date.now() - started,
      limited: sample.limited,
    });
    await download(bytes);
  }
  const pdf = await readFile(
    '../server/src/test/resources/documents/chinese-two-pages-actions.pdf',
  );
  const pdfStarted = Date.now();
  let held;
  let capture;
  const captured = new Promise((resolve) => {
    capture = resolve;
  });
  const chunkPattern = '**/PdfPreview-*.js';
  await page.route(chunkPattern, (route) => {
    held = route;
    capture();
  });
  await upload('固定两页中文.pdf', 'application/pdf', pdf);
  await captured;
  await page.getByRole('button', { name: '关闭预览', exact: true }).click();
  await page
    .locator('.file-rows button')
    .filter({ hasText: '固定危险内容.md' })
    .click();
  await page.getByRole('button', { name: '预览最新资料', exact: true }).click();
  await page.locator('.markdown-body h1').waitFor();
  await held.continue();
  await page.unroute(chunkPattern);
  await page.waitForLoadState('networkidle');
  assert.equal(
    await page.locator('.markdown-body h1').textContent(),
    '中文安全预览',
  );
  assert.equal(await page.locator('.pdf-preview').count(), 0);
  await page
    .locator('.file-rows button')
    .filter({ hasText: '固定两页中文.pdf' })
    .click();
  await page.getByRole('button', { name: '预览最新资料', exact: true }).click();
  await page.locator('.pdf-page:not([style*="display: none"])').waitFor();
  await page
    .locator('.textLayer')
    .getByText('第一页：中文资料预览', { exact: true })
    .waitFor();
  assert.equal(await page.locator('.pdf-preview canvas').count(), 1);
  assert.equal(
    await page
      .locator('.pdf-preview a,.pdf-preview iframe,.pdf-preview object')
      .count(),
    0,
  );
  assert.ok(
    await page.locator('.pdf-preview canvas').evaluate((canvas) => {
      const data = canvas
        .getContext('2d')
        .getImageData(0, 0, canvas.width, canvas.height).data;
      let dark = 0;
      for (let i = 0; i < data.length; i += 4)
        if (
          data[i] < 128 &&
          data[i + 1] < 128 &&
          data[i + 2] < 128 &&
          data[i + 3]
        )
          dark++;
      return dark > 100;
    }),
  );
  await page.screenshot({
    path: 'artifacts/live-pdf-first.png',
    fullPage: true,
  });
  measurements.push({
    name: '固定两页中文.pdf',
    bytes: pdf.length,
    elapsedMilliseconds: Date.now() - pdfStarted,
    canvasPixels: await page
      .locator('.pdf-preview canvas')
      .evaluate((canvas) => canvas.width * canvas.height),
  });
  await page.getByLabel('页码', { exact: true }).fill('3');
  await page.getByRole('button', { name: '跳转页码', exact: true }).click();
  await page
    .locator('.pdf-preview [role=alert]')
    .filter({ hasText: '请输入1至2' })
    .waitFor();
  await page
    .locator('.pdf-preview')
    .getByRole('button', { name: '下一页', exact: true })
    .click();
  await page
    .locator('.textLayer')
    .getByText('第二页：保留原文与页码', { exact: true })
    .waitFor();
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    await page
      .getByRole('status')
      .filter({ hasText: '正在渲染页面' })
      .waitFor({ state: 'hidden' });
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: `artifacts/live-pdf-second-${width}.png`,
      fullPage: true,
    });
  }
  await page.getByLabel('页码', { exact: true }).fill('1');
  await page.getByRole('button', { name: '跳转页码', exact: true }).click();
  await page
    .locator('.textLayer')
    .getByText('第一页：中文资料预览', { exact: true })
    .waitFor();
  await download(pdf);
  for (const sample of [
    { file: 'oversized-page.pdf', name: '超过画布预算.pdf' },
    { file: 'oversized-image.pdf', name: '超过图像预算.pdf' },
  ]) {
    const bytes = await readFile(
      `../server/src/test/resources/documents/${sample.file}`,
    );
    const started = Date.now();
    await upload(sample.name, 'application/pdf', bytes);
    await page
      .locator('.pdf-preview [role=alert]')
      .filter({ hasText: '资源限制' })
      .waitFor();
    assert.equal(
      await page
        .locator('.pdf-preview canvas')
        .evaluate((canvas) => canvas.width),
      0,
    );
    measurements.push({
      name: sample.name,
      bytes: bytes.length,
      elapsedMilliseconds: Date.now() - started,
      limited: true,
    });
    await download(bytes);
  }
  await page.locator('.file-preview').press('Escape');
  assert.equal(await page.locator('.pdf-preview').count(), 0);
  assert.equal(
    await page
      .getByRole('button', { name: '预览最新资料', exact: true })
      .evaluate((node) => node === document.activeElement),
    true,
  );
  assert.deepEqual(outside, []);
  assert.ok(requests.some((path) => path.includes('pdf.worker')));
  assert.ok(resourceResponses.length > 0);
  assert.ok(resourceResponses.every((resource) => resource.status === 200));
  assert.deepEqual(resourceFailures, []);
  page.off('request', listener);
  page.off('response', responseListener);
  page.off('requestfailed', failureListener);
  const recovery = await page.context().newPage();
  try {
    const pattern = '**/TextPreview-*.js';
    await recovery.route(pattern, (route) => route.abort('failed'));
    await recovery.goto(page.url());
    await recovery
      .locator('.file-rows button')
      .filter({ hasText: '固定危险内容.md' })
      .click();
    await recovery
      .getByRole('button', { name: '预览最新资料', exact: true })
      .click();
    await recovery
      .locator('.file-preview [role=alert]')
      .filter({ hasText: '预览组件加载失败' })
      .waitFor();
    await recovery.unroute(pattern);
    await Promise.all([
      recovery.waitForEvent('load'),
      recovery.getByRole('button', { name: '刷新页面', exact: true }).click(),
    ]);
    await recovery
      .locator('.file-rows button')
      .filter({ hasText: '固定危险内容.md' })
      .click();
    await recovery
      .getByRole('button', { name: '预览最新资料', exact: true })
      .click();
    await recovery.locator('.markdown-body h1').waitFor();
  } finally {
    await recovery.close();
  }
  tests.push({
    name: '真实Markdown与两页中文PDF安全预览定位和原字节下载',
    passed: true,
    operationIds: [
      'uploadFiles',
      'getFile',
      'previewLatestFile',
      'downloadLatestFile',
    ],
    constraintIds: ['security.7'],
    assertions: [
      'Markdown危险内容无活动节点或外联，源行定位和原字节下载',
      'PDF本地工作线程、文字层和画布，翻页定位与三宽度截图，文档动作不执行',
      '关闭清理与焦点恢复，不假设范围请求',
      '迟到PDF组件加载不能覆盖新Markdown；组件资源失败有中文提示和实际刷新恢复',
    ],
    localResources: requests,
    resourceResponses,
    measurements,
  });
}
