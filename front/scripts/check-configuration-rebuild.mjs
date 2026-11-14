import assert from 'node:assert/strict';
import { expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { checkIndexRecovery } from './check-index-recovery.mjs';

export async function checkConfigurationRebuild(
  page,
  tests,
  {
    http,
    base,
    headers,
    knowledgeBaseId,
    gateEmbedding,
    releaseEmbedding,
    rejectEmbedding,
  },
) {
  const root = `${base}/api/v1`,
    path = `${root}/knowledge-bases/${knowledgeBaseId}`;
  const read = async () => {
    const reply = await http.get(path);
    assert.equal(reply.status(), 200);
    return reply.json();
  };
  const waitTask = async (id) => {
    let task;
    await expect
      .poll(
        async () => {
          const reply = await http.get(`${root}/tasks/${id}`);
          assert.equal(reply.status(), 200);
          task = await reply.json();
          return ['queued', 'running'].includes(task.state);
        },
        { timeout: 30000 },
      )
      .toBe(false);
    return task;
  };
  const publish = await http.post(`${path}/publish`, { headers });
  assert.equal(publish.status(), 200);
  await page.goto(`${base}/#/knowledge-bases/${knowledgeBaseId}`);
  await page.reload();
  const before = await read();
  assert.ok(before.effectiveConfigVersionId);
  const edit = page.getByRole('button', { name: '调整切片配置', exact: true });
  await edit.click();
  const panel = page.getByRole('region', { name: '编辑切片配置', exact: true });
  await panel.getByLabel('目标重叠码点数', { exact: true }).fill('7');
  gateEmbedding();
  let accepted;
  try {
    const request = page.waitForResponse(
      (reply) =>
        reply.request().method() === 'PUT' &&
        new URL(reply.url()).pathname.endsWith('/processing-config'),
    );
    await panel
      .getByRole('button', { name: '保存配置并重建', exact: true })
      .click();
    accepted = (await (await request).json()).task;
    const building = await read();
    assert.equal(building.publicationState, 'published');
    assert.equal(
      building.effectiveConfigVersionId,
      before.effectiveConfigVersionId,
    );
    assert.notEqual(
      building.targetConfigVersionId,
      before.effectiveConfigVersionId,
    );
    for (const versionId of [
      building.targetConfigVersionId,
      building.effectiveConfigVersionId,
    ]) {
      const reply = await http.get(
        `${path}/processing-config/versions/${versionId}`,
      );
      assert.equal(reply.status(), 200);
      assert.equal((await reply.json()).id, versionId);
    }
    const offline = await http.post(`${path}/offline`, { headers });
    assert.equal(offline.status(), 200);
  } finally {
    releaseEmbedding();
  }
  assert.equal((await waitTask(accepted.id)).state, 'succeeded');
  const switched = await read();
  assert.equal(switched.publicationState, 'offline');
  assert.equal(
    switched.effectiveConfigVersionId,
    accepted.targetConfigVersionId,
  );
  assert.equal(switched.effectiveIndexVersionId, accepted.id);
  const config = (await (await http.get(`${path}/processing-config`)).json())
    .input;
  rejectEmbedding(true);
  let failed;
  try {
    const reply = await http.put(`${path}/processing-config`, {
      headers: { ...headers, 'Idempotency-Key': randomUUID() },
      data: {
        ...config,
        chunking: { ...config.chunking, overlapCodePoints: 8 },
      },
    });
    assert.equal(reply.status(), 202);
    failed = await waitTask((await reply.json()).task.id);
    assert.equal(failed.state, 'failed');
  } finally {
    rejectEmbedding(false);
  }
  const retained = await read();
  assert.equal(
    retained.effectiveConfigVersionId,
    switched.effectiveConfigVersionId,
  );
  assert.equal(
    retained.effectiveIndexVersionId,
    switched.effectiveIndexVersionId,
  );
  assert.equal(retained.publicationState, 'offline');
  const tasksBefore = await (await http.get(`${path}/tasks?limit=100`)).json();
  const credential = await http.put(
    `${root}/settings/models/embedding/versions/${config.embedding.modelConfigVersionId}/credential`,
    {
      headers,
      data: { action: 'replace', value: 'fixed-knowledge-base-fixture' },
    },
  );
  assert.equal(credential.status(), 200);
  assert.equal(
    (await read()).targetConfigVersionId,
    retained.targetConfigVersionId,
  );
  const tasksAfter = await (await http.get(`${path}/tasks?limit=100`)).json();
  assert.equal(
    tasksAfter.items.filter((task) => task.kind === 'rebuild_knowledge_base')
      .length,
    tasksBefore.items.filter((task) => task.kind === 'rebuild_knowledge_base')
      .length,
  );
  await page.reload();
  const compare = page.getByRole('region', {
    name: '目标与生效配置对照',
    exact: true,
  });
  await expect(
    compare.locator('dt').filter({
      hasText: retained.effectiveConfigVersionId,
    }),
  ).toHaveText(`旧生效配置（当前生效）：${retained.effectiveConfigVersionId}`);
  assert.ok(
    (await compare.innerText()).includes(retained.effectiveConfigVersionId),
  );
  const tasks = page.getByRole('region', { name: '处理任务', exact: true });
  await tasks
    .getByText(
      '整库父任务：全部资料构建并验证成功后统一切换；子任务成功仅为暂存，不能视作单文件已切换。失败继续旧生效索引，切换不改变当前发布决定。',
      { exact: true },
    )
    .first()
    .waitFor();
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    await compare.scrollIntoViewIfNeeded();
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: `artifacts/live-configuration-rebuild-${width}.png`,
    });
  }
  await checkIndexRecovery(page, tests, {
    http,
    base,
    headers,
    knowledgeBaseId,
    gateEmbedding,
    releaseEmbedding,
  });
  tests.push({
    name: '真实整库重建目标生效配置失败旧库服务及发布独立',
    passed: true,
    operationIds: [
      'getProcessingConfigVersion',
      'updateProcessingConfig',
      'getTask',
      'listKnowledgeBaseTasks',
    ],
    assertions: [
      '重建期间旧生效配置不变，目标与旧配置分别从正式历史入口读取',
      '主动下线后整库成功自动切换，仍保持下线且不要求再次发布',
      '受控嵌入故障使父重建失败，旧配置及索引继续保留',
      '仅替换凭据不生成重建任务；三宽配置对照及父子任务截图',
    ],
  });
}
