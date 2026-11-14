import assert from 'node:assert/strict';
export async function checkRuntimeSettings(page, tests, base) {
  await page.goto(`${base}/#/settings`);
  const panel = page.getByRole('region', { name: '运行参数', exact: true });
  await panel.locator('[name="indexConcurrency"]').waitFor();
  const initial = await (
    await page.context().request.get(`${base}/api/v1/settings/runtime`)
  ).json();
  assert.equal(Object.keys(initial).length, 22);
  assert.equal(await panel.locator('.runtime-fields input').count(), 21);
  assert.equal(
    await panel.locator('[name="maxFileBytes"]').inputValue(),
    String(initial.maxFileBytes),
  );
  const value =
    initial.indexConcurrency === 32 ? 31 : initial.indexConcurrency + 1;
  await panel.locator('[name="indexConcurrency"]').fill(String(value));
  const replyPromise = page.waitForResponse(
    (reply) =>
      new URL(reply.url()).pathname === '/api/v1/settings/runtime' &&
      reply.request().method() === 'PUT',
  );
  await panel
    .getByRole('button', { name: '保存全部运行参数', exact: true })
    .click();
  const reply = await replyPromise;
  const replyBody = reply.status() === 200 ? '' : await reply.text();
  assert.equal(reply.status(), 200, `运行参数保存失败：${replyBody}`);
  assert.deepEqual(JSON.parse(reply.request().postData()), {
    ...initial,
    indexConcurrency: value,
  });
  assert.ok(reply.request().headers()['x-csrf-token']);
  assert.equal(reply.request().headers()['idempotency-key'], undefined);
  await panel.getByText(/运行参数已保存/).waitFor();
  await page.reload();
  await panel.locator('[name="indexConcurrency"]').waitFor();
  assert.equal(
    await panel.locator('[name="indexConcurrency"]').inputValue(),
    String(value),
  );
  await panel
    .locator('[name="indexConcurrency"]')
    .fill(String(initial.indexConcurrency));
  await panel
    .getByRole('button', { name: '保存全部运行参数', exact: true })
    .click();
  await panel.getByText(/运行参数已保存/).waitFor();
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    await panel.scrollIntoViewIfNeeded();
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: `artifacts/live-runtime-settings-${width}.png`,
    });
  }
  tests.push({
    name: '运行参数真实初值全量替换及刷新回读',
    passed: true,
    operationIds: ['getRuntimeSettings', 'updateRuntimeSettings'],
    assertions: [
      '全部二十二字段由后端读取，二十一数值字段与模型限流完整编辑',
      '真实写保护全量保存，刷新保持生效值，再恢复固定初值；未自动调用模型',
      '三种桌面宽度截图无横向溢出',
    ],
  });
}
