import assert from 'node:assert/strict';
import { expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';

export async function checkIndexRecovery(
  page,
  tests,
  { http, base, headers, knowledgeBaseId, gateEmbedding, releaseEmbedding },
) {
  const root = `${base}/api/v1`,
    path = `${root}/knowledge-bases/${knowledgeBaseId}`;
  const files = await (await http.get(`${path}/files?limit=100`)).json();
  const before = await (await http.get(path)).json();
  const failed = files.items.find(
    (item) =>
      item.failure &&
      item.task &&
      ['failed', 'interrupted'].includes(item.task.state),
  );
  assert.ok(failed);
  const original = failed.task.id;
  await page.goto(`${base}/#/knowledge-bases/${knowledgeBaseId}`);
  await page.reload();
  const tasks = page.getByRole('region', { name: '处理任务', exact: true });
  const row = tasks
    .locator('li')
    .filter({
      has: page.getByText(`任务：${original}；文件：${failed.fileId}`, {
        exact: true,
      }),
    })
    .first();
  await row
    .getByRole('button', { name: '重试此失败资料', exact: true })
    .click();
  const panel = row.getByRole('region', {
    name: '确认重试此失败资料',
    exact: true,
  });
  await panel
    .getByRole('button', { name: '确认重试失败资料', exact: true })
    .click();
  await panel
    .getByText('请先确认重试可能再次嵌入并产生费用。', { exact: true })
    .waitFor();
  await panel
    .getByRole('checkbox', {
      name: '我确认重试可能再次嵌入并产生费用',
      exact: true,
    })
    .check();
  gateEmbedding();
  let retryTask;
  try {
    const accepted = page.waitForResponse(
      (reply) =>
        reply.request().method() === 'POST' &&
        new URL(reply.url()).pathname ===
          `/api/v1/knowledge-bases/${knowledgeBaseId}/files/${failed.fileId}/retry`,
    );
    await panel
      .getByRole('button', { name: '确认重试失败资料', exact: true })
      .click();
    const reply = await accepted;
    assert.equal(reply.status(), 202);
    assert.equal(reply.request().postData(), null);
    const result = await reply.json();
    assert.equal(result.items[0].outcome, 'enqueued');
    retryTask = result.items[0].taskId;
    const task = await (await http.get(`${root}/tasks/${retryTask}`)).json();
    assert.equal(task.retryOfTaskId, original);
    const duplicate = await http.post(`${path}/files/${failed.fileId}/retry`, {
      headers: { ...headers, 'Idempotency-Key': randomUUID() },
    });
    assert.equal(duplicate.status(), 202);
    const existing = await duplicate.json();
    assert.equal(existing.items[0].outcome, 'already_active');
    assert.equal(existing.items[0].taskId, retryTask);
  } finally {
    releaseEmbedding();
  }
  await expect
    .poll(
      async () => {
        const reply = await http.get(`${root}/tasks/${retryTask}`);
        assert.equal(reply.status(), 200);
        return (await reply.json()).state;
      },
      { timeout: 30000 },
    )
    .toBe('succeeded');
  assert.equal(
    (await (await http.get(`${root}/tasks/${retryTask}`)).json()).state,
    'succeeded',
  );
  assert.equal(
    (await (await http.get(`${root}/tasks/${original}`)).json()).state,
    'failed',
  );
  const current = await (await http.get(`${path}/files?limit=100`)).json();
  const recovered = current.items.find((item) => item.fileId === failed.fileId);
  assert.equal(recovered.task.state, 'succeeded');
  assert.equal(recovered.task.failure, null);
  if (current.items.some((item) => item.task?.state === 'failed')) {
    await expect
      .poll(
        async () => {
          const result = await (
            await http.get(`${path}/files?limit=100`)
          ).json();
          return result.items.find((item) => item.fileId === failed.fileId)
            .failure?.code;
        },
        { timeout: 30000 },
      )
      .toBe('INTEGRITY_FAILED');
    const retained = await (await http.get(path)).json();
    assert.equal(
      retained.effectiveConfigVersionId,
      before.effectiveConfigVersionId,
    );
    assert.equal(
      retained.effectiveIndexVersionId,
      before.effectiveIndexVersionId,
    );
  }
  await tasks
    .getByRole('button', { name: '刷新处理任务', exact: true })
    .click();
  await tasks
    .getByRole('button', { name: '重试全部失败项', exact: true })
    .click();
  const all = tasks.getByRole('region', {
    name: '确认重试全部失败项',
    exact: true,
  });
  await all
    .getByRole('checkbox', {
      name: '我确认重试可能再次嵌入并产生费用',
      exact: true,
    })
    .check();
  const batch = page.waitForResponse(
    (reply) =>
      new URL(reply.url()).pathname ===
      `/api/v1/knowledge-bases/${knowledgeBaseId}/retry-failed`,
  );
  await all
    .getByRole('button', { name: '确认重试失败资料', exact: true })
    .click();
  const results = await (await batch).json();
  assert.ok(
    results.items.every(
      (item) => item.fileId !== failed.fileId || item.outcome !== 'enqueued',
    ),
  );
  for (const item of results.items.filter(
    (item) => item.outcome === 'enqueued',
  )) {
    await expect
      .poll(
        async () => {
          return (await (await http.get(`${root}/tasks/${item.taskId}`)).json())
            .state;
        },
        { timeout: 30000 },
      )
      .toBe('succeeded');
  }
  await expect
    .poll(
      async () => {
        const result = await (await http.get(path)).json();
        return result.effectiveConfigVersionId;
      },
      { timeout: 30000 },
    )
    .toBe(before.targetConfigVersionId);
  const completed = await (await http.get(`${path}/files?limit=100`)).json();
  assert.ok(completed.items.every((item) => item.failure === null));
  assert.ok(
    completed.items.every(
      (item) => item.effectiveConfigVersionId === before.targetConfigVersionId,
    ),
  );
  const switched = await (await http.get(path)).json();
  assert.notEqual(
    switched.effectiveIndexVersionId,
    before.effectiveIndexVersionId,
  );
  assert.equal(switched.publicationState, before.publicationState);
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    await all.scrollIntoViewIfNeeded();
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: `artifacts/live-index-recovery-${width}.png`,
    });
  }
  tests.push({
    name: '真实失败资料显式重试幂等活动去重与原历史保留',
    passed: true,
    operationIds: ['retryKnowledgeBaseFile', 'retryFailedKnowledgeBaseFiles'],
    assertions: [
      '费用未确认不受理，单份POST无正文且新任务关联原失败',
      '活动重复重试返回已有同任务，旧失败记录保留',
      '成功项在全失败项重试中不重新入队；三宽逐项结果截图',
      '单项暂存成功不掩盖其余失败；全部恢复后整库统一切换且发布决定不变',
    ],
  });
}
