import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

export async function checkBatchFiles(page, tests) {
  const observed = [];
  const listener = (response) => {
    const path = new URL(response.url()).pathname;
    if (/\/files(?::upload|\/|$)/.test(path))
      observed.push({
        path,
        method: response.request().method(),
        status: response.status(),
      });
  };
  page.on('response', listener);
  const text = (name, content) => ({
    name,
    mimeType: 'text/plain',
    buffer: Buffer.from(content),
  });
  async function submit(files) {
    await page
      .getByLabel('选择资料（可多选）', { exact: true })
      .setInputFiles(files);
    return send();
  }
  async function send(
    button = page.getByRole('button', { name: '上传资料', exact: true }),
  ) {
    const pending = page.waitForResponse(
      (response) =>
        response.url().includes('/files:upload') &&
        response.request().method() === 'POST',
    );
    await button.click();
    const result = await (await pending).json();
    await page
      .getByRole('button', { name: '上传资料', exact: true })
      .waitFor({ state: 'visible' });
    await page.waitForFunction(
      () => !document.querySelector('.files-panel .primary-button')?.disabled,
    );
    return result;
  }
  const original = await submit([
    text('批量稳定身份.txt', '初始正文'),
    text('批量独立副本.txt', '初始正文'),
  ]);
  assert.deepEqual(
    original.items.map((item) => item.outcome),
    ['created', 'created'],
  );
  assert.notEqual(original.items[0].file.id, original.items[1].file.id);
  const baseline = original.items[0].file;
  const changed = await submit([
    text('批量稳定身份.txt', '覆盖正文'),
    text('批量独立副本.txt', '初始正文'),
    text('批量新增.txt', '新增正文'),
    {
      name: '批量失败.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from([0, 255]),
    },
    text('__proto__', '不支持的文件类型'),
    text('constructor', '不支持的文件类型'),
  ]);
  assert.deepEqual(
    changed.items.map((item) => item.outcome),
    ['replaced', 'unchanged', 'created', 'failed', 'failed', 'failed'],
  );
  assert.equal(changed.items[0].file.id, baseline.id);
  assert.notEqual(
    changed.items[0].file.latestVersion.id,
    baseline.latestVersion.id,
  );
  assert.equal(
    changed.items[1].file.latestVersion.id,
    original.items[1].file.latestVersion.id,
  );
  const results = page.getByRole('list', { name: '逐项上传结果' });
  for (const label of ['覆盖成功', '内容未变', '新增成功', '上传失败'])
    assert.ok((await results.textContent()).includes(label));
  const retry = await send(
    page.getByRole('button', { name: '单独重试 批量失败.txt', exact: true }),
  );
  assert.equal(retry.items.length, 1);
  assert.equal(retry.items[0].submittedName, '批量失败.txt');
  assert.equal(retry.items[0].outcome, 'failed');

  await page
    .getByLabel('选择资料（可多选）', { exact: true })
    .setInputFiles([
      text('批内É.TXT', '第一份正文'),
      text('批内E\u0301.txt', '第二份正文'),
      text('批内其他.txt', '独立正文'),
    ]);
  assert.equal(await page.locator('.upload-conflict input:checked').count(), 0);
  assert.equal(await page.locator('.upload-conflict input').count(), 2);
  const independent = await send();
  assert.equal(independent.items.length, 1);
  assert.equal(independent.items[0].submittedName, '批内其他.txt');
  await page.locator('.upload-conflict input').nth(1).check();
  assert.equal(await page.locator('.upload-conflict input:checked').count(), 1);
  const selected = await send();
  assert.equal(selected.items.length, 1);
  assert.equal(selected.items[0].submittedName, '批内E\u0301.txt');
  await page.getByRole('button', { name: '预览最新资料', exact: true }).click();
  await page
    .locator('.source-text')
    .getByText('第二份正文', { exact: true })
    .waitFor();

  const current = changed.items[0].file;
  await page.getByRole('button', { name: /批量稳定身份.txt/ }).click();
  await page.getByRole('button', { name: '重命名资料', exact: true }).click();
  await page.getByLabel('完整文件名（不去除首尾空白）').fill('批量新增.txt');
  const rejected = page.waitForResponse(
    (response) =>
      response.url().endsWith('/name') &&
      response.request().method() === 'PATCH',
  );
  await page.getByRole('button', { name: '保存名称', exact: true }).click();
  assert.equal((await rejected).status(), 409);
  await page
    .getByRole('form', { name: '重命名资料' })
    .getByRole('alert')
    .waitFor();
  assert.equal(
    await page.locator('.file-detail > h4').textContent(),
    '批量稳定身份.txt',
  );
  await page.getByLabel('完整文件名（不去除首尾空白）').fill(' 跨扩展名.pdf ');
  const renamed = page.waitForResponse(
    (response) =>
      response.url().endsWith('/name') &&
      response.request().method() === 'PATCH',
  );
  await page.getByRole('button', { name: '保存名称', exact: true }).click();
  const after = await (await renamed).json();
  assert.equal(after.name, ' 跨扩展名.pdf ');
  assert.deepEqual(after.latestVersion, current.latestVersion);
  assert.equal(after.createdBy, baseline.createdBy);
  assert.equal(after.createdAt, baseline.createdAt);
  assert.equal(after.lastUploadedAt, current.lastUploadedAt);
  await page.getByRole('button', { name: '预览最新资料', exact: true }).click();
  await page
    .locator('.source-text')
    .getByText('覆盖正文', { exact: true })
    .waitFor();
  const downloadPending = page.waitForEvent('download');
  await page.getByRole('button', { name: '下载原文件', exact: true }).click();
  assert.deepEqual(
    await readFile(await (await downloadPending).path()),
    Buffer.from('覆盖正文'),
  );

  for (let index = 0; index < 21; index++) {
    const result = await submit(text('批量分页.txt', `分页正文${index}`));
    assert.equal(result.items[0].outcome, index === 0 ? 'created' : 'replaced');
    assert.deepEqual(result.items[0].indexTaskIds, []);
  }
  await page.getByRole('button', { name: '查看内容版本', exact: true }).click();
  const history = page.getByRole('region', { name: '内容版本', exact: true });
  await history.locator('li').nth(19).waitFor();
  assert.equal(await history.locator('li').count(), 20);
  const versionRequest = page.waitForResponse(
    (response) =>
      response.url().includes('/versions?') &&
      response.url().includes('cursor='),
  );
  await page.getByRole('button', { name: '下一页版本', exact: true }).click();
  assert.equal((await versionRequest).status(), 200);
  await history.getByText('第 2 页', { exact: true }).waitFor();
  assert.equal(await history.locator('li').count(), 1);
  await page.getByRole('button', { name: '上一页版本', exact: true }).click();
  await history.locator('li').nth(19).waitFor();
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
      path: `artifacts/live-batch-files-${width}.png`,
      fullPage: true,
    });
  }
  page.off('response', listener);
  tests.push({
    name: '真实批量逐项结果冲突选择失败单独重试重命名与多版本分页',
    operationIds: [
      'uploadFiles',
      'getFile',
      'renameFile',
      'listFileVersions',
      'previewLatestFile',
      'downloadLatestFile',
    ],
    constraintIds: [],
    passed: true,
    requests: observed,
    assertions: [
      '不同名同内容独立保存，同名变化保持身份；四结果逐项显示，失败单独重试只返回对应一项',
      'NFC大小写重名不默认选择，其他组先上传，用户选定第二份后仅保存其正文',
      '名称冲突409保留原资料，跨扩展名改名不改变内容媒体和上传快照；预览及原字节下载仍正确',
      '真实后端21内容版本经20条游标分页，三桌面宽度无溢出并保留截图，无关联不伪造索引任务',
    ],
  });
}
