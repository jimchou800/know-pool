import { expect, it } from 'vitest';
import { createManagementSearchClient } from './search-client';
import { setup, id, otherId, task } from '../../test-fixtures/knowledge-bases';
import { search, evidence } from '../../test-fixtures/indexing';

it('管理检索实际固定当前单库和幂等键，不接受伪造实例', async () => {
  const pending = {
    id,
    instanceId: null,
    task: { ...task, kind: 'search' },
    result: null,
    scope: null,
  };
  const s = await setup(() => pending);
  const api = createManagementSearchClient(s.client);
  expect(await api.submit(id, '版本规则', otherId)).toEqual(pending);
  expect(JSON.parse(s.requests.at(-1).data)).toEqual({
    query: '版本规则',
    knowledgeBaseIds: [id],
  });
  expect(s.requests.at(-1).headers.get('Idempotency-Key')).toBe(otherId);
  expect(s.requests.at(-1).headers.get('X-CSRF-Token')).toBeTruthy();
  s.reply({ ...pending, instanceId: otherId });
  await expect(api.get(id, id)).rejects.toThrow('响应格式');
});

it('真实消费成功结果和独立证据查询，定位实际版本且拒绝跨请求身份', async () => {
  const s = await setup(() => search),
    api = createManagementSearchClient(s.client);
  expect(await api.get(id, id)).toEqual(search);
  s.reply(evidence);
  expect(await api.evidence(id, otherId, id)).toEqual(evidence);
  expect(s.requests.at(-1).url).toBe(
    `/search-requests/${id}/evidence/${otherId}`,
  );
  s.reply({ ...search, result: { ...search.result, requestId: otherId } });
  await expect(api.get(id, id)).rejects.toThrow('响应格式');
});

it('成功结果不能伪造空范围、矛盾无命中或错配查询快照', async () => {
  const s = await setup(() => search),
    api = createManagementSearchClient(s.client);
  for (const value of [
    { ...search, scope: null },
    {
      ...search,
      result: {
        ...search.result,
        scope: { ...search.scope, usedKnowledgeBaseIds: [] },
      },
    },
    { ...search, result: { ...search.result, outcome: 'no_matches' } },
    { ...search, result: { ...search.result, evidence: [] } },
  ]) {
    s.reply(value);
    await expect(api.get(id, id)).rejects.toThrow('响应格式');
  }
});

it('响应对象键顺序不同不影响同一范围消费', async () => {
  const s = await setup(() => ({
    ...search,
    scope: {
      incomplete: false,
      omitted: [],
      usedKnowledgeBaseIds: [id],
      selectedKnowledgeBaseIds: [id],
    },
  }));
  expect(
    (await createManagementSearchClient(s.client).get(id, id)).result.outcome,
  ).toBe('matches');
});

it.each([
  ['id', null],
  ['instanceId', otherId],
  ['chunkId', '错误'],
  ['knowledgeBaseId', otherId],
  ['fileId', null],
  ['contentVersionId', 2],
  ['indexVersionId', '错误'],
  ['configVersionId', null],
  ['filenameAtCapture', '../原文.txt'],
  ['filenameAtCapture', '.'],
  ['filenameAtCapture', '长'.repeat(256)],
  ['currentFilename', 2],
  ['text', null],
  ['location', null],
  ['originalAvailable', 1],
  ['usingPreviousVersion', 'true'],
  ['updateState', '错误'],
  ['capturedAt', '2026-01-01T00:00:00+08:00'],
  ['extra', true],
])('依据响应非法字段%s不能作为真实原文展示', async (field, value) => {
  const s = await setup(() => ({ ...evidence, [field]: value }));
  await expect(
    createManagementSearchClient(s.client).evidence(id, otherId, id),
  ).rejects.toThrow('响应格式');
});

it.each([
  ['format', 'html'],
  ['pageStart', 0],
  ['pageEnd', Number.MAX_SAFE_INTEGER + 1],
  ['lineStart', null],
  ['lineStart', 4],
  ['lineEnd', '3'],
  ['titlePath', [2]],
  ['paragraphId', 2],
  ['structureId', false],
  ['continuationIndex', -1],
  ['tableRowStart', -1],
  ['tableRowEnd', 0.1],
  ['tableColumnStart', '0'],
  ['tableColumnEnd', false],
  ['codeLanguage', {}],
  ['codeBlockId', []],
  ['extra', '未知'],
])('依据位置非法字段%s不能伪造可定位结果', async (field, value) => {
  const s = await setup(() => ({
    ...evidence,
    location: { ...evidence.location, [field]: value },
  }));
  await expect(
    createManagementSearchClient(s.client).evidence(id, otherId, id),
  ).rejects.toThrow('响应格式');
});

it.each([
  ['requestId', otherId],
  ['instanceId', id],
  ['taskId', id],
  ['query', ' '],
  ['query', '长'.repeat(20001)],
  ['scope', null],
  ['outcome', 'failed'],
  ['evidence', null],
  ['preprocessingState', 'completed'],
  ['additionalQueries', ['附加查询']],
  ['warnings', [false]],
  ['extra', true],
])('管理结果非法字段%s不绕过消费校验', async (field, value) => {
  const s = await setup(() => ({
    ...search,
    result: { ...search.result, [field]: value },
  }));
  await expect(
    createManagementSearchClient(s.client).get(id, id),
  ).rejects.toThrow('响应格式');
});

it('非法提交在网络边界前拒绝，查询和证据标识不进入任意路径', async () => {
  const s = await setup(() => search),
    api = createManagementSearchClient(s.client);
  for (const args of [
    ['错误', '查询', id],
    [id, ' ', id],
    [id, '长'.repeat(20001), id],
    [id, '查询', '错误'],
  ])
    await expect(api.submit(...args)).rejects.toThrow('请输入');
  await expect(api.get('../原文', id)).rejects.toThrow('标识');
  await expect(api.evidence(id, '../依据', id)).rejects.toThrow('标识');
  expect(s.requests).toHaveLength(0);
});
