import assert from 'node:assert/strict';

export async function checkMultiSearch(
  page,
  tests,
  {
    http,
    base,
    headers,
    datasetId,
    knowledgeBaseId,
    noMatches,
    gateEmbedding,
    releaseEmbedding,
  },
) {
  const root = `${base}/api/v1`;
  const get = async (path) => {
    const response = await http.get(root + path);
    assert.equal(response.status(), 200);
    return response.json();
  };
  const post = async (path, data) =>
    http.post(root + path, {
      headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
      ...(data === undefined ? {} : { data }),
    });
  const wait = async (requestId) => {
    for (let attempt = 0; attempt < 150; attempt++) {
      const request = await get(`/search-requests/${requestId}`);
      if (!['queued', 'running'].includes(request.task.state)) return request;
      await page.waitForTimeout(200);
    }
    throw new Error('多库检索原请求未在限定时间结束。');
  };
  assert.equal(
    (await post(`/knowledge-bases/${knowledgeBaseId}/publish`)).status(),
    200,
  );
  const config = await get(
    `/knowledge-bases/${knowledgeBaseId}/processing-config`,
  );
  const created = await post('/knowledge-bases', {
    name: '多库检索固定资料库',
    processing: config.input,
    datasetIds: [datasetId],
  });
  assert.equal(created.status(), 202);
  const creation = (await created.json()).task;
  let task;
  for (let attempt = 0; attempt < 150; attempt++) {
    task = await get(`/tasks/${creation.id}`);
    if (!['queued', 'running'].includes(task.state)) break;
    await page.waitForTimeout(200);
  }
  assert.equal(task.state, 'succeeded');
  const second = task.resultResourceId;
  for (
    let attempt = 0;
    attempt < 150 &&
    (await get(`/knowledge-bases/${second}`)).searchableFileCount === 0;
    attempt++
  )
    await page.waitForTimeout(200);
  assert.equal(
    (await post(`/knowledge-bases/${second}/publish`)).status(),
    200,
  );
  await page.goto(`${base}/#/search`);
  const window = page.getByRole('region', { name: '检索窗口', exact: true });
  await page.getByRole('button', { name: '新建检索', exact: true }).click();
  await window
    .getByLabel('检索内容', { exact: true })
    .fill('固定资料中的版本规则');
  for (const id of [knowledgeBaseId, second])
    await window.locator(`input[type=checkbox][value="${id}"]`).check();
  const consent = () =>
    window.getByRole('checkbox', {
      name: '我同意本次检索可能调用嵌入服务并产生费用',
    });
  const submit = async () => {
    await consent().check();
    const response = page.waitForResponse(
      (r) =>
        /\/api\/v1\/search-instances\/[^/]+\/requests$/.test(
          new URL(r.url()).pathname,
        ) && r.request().method() === 'POST',
    );
    await window.getByRole('button', { name: '开始检索', exact: true }).click();
    const accepted = await response;
    assert.equal(accepted.status(), 202);
    return accepted.json();
  };
  const first = await submit();
  const firstResult = await wait(first.id);
  assert.equal(firstResult.task.state, 'succeeded');
  assert.equal(firstResult.result.outcome, 'matches');
  assert.deepEqual(
    new Set(firstResult.scope.usedKnowledgeBaseIds),
    new Set([knowledgeBaseId, second]),
  );
  await window
    .getByRole('button', { name: '查看原文依据', exact: true })
    .first()
    .waitFor();
  await window
    .getByRole('button', { name: '查看原文依据', exact: true })
    .first()
    .click();
  await window.getByRole('region', { name: '原文依据', exact: true }).waitFor();
  await window
    .getByRole('button', { name: '关闭原文依据', exact: true })
    .click();
  noMatches(true);
  try {
    const empty = await wait((await submit()).id);
    assert.equal(empty.result.outcome, 'no_matches');
    await window.getByText('未找到相关内容。', { exact: false }).waitFor();
  } finally {
    noMatches(false);
  }
  assert.equal(
    (await post(`/knowledge-bases/${second}/offline`)).status(),
    200,
  );
  const partial = await wait((await submit()).id);
  assert.equal(partial.task.state, 'succeeded');
  assert.equal(partial.scope.incomplete, true);
  assert.equal(
    partial.scope.omitted.find((o) => o.knowledgeBaseId === second).reason,
    'not_published',
  );
  await window
    .getByText('检索范围不完整，以下结果仅来自实际可用知识库。', {
      exact: true,
    })
    .waitFor();
  assert.equal(
    (await post(`/knowledge-bases/${knowledgeBaseId}/offline`)).status(),
    200,
  );
  const failed = await wait((await submit()).id);
  assert.equal(failed.task.state, 'failed');
  assert.equal(failed.result, null);
  await window
    .getByText('选定范围全部不可用，检索已终止，请修复依赖或重新选择范围。', {
      exact: true,
    })
    .waitFor();
  assert.equal(
    (await post(`/knowledge-bases/${knowledgeBaseId}/publish`)).status(),
    200,
  );
  assert.equal(
    (await post(`/knowledge-bases/${second}/publish`)).status(),
    200,
  );
  gateEmbedding();
  try {
    const old = await submit();
    await window
      .getByLabel('检索内容', { exact: true })
      .fill('最新提交的版本规则');
    const latest = await submit();
    assert.notEqual(old.id, latest.id);
    assert.equal(
      (await get(`/search-requests/${old.id}`)).task.state,
      'cancelled',
    );
    releaseEmbedding();
    const final = await wait(latest.id);
    assert.equal(final.task.state, 'succeeded');
    await window.getByText(`请求标识：${latest.id}`, { exact: true }).waitFor();
    assert.equal(
      (await window.innerText()).includes(`请求标识：${old.id}`),
      false,
    );
  } finally {
    releaseEmbedding();
  }
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({ path: `artifacts/live-multi-search-${width}.png` });
  }
  tests.push({
    name: '真实最小实例多库检索范围费用原文版本及新请求隔离',
    passed: true,
    operationIds: [
      'createSearchInstance',
      'submitSearch',
      'getSearchRequest',
      'getSearchEvidence',
    ],
    assertions: [
      '创建最小实例选择已发布库并确认嵌入费用',
      '多库命中显示实际原文版本位置，无统一可信度及回答生成',
      '无结果与全部不可用分离，部分下线明确遗漏且不扩围',
      '同实例新提交取消旧任务，迟到结果不覆盖新请求',
      '三宽正式检索页面截图',
    ],
  });
  await page.goto(`${base}/#/knowledge-bases/${knowledgeBaseId}`);
}
