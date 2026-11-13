import assert from 'node:assert/strict';

export async function checkPublication(
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
    rejectEmbedding,
  },
) {
  const root = `${base}/api/v1`;
  const get = async (path) => {
    const response = await http.get(root + path);
    assert.equal(response.status(), 200);
    return response.json();
  };
  const send = async (path, data) =>
    http.post(root + path, {
      headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
      ...(data === undefined ? {} : { data }),
    });
  const waitTask = async (id) => {
    for (let attempt = 0; attempt < 150; attempt++) {
      const task = await get(`/tasks/${id}`);
      if (!['queued', 'running'].includes(task.state)) return task;
      await page.waitForTimeout(200);
    }
    throw new Error('发布流程的原任务未在限定时间内完成。');
  };
  const open = async (id) => {
    await page.goto(`${base}/#/knowledge-bases/${id}`);
    await page
      .getByRole('region', { name: '发布与可用性管理', exact: true })
      .waitFor();
  };
  const publication = () =>
    page.getByRole('region', { name: '发布与可用性管理', exact: true });
  const act = async (name, suffix) => {
    await publication().getByRole('button', { name, exact: true }).click();
    const response = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname.endsWith(suffix) &&
        r.request().method() === 'POST',
    );
    await publication()
      .getByRole('button', { name: `确认${name}`, exact: true })
      .click();
    return response;
  };
  const config = await get(
    `/knowledge-bases/${knowledgeBaseId}/processing-config`,
  );
  const accepted = await send('/knowledge-bases', {
    name: '发布门槛固定资料库',
    processing: config.input,
  });
  assert.equal(accepted.status(), 202);
  const created = await waitTask((await accepted.json()).task.id);
  assert.equal(created.state, 'succeeded');
  const id = created.resultResourceId;
  await open(id);
  assert.equal((await act('发布知识库', '/publish')).status(), 422);
  assert.equal(
    (await get(`/knowledge-bases/${id}`)).publicationState,
    'unpublished',
  );
  const uploaded = await http.post(
    `${root}/datasets/${datasetId}/files:upload`,
    {
      headers,
      multipart: {
        files: {
          name: '发布门槛固定资料.txt',
          mimeType: 'text/plain',
          buffer: Buffer.from('发布门槛固定正文。'),
        },
      },
    },
  );
  assert.equal(uploaded.status(), 200);
  const file = (await uploaded.json()).items[0].file;
  gateEmbedding();
  try {
    const source = await send(`/knowledge-bases/${id}/sources`, {
      type: 'file',
      resourceId: file.id,
    });
    assert.equal(source.status(), 201);
    await open(id);
    assert.equal((await act('发布知识库', '/publish')).status(), 422);
  } finally {
    releaseEmbedding();
  }
  for (
    let attempt = 0;
    attempt < 150 &&
    (await get(`/knowledge-bases/${id}`)).searchableFileCount !== 1;
    attempt++
  )
    await page.waitForTimeout(200);
  assert.equal((await get(`/knowledge-bases/${id}`)).searchableFileCount, 1);
  await open(id);
  assert.equal((await act('发布知识库', '/publish')).status(), 200);
  assert.equal(
    (await get(`/knowledge-bases/${id}`)).publicationState,
    'published',
  );
  assert.equal((await act('主动下线知识库', '/offline')).status(), 200);
  let value = await get(`/knowledge-bases/${id}`);
  assert.equal(value.publicationState, 'offline');
  assert.equal(value.offlineReason, 'manual');
  assert.equal(value.searchableFileCount, 1);
  const rebuild = await http.put(
    `${root}/knowledge-bases/${id}/processing-config`,
    {
      headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
      data: {
        ...config.input,
        chunking: { ...config.input.chunking, overlapCodePoints: 173 },
      },
    },
  );
  assert.equal(rebuild.status(), 202);
  assert.equal(
    (await waitTask((await rebuild.json()).task.id)).state,
    'succeeded',
  );
  assert.equal(
    (await get(`/knowledge-bases/${id}`)).publicationState,
    'offline',
  );
  await open(id);
  assert.equal((await act('重新发布知识库', '/publish')).status(), 200);
  const excluded = await http.put(
    `${root}/knowledge-bases/${id}/exclusions/${file.id}`,
    { headers },
  );
  assert.equal(excluded.status(), 200);
  value = await get(`/knowledge-bases/${id}`);
  assert.equal(value.publicationState, 'offline');
  assert.equal(value.offlineReason, 'no_searchable_files');
  await open(id);
  assert.ok((await publication().innerText()).includes('无可检索资料'));
  assert.equal((await act('重新发布知识库', '/publish')).status(), 422);
  assert.equal(
    (
      await http.delete(`${root}/knowledge-bases/${id}/exclusions/${file.id}`, {
        headers,
      })
    ).status(),
    204,
  );
  for (
    let attempt = 0;
    attempt < 150 &&
    (await get(`/knowledge-bases/${id}`)).searchableFileCount !== 1;
    attempt++
  )
    await page.waitForTimeout(200);
  assert.equal(
    (await get(`/knowledge-bases/${id}`)).publicationState,
    'offline',
  );
  await open(id);
  assert.equal((await act('重新发布知识库', '/publish')).status(), 200);
  const check = async () => {
    await publication()
      .getByRole('button', { name: '复查可用性', exact: true })
      .click();
    await publication().getByRole('checkbox').check();
    const response = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === `/api/v1/knowledge-bases/${id}/check`,
    );
    await publication()
      .getByRole('button', { name: '确认复查可用性', exact: true })
      .click();
    const accepted = await response;
    assert.equal(accepted.status(), 202);
    const task = await waitTask((await accepted.json()).task.id);
    assert.equal(task.state, 'succeeded');
    await publication().getByText('复查已完成。', { exact: false }).waitFor();
    return get(`/tasks/${task.id}/connection-test`);
  };
  rejectEmbedding(true);
  try {
    assert.equal((await check()).available, false);
    value = await get(`/knowledge-bases/${id}`);
    assert.equal(value.publicationState, 'published');
    assert.equal(value.availability, 'unavailable');
  } finally {
    rejectEmbedding(false);
  }
  assert.equal((await check()).available, true);
  const taskList = page.getByRole('region', { name: '处理任务', exact: true });
  await taskList
    .getByRole('button', { name: '刷新处理任务', exact: true })
    .click();
  const checkRow = taskList
    .locator('.knowledge-files > li')
    .filter({ hasText: '类型：可用性复查' })
    .first();
  await checkRow.waitFor();
  assert.equal(
    await checkRow.getByRole('button', { name: /预检|取消原任务/ }).count(),
    0,
  );
  assert.equal(
    (await get(`/knowledge-bases/${id}`)).publicationState,
    'published',
  );
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    await publication().scrollIntoViewIfNeeded();
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({ path: `artifacts/live-publication-${width}.png` });
  }
  tests.push({
    name: '真实发布门槛主动下线自动下线恢复与显式可用性复查',
    passed: true,
    operationIds: [
      'publishKnowledgeBase',
      'offlineKnowledgeBase',
      'checkKnowledgeBase',
    ],
    assertions: [
      '空库与未索引及全部排除拒绝发布，保留原状态',
      '主动下线保留索引，重建与恢复资料不自动发布',
      '最后资料排除自动下线并说明原因，显式重新发布',
      '费用确认后检查原任务，依赖故障仅改变可用性，再次显式检查恢复',
      '三宽发布操作和状态截图',
    ],
  });
  await open(knowledgeBaseId);
}
