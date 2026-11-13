import { afterAll, expect, it } from 'vitest';
import { execFileSync } from 'node:child_process';
import { mount, flushPromises } from '@vue/test-utils';
import {
  setup,
  id,
  otherId,
  record,
  task,
  time,
} from '../../test-fixtures/knowledge-bases';
import { config } from '../../test-fixtures/preflight';
import { createKnowledgeBaseClient } from './client';
import ProcessingVersions from './ProcessingVersions.vue';
import IndexTasks from './IndexTasks.vue';
const observations = [];
function observe(r) {
  observations.push({
    operationId: 'getProcessingConfigVersion',
    method: r.method,
    url: r.url,
    params: r.params ?? {},
    headers: r.headers.toJSON(),
    body: r.data ?? null,
    status: r.observed.status,
    response: r.observed.data,
  });
}
it('历史配置按本库与实际版本严格读取，拒绝错身份未知字段，消费全部公开错误', async () => {
  const s = await setup(() => config),
    api = createKnowledgeBaseClient(s.client);
  expect(await api.configVersion(id, otherId)).toEqual(config);
  observe(s.requests.at(-1));
  for (const value of [
    { ...config, id },
    { ...config, knowledgeBaseId: otherId },
    { ...config, draft: '非法' },
  ]) {
    s.reply(value);
    await expect(api.configVersion(id, otherId)).rejects.toThrow('不符合');
  }
  for (const [status, code] of [
    [400, 'INVALID_REQUEST'],
    [401, 'SESSION_EXPIRED'],
    [403, 'ORIGIN_REJECTED'],
    [404, 'NOT_FOUND'],
    [429, 'QUEUE_FULL'],
    [500, 'INTERNAL_ERROR'],
    [503, 'DEPENDENCY_UNAVAILABLE'],
  ]) {
    s.reply({
      errorStatus: status,
      body: {
        requestId: id,
        code,
        message: '固定历史配置错误',
        fieldErrors: [],
        retryable: false,
      },
    });
    await expect(api.configVersion(id, otherId)).rejects.toThrow(
      '固定历史配置错误',
    );
    observe(s.requests.at(-1));
  }
});
it('目标与旧生效配置分别取实际快照，不把同名模型不同端点当作相同配置', async () => {
  const old = {
    ...config,
    id,
    input: {
      ...config.input,
      embedding: {
        ...config.input.embedding,
        modelSnapshot: {
          model: config.input.embedding.modelSnapshot.model,
          endpoint: 'http://127.0.0.1:9876/v1',
        },
      },
    },
  };
  const s = await setup((r) => (r.url.endsWith(otherId) ? config : old));
  const w = mount(ProcessingVersions, {
    props: {
      client: s.client,
      base: {
        ...record,
        targetConfigVersionId: otherId,
        effectiveConfigVersionId: id,
      },
    },
  });
  await flushPromises();
  expect(s.requests).toHaveLength(2);
  expect(w.text()).toContain('旧生效配置');
  expect(w.text()).toContain('9876');
  expect(w.text()).toContain('失败不移除');
  w.unmount();
});
it('整库父进度与同配置子任务分组，子成功不伪装已切换', async () => {
  const parent = {
    ...task,
    kind: 'rebuild_knowledge_base',
    state: 'running',
    phase: 'initializing',
    startedAt: time,
    progressCompleted: 1,
    progressTotal: 2,
  };
  const child = {
    ...task,
    id,
    kind: 'index_file',
    state: 'succeeded',
    phase: 'completed',
    startedAt: time,
    finishedAt: time,
    fileId: otherId,
    targetContentVersionId: otherId,
    resultResourceId: id,
  };
  const s = await setup(() => ({ items: [parent, child], nextCursor: null }));
  const w = mount(IndexTasks, {
    props: { client: s.client, knowledgeBaseId: id },
  });
  await flushPromises();
  expect(w.text()).toContain('已完成 1 / 2');
  expect(w.text()).toContain('子任务成功仅为暂存');
  expect(w.get('details').text()).toContain(child.id);
  w.unmount();
});
afterAll(() =>
  execFileSync('python', ['scripts/settings-evidence.py', '08'], {
    input: JSON.stringify(observations),
    encoding: 'utf8',
  }),
);
