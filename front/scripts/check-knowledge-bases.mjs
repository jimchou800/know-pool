import { createServer } from 'node:http';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';

export async function checkKnowledgeBases(page, tests) {
  await mkdir('artifacts', { recursive: true });
  const collections = new Map();
  const probes = [];
  let rejectCreation = false;
  const vector = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = chunks.length
      ? JSON.parse(Buffer.concat(chunks).toString())
      : null;
    probes.push({ method: request.method, path: request.url });
    response.setHeader('Content-Type', 'application/json');
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
      path.startsWith('/api/v1/tasks/')
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
      model: '仅保存不外发的嵌入模型',
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
    await page.getByLabel(dataset.name, { exact: true }).check();
    const accepted = await save();
    await detail.getByRole('heading', { name: /^初始资料库/ }).waitFor();
    const resource = /\/knowledge-bases\/([^/]+)$/.exec(
      new URL(page.url()).hash.slice(1),
    )?.[1];
    assert.ok(resource);
    await detail.getByText('固定知识库资料.txt', { exact: true }).waitFor();
    assert.ok((await detail.innerText()).includes('已纳入'));
    assert.ok((await detail.innerText()).includes('尚未生效'));
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
      ['queued', 'running', 'failed'].includes(filesReply.items[0].task.state),
    );
    assert.equal(filesReply.items[0].task.kind, 'index_file');
    assert.equal(filesReply.items[0].effectiveIndexVersionId, null);
    assert.equal((await add('dataset')).status(), 409);
    await page.getByRole('button', { name: '取消关联', exact: true }).click();
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
      probes.some((p) => /embeddings|chat\/completions/.test(p.path)),
      false,
    );
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
        '真实应用接口与本机向量替身联通；所有模型路径零请求',
        '空库创建受理后查询原任务，成功才进入详情，发布及可用及处理状态独立',
        '显式配置与无密钥嵌入身份快照、初始datasetIds、名称说明保存及刷新仍保留',
        '数据集与文件来源并集只保留一份文件与排队意图，重复来源409且无新索引',
        '远端拒绝保留失败原任务，显式修改后新键重建成功',
        '三宽长名称表单与详情无横向溢出且保存截图，浏览器不持久化凭据',
      ],
      tasks: { initialAccepted: !!accepted.id },
    });
  } finally {
    page.off('request', observe);
    await new Promise((resolve) => vector.close(resolve));
  }
}
