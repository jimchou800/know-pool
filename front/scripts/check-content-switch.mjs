import assert from 'node:assert/strict';

export async function checkContentSwitch(
  page,
  tests,
  {
    http,
    base,
    headers,
    datasetId,
    knowledgeBaseId,
    gateEmbedding,
    releaseEmbedding,
  },
) {
  const name = '修订切换固定资料.txt';
  const upload = async (body) => {
    const response = await http.post(
      `${base}/api/v1/datasets/${datasetId}/files:upload`,
      {
        headers,
        multipart: {
          files: { name, mimeType: 'text/plain', buffer: Buffer.from(body) },
        },
      },
    );
    assert.equal(response.status(), 200);
    return (await response.json()).items[0];
  };
  const first = await upload('第一版固定正文。');
  const fileId = first.file.id,
    oldVersion = first.file.latestVersion.id;
  const state = async () =>
    (
      await (
        await http.get(
          `${base}/api/v1/knowledge-bases/${knowledgeBaseId}/files?limit=100`,
        )
      ).json()
    ).items.find((item) => item.fileId === fileId);
  for (
    let attempt = 0;
    attempt < 120 && (await state())?.effectiveContentVersionId !== oldVersion;
    attempt++
  )
    await page.waitForTimeout(250);
  assert.equal((await state()).effectiveContentVersionId, oldVersion);
  gateEmbedding();
  const second = await upload('第二版固定正文，待取消。');
  const newVersion = second.file.latestVersion.id;
  assert.equal((await state()).effectiveContentVersionId, oldVersion);
  assert.equal((await state()).targetContentVersionId, newVersion);
  await page.reload();
  const tasks = page.getByRole('region', { name: '处理任务', exact: true });
  const taskRow = tasks
    .locator('.knowledge-files > li')
    .filter({ hasText: second.indexTaskIds[0] });
  await taskRow
    .getByRole('button', { name: '取消原任务', exact: true })
    .waitFor();
  await taskRow
    .getByRole('button', { name: '取消原任务', exact: true })
    .click();
  const cancelResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname ===
      `/api/v1/tasks/${second.indexTaskIds[0]}/cancel`,
  );
  await taskRow
    .getByRole('button', { name: '确认取消原任务', exact: true })
    .click();
  assert.equal((await (await cancelResponse).json()).state, 'cancelled');
  releaseEmbedding();
  assert.equal((await state()).effectiveContentVersionId, oldVersion);
  const third = await upload('第三版固定正文，最终生效。');
  const finalVersion = third.file.latestVersion.id;
  for (
    let attempt = 0;
    attempt < 120 &&
    (await state())?.effectiveContentVersionId !== finalVersion;
    attempt++
  )
    await page.waitForTimeout(250);
  assert.equal((await state()).effectiveContentVersionId, finalVersion);
  await page.reload();
  const switches = page.getByRole('region', {
    name: '索引切换记录',
    exact: true,
  });
  const item = switches.locator('li').filter({ hasText: finalVersion });
  await item.waitFor();
  assert.ok((await item.innerText()).includes(oldVersion));
  assert.ok((await item.innerText()).includes('配置版本：'));
  const downloadPromise = page.waitForEvent('download');
  await item
    .getByRole('button', { name: '下载此内容版本', exact: true })
    .click();
  const download = await downloadPromise;
  assert.equal(download.suggestedFilename(), name);
  const response = await http.get(
    `${base}/api/v1/files/${fileId}/versions/${finalVersion}/download`,
  );
  assert.equal(response.status(), 200);
  assert.equal(
    (await response.body()).toString(),
    '第三版固定正文，最终生效。',
  );
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    await item.scrollIntoViewIfNeeded();
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: `artifacts/live-content-switch-${width}.png`,
    });
  }
  tests.push({
    name: '更新旧索引服务、取消原任务与实际版本切换',
    passed: true,
    operationIds: [
      'uploadFiles',
      'listKnowledgeBaseFiles',
      'cancelTask',
      'getTask',
      'listIndexSwitches',
      'downloadVersionFile',
    ],
    constraintIds: [
      '#/paths/~1tasks~1{taskId}~1cancel/post/responses/200',
      '#/paths/~1knowledge-bases~1{knowledgeBaseId}~1switches/get/responses/200',
    ],
    assertions: [
      '固定首版索引继续服务，受阻修订任务被用户取消，迟到处理不切换；第三版成功切换记录含前后版本，指定版本下载返回相同正文与安全文件名，三宽截图。',
    ],
  });
}
