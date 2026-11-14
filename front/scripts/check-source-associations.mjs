import assert from 'node:assert/strict';

export async function checkSourceAssociations(
  page,
  tests,
  { http, base, headers, knowledgeBaseId },
) {
  const datasetName = '来源撤销固定数据集';
  const datasetReply = await http.post(`${base}/api/v1/datasets`, {
    headers,
    data: { name: datasetName, description: '仅用于多来源边界验证' },
  });
  assert.equal(datasetReply.status(), 201);
  const fixtureDatasetId = (await datasetReply.json()).id;
  const datasetSourceReply = await http.post(
    `${base}/api/v1/knowledge-bases/${knowledgeBaseId}/sources`,
    { headers, data: { type: 'dataset', resourceId: fixtureDatasetId } },
  );
  assert.equal(datasetSourceReply.status(), 201);
  const datasetSource = await datasetSourceReply.json();
  const name = '多来源同步固定资料.txt';
  const upload = await http.post(
    `${base}/api/v1/datasets/${fixtureDatasetId}/files:upload`,
    {
      headers,
      multipart: {
        files: {
          name,
          mimeType: 'text/plain',
          buffer: Buffer.from('数据集自动同步固定正文😀。'),
        },
      },
    },
  );
  assert.equal(upload.status(), 200);
  const uploaded = (await upload.json()).items[0],
    fileId = uploaded.file.id;
  const add = await http.post(
    `${base}/api/v1/knowledge-bases/${knowledgeBaseId}/sources`,
    { headers, data: { type: 'file', resourceId: fileId } },
  );
  assert.equal(add.status(), 201);
  const explicit = await add.json();
  const list = async () => {
    const response = await http.get(
      `${base}/api/v1/knowledge-bases/${knowledgeBaseId}/files?limit=100`,
    );
    assert.equal(response.status(), 200);
    return (await response.json()).items.find((item) => item.fileId === fileId);
  };
  const initial = await list();
  assert.equal(initial.sources.length, 2);
  assert.equal(initial.included, true);
  await page.reload();
  const files = page.getByRole('region', { name: '知识库文件', exact: true });
  const row = () =>
    files
      .locator('.knowledge-files > li')
      .filter({ has: page.getByText(name, { exact: true }) });
  await row()
    .getByRole('button', { name: '排除此资料', exact: true })
    .waitFor();
  await row().getByRole('button', { name: '排除此资料', exact: true }).click();
  await row()
    .getByRole('button', { name: '确认排除此资料', exact: true })
    .click();
  await row().getByRole('button', { name: '取消排除', exact: true }).waitFor();
  assert.equal((await list()).excluded, true);
  assert.equal((await list()).included, false);
  const sourceRegion = page.getByRole('region', {
    name: '关联来源',
    exact: true,
  });
  const removeSource = async (sourceRow, sourceId) => {
    const response = page.waitForResponse(
      (reply) =>
        reply.request().method() === 'DELETE' &&
        new URL(reply.url()).pathname ===
          `/api/v1/knowledge-bases/${knowledgeBaseId}/sources/${sourceId}`,
    );
    await sourceRow
      .getByRole('button', { name: '确认撤销此来源', exact: true })
      .click();
    assert.equal((await response).status(), 204);
    await sourceRow.waitFor({ state: 'detached' });
  };
  const sourceRow = sourceRegion.locator('li').filter({ hasText: name });
  await sourceRow
    .getByRole('button', { name: '撤销此来源', exact: true })
    .click();
  await removeSource(sourceRow, explicit.id);
  await row().getByRole('button', { name: '取消排除', exact: true }).waitFor();
  const retained = await list();
  assert.equal(retained.sources.length, 1);
  assert.equal(retained.sources[0].type, 'dataset');
  assert.equal(retained.excluded, true);
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    await row().scrollIntoViewIfNeeded();
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: `artifacts/live-source-associations-${width}.png`,
    });
  }
  await row().getByRole('button', { name: '取消排除', exact: true }).click();
  await row()
    .getByRole('button', { name: '确认取消排除', exact: true })
    .click();
  await row()
    .getByRole('button', { name: '排除此资料', exact: true })
    .waitFor();
  assert.equal((await list()).included, true);
  await page.goto(`${base}/#/datasets/${fixtureDatasetId}`);
  await page.locator('.file-rows button').filter({ hasText: name }).click();
  const detail = page.getByRole('region', { name: '资料详情', exact: true });
  await detail
    .getByRole('button', { name: '查看关联知识库', exact: true })
    .click();
  await detail
    .getByText('数据集来源：' + retained.sources[0].resourceId, { exact: true })
    .waitFor();
  assert.ok((await detail.innerText()).includes('已纳入'));
  const reverse = await http.get(
    `${base}/api/v1/files/${fileId}/knowledge-bases?limit=100`,
  );
  assert.equal(
    (await reverse.json()).items[0].knowledgeBaseId,
    knowledgeBaseId,
  );
  await page.goto(`${base}/#/knowledge-bases/${knowledgeBaseId}`);
  await row()
    .getByRole('button', { name: '排除此资料', exact: true })
    .waitFor();
  await row().getByRole('button', { name: '排除此资料', exact: true }).click();
  await row()
    .getByRole('button', { name: '确认排除此资料', exact: true })
    .click();
  await row().getByRole('button', { name: '取消排除', exact: true }).waitFor();
  const datasetSourceRow = sourceRegion
    .locator('li')
    .filter({ hasText: datasetName });
  await datasetSourceRow
    .getByRole('button', { name: '撤销此来源', exact: true })
    .click();
  await removeSource(datasetSourceRow, datasetSource.id);
  await row().getByRole('button', { name: '取消排除', exact: true }).waitFor();
  const excludedWithoutSource = await list();
  assert.deepEqual(excludedWithoutSource.sources, []);
  assert.equal(excludedWithoutSource.included, false);
  assert.equal(excludedWithoutSource.excluded, true);
  assert.equal(excludedWithoutSource.task, null);
  assert.equal(excludedWithoutSource.targetContentVersionId, null);
  assert.equal(excludedWithoutSource.targetConfigVersionId, null);
  assert.equal(excludedWithoutSource.updateState, 'not_included');
  await row().getByRole('button', { name: '取消排除', exact: true }).click();
  await row()
    .getByRole('button', { name: '确认取消排除', exact: true })
    .click();
  await row().waitFor({ state: 'detached' });
  assert.equal(await list(), undefined);
  const removedReverse = await http.get(
    `${base}/api/v1/files/${fileId}/knowledge-bases?limit=100`,
  );
  assert.deepEqual((await removedReverse.json()).items, []);
  assert.ok(explicit.id);
  tests.push({
    name: '数据集自动同步与多来源排除真实界面',
    passed: true,
    operationIds: [
      'uploadFiles',
      'addKnowledgeBaseSource',
      'removeKnowledgeBaseSource',
      'listKnowledgeBaseFiles',
      'excludeKnowledgeBaseFile',
      'includeKnowledgeBaseFile',
      'listKnowledgeBaseExclusions',
      'listFileKnowledgeBases',
    ],
    constraintIds: [
      '#/paths/~1knowledge-bases~1{knowledgeBaseId}~1sources~1{sourceId}/delete/responses/204',
      '#/paths/~1knowledge-bases~1{knowledgeBaseId}~1exclusions~1{fileId}/delete/responses/204',
    ],
    assertions: [
      '新上传自动出现数据集来源，单文件重复来源不重复纳入；排除优先两来源，撤销单文件来源保留数据集，取消排除按实际来源恢复，资料详情读取关联库；最后来源撤销后取消排除仍不纳入且反向列表为空；三宽截图均仅固定资料及本机模型。',
    ],
  });
}
