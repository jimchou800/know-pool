import assert from 'node:assert/strict';

export async function checkChunkPreflight(page, tests) {
  const tasks = page.getByRole('region', { name: '处理任务', exact: true });
  const refreshed = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      /^\/api\/v1\/knowledge-bases\/[^/]+\/tasks$/.test(
        new URL(response.url()).pathname,
      ),
  );
  await tasks
    .getByRole('button', { name: '刷新处理任务', exact: true })
    .click();
  assert.equal((await refreshed).status(), 200);
  await page.waitForFunction(() =>
    [...document.querySelectorAll('button')].some(
      (button) =>
        button.textContent.trim() === '刷新处理任务' && !button.disabled,
    ),
  );
  const first = tasks
    .getByRole('button', { name: '查看切片预检', exact: true })
    .first();
  await first.waitFor();
  const originalTaskId = await first.evaluate(
    (node) => node.closest('li').textContent.match(/任务：([^；\s]+)/)[1],
  );
  const open = tasks
    .locator('.knowledge-files > li')
    .filter({ hasText: `任务：${originalTaskId}；` })
    .getByRole('button', { name: '查看切片预检', exact: true });
  await open.click();
  const report = tasks.getByRole('region', {
    name: '切片完整性预检',
    exact: true,
  });
  await report.getByText('内容完整性检查通过', { exact: true }).waitFor();
  assert.ok((await report.innerText()).includes('码点'));
  assert.ok((await report.innerText()).includes('计量器：'));
  assert.ok(await report.locator('.source-text').count());
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    await report.scrollIntoViewIfNeeded();
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: `artifacts/live-chunk-preflight-${width}.png`,
    });
  }
  await report
    .getByRole('button', { name: '关闭切片预检', exact: true })
    .click();
  assert.equal(
    await open.evaluate((node) => document.activeElement === node),
    true,
    JSON.stringify(
      await open.evaluate((node) => ({
        triggerTask: node
          .closest('li')
          ?.textContent.match(/任务：([^；\s]+)/)?.[1],
        focusedTag: document.activeElement?.tagName,
        focusedLabel: document.activeElement?.getAttribute('aria-label'),
        focusedTask: document.activeElement
          ?.closest('li')
          ?.textContent.match(/任务：([^；\s]+)/)?.[1],
        preflightButtons: [
          ...document.querySelectorAll('.chunk-preflight > button'),
        ].map((button) => ({
          disabled: button.disabled,
          focused: button === document.activeElement,
        })),
      })),
    ),
  );
  const edit = page.getByRole('button', { name: '调整切片配置', exact: true });
  await edit.click();
  const editor = page.getByRole('region', {
    name: '编辑切片配置',
    exact: true,
  });
  await editor
    .getByRole('combobox', { name: /^切片策略/ })
    .selectOption('fixed');
  await editor.getByLabel('目标重叠码点数', { exact: true }).fill('0');
  const acceptedPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' &&
      new URL(response.url()).pathname.endsWith('/processing-config'),
  );
  await editor
    .getByRole('button', { name: '保存配置并重建', exact: true })
    .click();
  const accepted = await acceptedPromise;
  assert.equal(accepted.status(), 202);
  assert.equal(
    JSON.parse(accepted.request().postData()).chunking.overlapCodePoints,
    0,
  );
  const acceptedTask = (await accepted.json()).task;
  const taskId = acceptedTask.id;
  const configVersionId = acceptedTask.targetConfigVersionId;
  assert.ok(taskId);
  assert.ok(configVersionId);
  const terminal = await page.waitForResponse(async (response) => {
    if (
      response.request().method() !== 'GET' ||
      new URL(response.url()).pathname !== `/api/v1/tasks/${taskId}`
    )
      return false;
    const task = await response.json();
    return !['queued', 'running'].includes(task.state);
  });
  const terminalTask = await terminal.json();
  assert.equal(
    terminalTask.state,
    'succeeded',
    JSON.stringify(terminalTask.failure),
  );
  await page
    .getByRole('button', { name: '调整切片配置', exact: true })
    .waitFor({ state: 'visible' });
  await page.waitForFunction(
    () => !document.querySelector('[aria-label="编辑切片配置"]'),
    null,
    { timeout: 30000 },
  );
  assert.ok(
    (
      await page
        .getByRole('region', { name: '知识库详情', exact: true })
        .innerText()
    ).includes('固定长度'),
  );
  await tasks
    .getByRole('button', { name: '刷新处理任务', exact: true })
    .click();
  const newPreflight = tasks
    .locator('.knowledge-files > li')
    .filter({ hasText: configVersionId })
    .filter({
      has: page.getByRole('button', { name: '查看切片预检', exact: true }),
    })
    .first()
    .getByRole('button', { name: '查看切片预检', exact: true });
  await newPreflight.waitFor();
  await newPreflight.click();
  const updatedReport = tasks.getByRole('region', {
    name: '切片完整性预检',
    exact: true,
  });
  await updatedReport
    .getByText('内容完整性检查通过', { exact: true })
    .waitFor();
  assert.ok((await updatedReport.innerText()).includes(configVersionId));
  await updatedReport
    .getByRole('button', { name: '关闭切片预检', exact: true })
    .click();
  tests.push({
    name: '真实切片预检与配置修改整库重建',
    passed: true,
    operationIds: [
      'getChunkPreflight',
      'updateProcessingConfig',
      'getProcessingConfig',
      'getTask',
      'listKnowledgeBaseTasks',
    ],
    constraintIds: [
      '#/paths/~1tasks~1{taskId}~1chunk-preflight/get/responses/200',
      '#/paths/~1knowledge-bases~1{knowledgeBaseId}~1processing-config/put/responses/202',
    ],
    assertions: [
      '真实索引任务提供完整统计与安全文本样例；配置修改使用正式异步任务，固定本机模型，三宽截图，关闭恢复焦点。',
    ],
  });
}
