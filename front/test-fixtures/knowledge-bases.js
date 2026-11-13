import { createSystemClient } from '../src/system/session';
import { createKnowledgeBaseClient } from '../src/knowledge-bases/client';
export const id = '00000000-0000-4000-8000-000000000071';
export const otherId = '00000000-0000-4000-8000-000000000072';
export const time = '2026-11-13T00:00:00Z';
export const record = {
  id,
  name: '空知识库',
  description: '',
  createdAt: time,
  updatedAt: time,
  publicationState: 'unpublished',
  publishedAt: null,
  offlineAt: null,
  offlineReason: null,
  availability: 'available',
  availabilityReason: null,
  lastCheckedAt: time,
  processingState: 'idle',
  targetConfigVersionId: otherId,
  effectiveConfigVersionId: null,
  effectiveIndexVersionId: null,
  includedFileCount: 0,
  searchableFileCount: 0,
};
export const processing = {
  chunking: {
    strategy: 'structure',
    overlapCodePoints: 200,
    removePageHeadersFooters: false,
    customMetadata: { user_topic: '主题' },
    embeddedMetadataFields: ['user_topic'],
  },
  embedding: {
    modelConfigVersionId: id,
    dimensions: 1024,
    distance: 'cosine',
    similarityThreshold: 0.5,
    candidatesPerQuery: 10,
    modelSnapshot: {
      endpoint: 'http://127.0.0.1:4567/v1',
      model: '固定嵌入模型',
    },
  },
};
export const task = {
  id: otherId,
  kind: 'knowledge_base_create',
  state: 'queued',
  phase: 'queued',
  createdAt: time,
  startedAt: null,
  finishedAt: null,
  knowledgeBaseId: id,
  fileId: null,
  targetContentVersionId: null,
  targetConfigVersionId: otherId,
  retryOfTaskId: null,
  attempt: 0,
  progressCompleted: 0,
  progressTotal: null,
  failure: null,
  warnings: [],
  resultResourceId: null,
};
export const config = {
  id: otherId,
  knowledgeBaseId: id,
  createdAt: time,
  parserRuleVersion: '1',
  chunkRuleVersion: '1',
  lengthMeterVersion: '1',
  input: processing,
};
export const source = {
  id: otherId,
  knowledgeBaseId: id,
  type: 'file',
  resourceId: otherId,
  createdAt: time,
};
export const file = {
  knowledgeBaseId: id,
  fileId: otherId,
  sources: [source],
  excluded: false,
  included: true,
  latestContentVersionId: otherId,
  effectiveContentVersionId: null,
  effectiveIndexVersionId: null,
  targetContentVersionId: otherId,
  targetConfigVersionId: otherId,
  updateState: 'queued',
  usingPreviousVersion: false,
  task: {
    ...task,
    kind: 'index_file',
    fileId: otherId,
    targetContentVersionId: otherId,
  },
  failure: null,
  effectiveConfigVersionId: null,
};
export async function setup(reply) {
  const requests = [];
  let respond = reply;
  const client = createSystemClient(async (config) => {
    if (config.url === '/session')
      return {
        data: {
          sessionId: id,
          csrfToken: '固定保护令牌'.repeat(10),
          expiresAt: time,
        },
        status: 200,
        config,
      };
    if (config.url === '/system/status')
      return {
        data: {
          applicationVersion: '0.1',
          startedAt: time,
          writeState: 'ready',
          dataDirectory: '测试目录',
          schemaVersion: 1,
          warnings: [],
        },
        status: 200,
        config,
      };
    requests.push(config);
    const result = await respond(config);
    const status =
      result?.errorStatus ||
      result?.httpStatus ||
      (config.method === 'put' && config.url.endsWith('/processing-config')
        ? 202
        : undefined) ||
      (config.method === 'post'
        ? config.url === '/knowledge-bases' ||
          config.url.endsWith('/search-test') ||
          config.url.endsWith('/check')
          ? 202
          : /\/(publish|offline|cancel)$/.test(config.url)
            ? 200
            : 201
        : 200);
    const data =
      result?.errorStatus || result?.httpStatus ? result.body : result;
    const captured = { config, status, data };
    config.observed = captured;
    if (status >= 400) throw { response: captured };
    return captured;
  });
  await client.connect();
  return {
    client,
    api: createKnowledgeBaseClient(client),
    requests,
    reply: (next) => {
      respond = typeof next === 'function' ? next : () => next;
    },
  };
}
