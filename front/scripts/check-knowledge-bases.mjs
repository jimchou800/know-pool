import { createServer } from 'node:http';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { checkDocumentIndex } from './check-document-index.mjs';
import { checkChunkPreflight } from './check-chunk-preflight.mjs';
import { checkSourceAssociations } from './check-source-associations.mjs';
import { checkContentSwitch } from './check-content-switch.mjs';
import { checkPublication } from './check-publication.mjs';
import { checkMultiSearch } from './check-multi-search.mjs';
import { checkSearchInstances } from './check-search-instances.mjs';
import { checkGroundedAnswer } from './check-grounded-answer.mjs';
import { checkConfigurationRebuild } from './check-configuration-rebuild.mjs';

export async function checkKnowledgeBases(page, tests) {
  await mkdir('artifacts', { recursive: true });
  const collections = new Map();
  const points = new Map();
  const probes = [];
  let rejectCreation = false;
  let noMatches = false;
  let rejectEmbedding = false;
  let releaseEmbedding;
  let embeddingGate;
  const vector = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = chunks.length
      ? JSON.parse(Buffer.concat(chunks).toString())
      : null;
    probes.push({ method: request.method, path: request.url });
    response.setHeader('Content-Type', 'application/json');
    const pathname = new URL(request.url, 'http://127.0.0.1').pathname;
    if (pathname === '/v1/embeddings') {
      if (embeddingGate) await embeddingGate;
      if (rejectEmbedding) {
        response.statusCode = 503;
        response.end(JSON.stringify({ error: { message: '固定依赖不可用' } }));
        return;
      }
      assert.equal(body.model, 'text-embedding-v4');
      const inputs = Array.isArray(body.input) ? body.input : [body.input];
      response.end(
        JSON.stringify({
          object: 'list',
          data: inputs.map((_, index) => ({
            object: 'embedding',
            index,
            embedding: [1, 0, 0],
          })),
          model: 'text-embedding-v4',
          usage: { prompt_tokens: 4, total_tokens: 4 },
        }),
      );
      return;
    }
    const pointCollection =
      /^\/collections\/(kp_[a-f0-9]{32})\/points(?:\/query)?$/.exec(
        pathname,
      )?.[1];
    if (pointCollection) {
      const saved = points.get(pointCollection) || new Map();
      if (pathname.endsWith('/query')) {
        assert.ok(
          body.filter.must.some(
            (filter) => filter.key === 'index_id' && filter.match.any.length,
          ),
        );
        const allowed = body.filter.must.find(
          (filter) => filter.key === 'index_id',
        ).match.any;
        response.end(
          JSON.stringify({
            status: 'ok',
            result: {
              points: noMatches
                ? []
                : [...saved.values()]
                    .filter((point) => allowed.includes(point.payload.index_id))
                    .map((point) => ({ ...point, score: 0.9 }))
                    .slice(0, body.limit),
            },
          }),
        );
      } else if (request.method === 'PUT') {
        for (const point of body.points) {
          assert.equal(point.vector.length, 3);
          saved.set(point.id, point);
        }
        points.set(pointCollection, saved);
        response.end(
          JSON.stringify({ status: 'ok', result: { status: 'completed' } }),
        );
      } else {
        response.end(
          JSON.stringify({
            status: 'ok',
            result: body.ids.map((id) => saved.get(id)).filter(Boolean),
          }),
        );
      }
      return;
    }
    const name = /^\/collections\/(kp_[a-f0-9]{32})$/.exec(request.url)?.[1];
    if (!name) {
      response.statusCode = 400;
      response.end(JSON.stringify({ status: 'error', result: null }));
    } else if (request.method === 'GET') {
      response.statusCode = collections.has(name) ? 200 : 404;
      response.end(
        JSON.stringify({
          status: collections.has(name) ? 'ok' : 'error',
          result: collections.has(name)
            ? { config: { params: { vectors: collections.get(name) } } }
            : null,
        }),
      );
    } else if (request.method === 'PUT') {
      if (rejectCreation) {
        response.statusCode = 503;
        response.end(JSON.stringify({ status: 'error', result: null }));
      } else {
        assert.ok(body.vectors.size > 0);
        assert.ok(['Cosine', 'Dot', 'Euclid'].includes(body.vectors.distance));
        collections.set(name, body.vectors);
        response.end(JSON.stringify({ status: 'ok', result: true }));
      }
    } else if (request.method === 'DELETE') {
      collections.delete(name);
      response.end(JSON.stringify({ status: 'ok', result: true }));
    } else {
      response.statusCode = 400;
      response.end(JSON.stringify({ status: 'error', result: null }));
    }
  });
  await new Promise((resolve) => vector.listen(0, '127.0.0.1', resolve));
  const endpoint = `http://127.0.0.1:${vector.address().port}`;
  const requests = [];
  const observe = (request) => {
    const path = new URL(request.url()).pathname;
    if (
      path.startsWith('/api/v1/knowledge-bases') ||
      path.startsWith('/api/v1/tasks/') ||
      path.startsWith('/api/v1/search-requests/')
    )
      requests.push({
        method: request.method(),
        path,
        hasProtection: !!request.headers()['x-csrf-token'],
        key: request.headers()['idempotency-key'],
      });
  };
  page.on('request', observe);
  try {
    const base = new URL(page.url()).origin;
    const http = page.context().request;
    const session = await (await http.get(`${base}/api/v1/session`)).json();
    const headers = { 'X-CSRF-Token': session.csrfToken };
    const put = async (path, data) => {
      const response = await http.put(`${base}/api/v1${path}`, {
        headers,
        data,
      });
      assert.equal(response.status(), 200);
      return response.json();
    };
    await put('/settings/vector-store', {
      endpoint,
      credential: { action: 'clear' },
    });
    const embeddingVersion = await put('/settings/models/embedding', {
      endpoint: `${endpoint}/v1`,
      model: 'text-embedding-v4',
      contextWindowTokens: null,
      maxOutputTokens: null,
      credential: { action: 'replace', value: 'fixed-knowledge-base-fixture' },
    });
    const datasetReply = await http.post(`${base}/api/v1/datasets`, {
      headers,
      data: { name: '知识库联调资料', description: '固定非个人资料' },
    });
    assert.equal(datasetReply.status(), 201);
    const dataset = await datasetReply.json();
    const upload = await http.post(
      `${base}/api/v1/datasets/${dataset.id}/files:upload`,
      {
        headers,
        multipart: {
          files: {
            name: '固定知识库资料.txt',
            mimeType: 'text/plain',
            buffer: Buffer.from('固定知识库联调资料，不调用模型。', 'utf8'),
          },
        },
      },
    );
    assert.equal(upload.status(), 200);
    const datasetFiles = await (
      await http.get(`${base}/api/v1/datasets/${dataset.id}/files?limit=20`)
    ).json();
    assert.equal(datasetFiles.items.length, 1);
    const file = datasetFiles.items[0];
    await page.getByRole('link', { name: '知识库', exact: true }).click();
    const detail = page.getByRole('region', {
      name: '知识库详情',
      exact: true,
    });
    const start = async (name) => {
      await page
        .getByRole('button', { name: '创建知识库', exact: true })
        .click();
      await page
        .getByLabel('嵌入配置版本', { exact: true })
        .selectOption(embeddingVersion.versionId);
      await page.getByLabel('名称', { exact: true }).fill(name);
      await page.getByLabel('相似度阈值（必填）').fill('0.5');
      await page.getByLabel('每条查询候选数（必填）').fill('10');
      await page.getByLabel('嵌入维度', { exact: true }).fill('3');
    };
    const save = async () => {
      const response = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname === '/api/v1/knowledge-bases',
      );
      await page
        .getByRole('button', { name: '保存知识库', exact: true })
        .click();
      const accepted = await response;
      assert.equal(accepted.status(), 202);
      return (await accepted.json()).task;
    };
    await start('空库联调');
    await checkPublication(page, tests, {
      http,
      base,
      headers,
      datasetId: dataset.id,
      knowledgeBaseId: resource,
      gateEmbedding: () => {
        embeddingGate = new Promise((resolve) => {
          releaseEmbedding = resolve;
        });
      },
      releaseEmbedding: () => {
        releaseEmbedding?.();
        embeddingGate = undefined;
      },
      rejectEmbedding: (value) => {
        rejectEmbedding = value;
      },
    });
    await checkMultiSearch(page, tests, {
      http,
      base,
      headers,
      datasetId: dataset.id,
      knowledgeBaseId: resource,
      noMatches: (value) => {
        noMatches = value;
      },
      gateEmbedding: () => {
        embeddingGate = new Promise((resolve) => {
          releaseEmbedding = resolve;
        });
      },
      releaseEmbedding: () => {
        releaseEmbedding?.();
        embeddingGate = undefined;
      },
    });
    await checkSearchInstances(page, tests, {
      http,
      base,
      headers,
      knowledgeBaseId: resource,
      gateEmbedding: () => {
        embeddingGate = new Promise((resolve) => {
          releaseEmbedding = resolve;
        });
      },
      releaseEmbedding: () => {
        releaseEmbedding?.();
        embeddingGate = undefined;
      },
    });
    await checkGroundedAnswer(page, tests, {
      http,
      base,
      headers,
      knowledgeBaseId: resource,
      noMatches: (value) => {
        noMatches = value;
      },
      gateEmbedding: () => {
        embeddingGate = new Promise((resolve) => {
          releaseEmbedding = resolve;
        });
      },
      releaseEmbedding: () => {
        releaseEmbedding?.();
        embeddingGate = undefined;
      },
    });
    for (const width of [1280, 1440, 1920]) {
      await page.setViewportSize({ width, height: 1000 });
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      await page.screenshot({
        path: `artifacts/live-knowledge-base-create-${width}.png`,
      });
    }
    await save();
    await detail
      .getByRole('heading', { name: '空库联调', exact: true })
      .waitFor();
    assert.ok((await detail.innerText()).includes('尚无有效文件'));
    assert.ok((await detail.innerText()).includes('未发布'));
    assert.ok((await detail.innerText()).includes('运行可用'));
    assert.ok((await detail.innerText()).includes('暂无处理任务'));
    const emptySearch = page.getByRole('region', {
      name: '单库检索测试',
      exact: true,
    });
    const callsBeforeEmpty = probes.filter((probe) =>
      /embeddings/.test(probe.path),
    ).length;
    await emptySearch.getByLabel('检索内容').fill('空库不调用模型');
    await emptySearch
      .getByRole('button', { name: '执行单库检索', exact: true })
      .click();
    await emptySearch
      .getByText('没有生效索引，请先完成资料处理。', { exact: true })
      .waitFor();
    assert.equal(
      probes.filter((probe) => /embeddings/.test(probe.path)).length,
      callsBeforeEmpty,
    );
    await detail.getByRole('button', { name: '修改名称与说明' }).click();
    await page.getByLabel('名称', { exact: true }).fill('空库联调已维护');
    await page
      .getByLabel('说明', { exact: true })
      .fill('中文说明与空库均已保存');
    await page.getByRole('button', { name: '保存修改', exact: true }).click();
    await detail
      .getByRole('heading', { name: '空库联调已维护', exact: true })
      .waitFor();
    await start('初始资料库' + '较长名称用于检验排版'.repeat(5));
    embeddingGate = new Promise((resolve) => {
      releaseEmbedding = resolve;
    });
    await page.getByLabel(dataset.name, { exact: true }).check();
    const accepted = await save();
    await detail.getByRole('heading', { name: /^初始资料库/ }).waitFor();
    const resource = /\/knowledge-bases\/([^/]+)$/.exec(
      new URL(page.url()).hash.slice(1),
    )?.[1];
    assert.ok(resource);
    await detail.getByText('固定知识库资料.txt', { exact: true }).waitFor();
    assert.ok((await detail.innerText()).includes('已纳入'));
    const add = async (type) => {
      await detail
        .getByRole('button', { name: '关联资料', exact: true })
        .click();
      await page.getByLabel('关联类型', { exact: true }).selectOption(type);
      await page
        .getByLabel('选择数据集', { exact: true })
        .selectOption(dataset.id);
      if (type === 'file')
        await page
          .getByLabel('选择资料', { exact: true })
          .selectOption(file.id);
      const reply = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new URL(r.url()).pathname.endsWith('/sources'),
      );
      await page.getByRole('button', { name: '保存关联', exact: true }).click();
      return reply;
    };
    assert.equal((await add('file')).status(), 201);
    await detail
      .getByRole('button', { name: '关联资料', exact: true })
      .waitFor();
    const filesReply = await (
      await http.get(
        `${base}/api/v1/knowledge-bases/${resource}/files?limit=20`,
      )
    ).json();
    assert.equal(filesReply.items.length, 1);
    assert.equal(filesReply.items[0].sources.length, 2);
    assert.ok(
      ['queued', 'running', 'failed', 'succeeded'].includes(
        filesReply.items[0].task.state,
      ),
    );
    assert.equal(filesReply.items[0].task.kind, 'index_file');
    assert.equal((await add('dataset')).status(), 409);
    await page.getByRole('button', { name: '取消关联', exact: true }).click();
    const indexedTaskId = filesReply.items[0].task.id;
    const streamPath = `/api/v1/tasks/${indexedTaskId}/events`;
    await page.evaluate((path) => {
      const originalFetch = window.fetch;
      window.fetch = async (input, options) => {
        if (new URL(input, location.href).pathname !== path)
          return originalFetch(input, options);
        window.fetch = originalFetch;
        const controller = new AbortController();
        options?.signal?.addEventListener('abort', () => controller.abort(), {
          once: true,
        });
        const response = await originalFetch(input, {
          ...options,
          signal: controller.signal,
        });
        setTimeout(
          () => controller.abort(new TypeError('固定事件连接中断')),
          100,
        );
        return response;
      };
    }, streamPath);
    let disconnected = false;
    const disconnect = page.waitForEvent('requestfailed', {
      predicate: (request) => {
        if (new URL(request.url()).pathname !== streamPath) return false;
        disconnected = true;
        return true;
      },
    });
    const recovery = page.waitForResponse(
      (reply) =>
        disconnected &&
        new URL(reply.url()).pathname === `/api/v1/tasks/${indexedTaskId}`,
    );
    const subscribed = page.waitForResponse(
      (reply) => disconnected && new URL(reply.url()).pathname === streamPath,
    );
    await page
      .getByRole('button', { name: '刷新处理任务', exact: true })
      .click();
    await page
      .getByRole('button', { name: '跟踪处理任务', exact: true })
      .first()
      .click();
    await disconnect;
    assert.equal((await recovery).status(), 200);
    const stream = await subscribed;
    assert.equal(stream.status(), 200);
    assert.ok(
      (await stream.allHeaders())['content-type'].startsWith(
        'text/event-stream',
      ),
    );
    const eventRequest = await stream.request().allHeaders();
    assert.ok(eventRequest.cookie?.includes('KNOW_POOL_SESSION='));
    assert.equal(new URL(stream.url()).search, '');
    assert.equal(eventRequest['x-csrf-token'], undefined);
    assert.match(
      eventRequest['last-event-id'],
      new RegExp(`^${indexedTaskId}:[1-9][0-9]*$`),
    );
    assert.ok(
      eventRequest['last-event-id'].length >= 38 &&
        eventRequest['last-event-id'].length <= 53,
    );
    const streamCapture = page
      .evaluate(async (url) => {
        const response = await fetch(url, { credentials: 'same-origin' });
        if (!response.ok) throw new Error('事件独立读取失败');
        return response.text();
      }, `/api/v1/tasks/${indexedTaskId}/events`)
      .then(
        (value) => ({ value }),
        (error) => ({ error }),
      );
    releaseEmbedding();
    embeddingGate = undefined;
    let indexed;
    for (let attempt = 0; attempt < 100; attempt++) {
      indexed = (
        await (
          await http.get(
            `${base}/api/v1/knowledge-bases/${resource}/files?limit=20`,
          )
        ).json()
      ).items[0];
      if (!['queued', 'running'].includes(indexed.task.state)) break;
      await page.waitForTimeout(100);
    }
    assert.equal(indexed.task.state, 'succeeded');
    assert.ok(indexed.effectiveIndexVersionId);
    assert.equal(
      indexed.task.resultResourceId,
      indexed.effectiveIndexVersionId,
    );
    assert.equal(
      indexed.effectiveContentVersionId,
      indexed.latestContentVersionId,
    );
    const captured = await streamCapture;
    if (captured.error) throw captured.error;
    const eventFrames = captured.value
      .replace(/\r\n/g, '\n')
      .split('\n\n')
      .filter((frame) => frame.includes('data:'));
    assert.ok(eventFrames.length > 0);
    let finalEvent;
    for (const frame of eventFrames) {
      const fields = Object.fromEntries(
        frame
          .split('\n')
          .filter((line) => line && !line.startsWith(':'))
          .map((line) => {
            const colon = line.indexOf(':');
            return [
              line.slice(0, colon),
              line.slice(colon + 1).replace(/^ /, ''),
            ];
          }),
      );
      const data = JSON.parse(fields.data);
      assert.equal(data.taskId, indexedTaskId);
      assert.ok(Number.isSafeInteger(data.sequence) && data.sequence > 0);
      assert.equal(fields.id, `${indexedTaskId}:${data.sequence}`);
      if (fields.event === 'terminal') {
        assert.equal(data.state, 'succeeded');
        assert.equal(data.phase, 'completed');
        finalEvent = data;
      } else assert.equal(fields.event, 'phase');
    }
    assert.ok(finalEvent);
    const replay = await http.get(
      `${base}/api/v1/tasks/${indexedTaskId}/events`,
      {
        headers: { 'Last-Event-ID': `${indexedTaskId}:${finalEvent.sequence}` },
      },
    );
    assert.equal(replay.status(), 200);
    const replayText = await replay.text();
    assert.match(
      replayText,
      new RegExp(
        `(?:^|\\n)id: ?${indexedTaskId}:${finalEvent.sequence}(?:\\r?\\n|$)`,
      ),
    );
    assert.match(replayText, /(?:^|\n)event: ?terminal(?:\r?\n|$)/);
    const hostileStream = await http.get(
      `${base}/api/v1/tasks/${indexedTaskId}/events`,
      { headers: { Origin: 'https://untrusted.invalid' } },
    );
    assert.equal(hostileStream.status(), 403);
    const expiredStream = await http.get(
      `${base}/api/v1/tasks/${indexedTaskId}/events`,
      { headers: { Cookie: 'KNOW_POOL_SESSION=expired-fixed-session' } },
    );
    assert.equal(expiredStream.status(), 401);
    const library = await (
      await http.get(`${base}/api/v1/knowledge-bases/${resource}`)
    ).json();
    assert.equal(library.publicationState, 'unpublished');
    assert.ok(library.effectiveIndexVersionId);
    await page
      .getByRole('button', { name: '刷新处理任务', exact: true })
      .click();
    await detail.getByText('处理成功 · 已结束', { exact: true }).waitFor();
    const management = page.getByRole('region', {
      name: '单库检索测试',
      exact: true,
    });
    await management
      .getByLabel('检索内容', { exact: true })
      .fill('固定知识库联调资料');
    const resultQuery = page.waitForResponse((reply) =>
      /\/api\/v1\/search-requests\/[^/]+$/.test(new URL(reply.url()).pathname),
    );
    embeddingGate = new Promise((resolve) => {
      releaseEmbedding = resolve;
    });
    await management
      .getByRole('button', { name: '执行单库检索', exact: true })
      .click();
    const queryResponse = await resultQuery;
    assert.equal(queryResponse.status(), 200);
    const runningSearch = await queryResponse.json();
    assert.ok(['queued', 'running'].includes(runningSearch.task.state));
    assert.equal(runningSearch.result, null);
    releaseEmbedding();
    embeddingGate = undefined;
    await management
      .getByRole('button', { name: '查看原文依据', exact: true })
      .first()
      .waitFor();
    await management
      .getByRole('button', { name: '查看原文依据', exact: true })
      .first()
      .click();
    const evidence = management.getByRole('region', {
      name: '原文依据',
      exact: true,
    });
    await evidence.waitFor();
    assert.ok(
      (await evidence.innerText()).includes(indexed.effectiveContentVersionId),
    );
    assert.ok((await evidence.innerText()).includes('第1至1行'));
    assert.equal(
      requests.filter(
        (request) =>
          request.method === 'POST' && request.path.endsWith('/search-test'),
      ).length,
      2,
    );
    const ssePrefix = '#/paths/~1tasks~1{taskId}~1events/get';
    tests.push({
      name: '真实浏览器索引事件中断原任务恢复与单库版本保护',
      passed: true,
      operationIds: [
        'watchTask',
        'getTask',
        'listKnowledgeBaseFiles',
        'getKnowledgeBase',
        'testKnowledgeBaseSearch',
        'getSearchRequest',
        'getSearchEvidence',
      ],
      constraintIds: [
        'security.8',
        '#/x-business-constraints/indexSwitch',
        '#/x-business-constraints/publication',
        '#/x-business-constraints/cost',
        '#/x-business-constraints/sse',
        `${ssePrefix}/x-sse/reconnect`,
        `${ssePrefix}/x-sse/events/phase`,
        `${ssePrefix}/x-sse/events/terminal`,
        `${ssePrefix}/x-sse/id`,
        `${ssePrefix}/x-sse/lastEventIdHeader`,
        `${ssePrefix}/x-sse/terminalStates`,
        `${ssePrefix}/description`,
        `${ssePrefix}/parameters/0/required`,
        `${ssePrefix}/parameters/0/schema/type`,
        `${ssePrefix}/parameters/0/schema/format`,
        `${ssePrefix}/parameters/1/required`,
        `${ssePrefix}/parameters/1/schema/type`,
        `${ssePrefix}/parameters/1/schema/minLength`,
        `${ssePrefix}/parameters/1/schema/maxLength`,
        `${ssePrefix}/parameters/1/schema/pattern`,
        '#/paths/~1knowledge-bases~1{knowledgeBaseId}~1search-test/post/description',
        '#/paths/~1search-requests~1{requestId}/get/description',
        '#/paths/~1search-requests~1{requestId}~1evidence~1{evidenceId}/get/description',
      ],
      assertions: [
        '真实浏览器中断一次事件流后先GET原任务，再恢复Cookie鉴权同源事件流；没有URL令牌或新建索引提交',
        '空库管理检索无索引失败且未外发嵌入；未发布库索引完成仍未发布，管理检索成功',
        '首次索引任务成功资源与实际生效索引版本一致、内容版本一致；查询结果由真实GET获取，证据绑定实际版本及原文行',
        '两次显式检索分别为空库和当前库，恢复过程不产生新检索提交；所有模型请求均本机替身，无回答生成',
      ],
    });
    for (const width of [1280, 1440, 1920]) {
      await page.setViewportSize({ width, height: 1000 });
      await management.scrollIntoViewIfNeeded();
      await evidence.scrollIntoViewIfNeeded();
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      await page.screenshot({
        path: `artifacts/live-index-search-evidence-${width}.png`,
      });
    }
    await evidence.getByRole('button', { name: '关闭原文依据' }).click();
    noMatches = true;
    await management
      .getByRole('button', { name: '执行单库检索', exact: true })
      .click();
    await management.getByText('未找到相关内容。', { exact: true }).waitFor();
    noMatches = false;
    rejectEmbedding = true;
    await management
      .getByRole('button', { name: '执行单库检索', exact: true })
      .click();
    await management.getByRole('alert').waitFor();
    assert.equal(
      await management.getByText('未找到相关内容。', { exact: true }).count(),
      0,
    );
    rejectEmbedding = false;
    await checkDocumentIndex(page, tests, {
      http,
      base,
      headers,
      datasetId: dataset.id,
      knowledgeBaseId: resource,
    });
    await checkChunkPreflight(page, tests);
    await checkConfigurationRebuild(page, tests, {
      http,
      base,
      headers,
      knowledgeBaseId: resource,
      gateEmbedding: () => {
        embeddingGate = new Promise((resolve) => {
          releaseEmbedding = resolve;
        });
      },
      releaseEmbedding: () => {
        releaseEmbedding?.();
        embeddingGate = undefined;
      },
      rejectEmbedding: (value) => {
        rejectEmbedding = value;
      },
    });
    await checkSourceAssociations(page, tests, {
      http,
      base,
      headers,
      datasetId: dataset.id,
      knowledgeBaseId: resource,
    });
    await checkContentSwitch(page, tests, {
      http,
      base,
      headers,
      datasetId: dataset.id,
      knowledgeBaseId: resource,
      gateEmbedding: () => {
        embeddingGate = new Promise((resolve) => {
          releaseEmbedding = resolve;
        });
      },
      releaseEmbedding: () => {
        releaseEmbedding?.();
        embeddingGate = undefined;
      },
    });
    for (const width of [1280, 1440, 1920]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.locator('.formal-main').evaluate((node) => {
        node.scrollTop = 0;
      });
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      await page.screenshot({
        path: `artifacts/live-knowledge-base-detail-${width}.png`,
      });
    }
    await page.reload();
    await detail.getByRole('heading', { name: /^初始资料库/ }).waitFor();
    await start('受控失败后恢复');
    rejectCreation = true;
    await save();
    await detail.getByText(/创建未完成/).waitFor();
    assert.ok((await detail.innerText()).includes('未完成'));
    await page.getByRole('button', { name: '修改后重新创建' }).click();
    rejectCreation = false;
    await save();
    await detail
      .getByRole('heading', { name: '受控失败后恢复', exact: true })
      .waitFor();
    const creationRequests = requests.filter(
      (r) => r.method === 'POST' && r.path === '/api/v1/knowledge-bases',
    );
    assert.ok(creationRequests.every((r) => r.hasProtection && r.key));
    assert.notEqual(creationRequests.at(-1).key, creationRequests.at(-2).key);
    assert.equal(
      probes.some((p) => /chat\/completions/.test(p.path)),
      false,
    );
    assert.ok(probes.some((p) => /embeddings/.test(p.path)));
    assert.equal(
      await page.evaluate(() => localStorage.length + sessionStorage.length),
      0,
    );
    await mkdir('artifacts', { recursive: true });
    tests.push({
      name: '真实知识库空库异步创建维护初始来源重叠去重及受控失败恢复',
      passed: true,
      operationIds: [
        'listKnowledgeBases',
        'createKnowledgeBase',
        'getKnowledgeBase',
        'updateKnowledgeBase',
        'getProcessingConfig',
        'addKnowledgeBaseSource',
        'listKnowledgeBaseSources',
        'listKnowledgeBaseFiles',
        'getTask',
        'listKnowledgeBaseTasks',
        'testKnowledgeBaseSearch',
        'getSearchRequest',
        'getSearchEvidence',
      ],
      requests: requests.map(({ key, ...rest }) => ({
        ...rest,
        hasIdempotencyKey: !!key,
      })),
      constraintIds: [
        '#/paths/~1knowledge-bases/post/description',
        '#/paths/~1knowledge-bases~1{knowledgeBaseId}~1sources/post/description',
        'security.7',
      ],
      assertions: [
        '真实应用接口与本机向量及嵌入替身联通；回答生成路径零请求',
        '空库创建受理后查询原任务，成功才进入详情，发布及可用及处理状态独立',
        '显式配置与无密钥嵌入身份快照、初始datasetIds、名称说明保存及刷新仍保留',
        '数据集与文件来源并集只保留一份文件与排队意图，重复来源409且无新索引',
        '远端拒绝保留失败原任务，显式修改后新键重建成功',
        '三宽长名称表单与详情无横向溢出且保存截图，浏览器不持久化凭据',
        '未发布单库索引生效后检索真实切片与版本位置；无命中和嵌入故障分别展示',
      ],
      tasks: { initialAccepted: !!accepted.id },
    });
  } finally {
    releaseEmbedding?.();
    page.off('request', observe);
    await new Promise((resolve) => vector.close(resolve));
  }
}
