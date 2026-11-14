import { createServer } from 'node:http';
import { configureFixedModelLimits } from './fixed-model-limits.mjs';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';

export async function checkSettings(page, tests) {
  const secret = 'fixed-browser-fixture-credential';
  const probes = [];
  const server = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = chunks.length
      ? JSON.parse(Buffer.concat(chunks).toString())
      : null;
    probes.push({
      path: request.url,
      method: request.method,
      hasCookie: !!request.headers.cookie,
      hasCsrf: !!request.headers['x-csrf-token'],
      bearer: request.headers.authorization === `Bearer ${secret}`,
      vectorKey: request.headers['api-key'] === secret,
      body,
    });
    response.setHeader('Content-Type', 'application/json');
    if (request.url === '/v1/chat/completions') {
      response.end(
        JSON.stringify({
          id: 'fixture-completion',
          object: 'chat.completion',
          created: 1,
          model: body.model,
          choices: [
            {
              index: 0,
              message: { role: 'assistant', content: '连接正常' },
              finish_reason: 'stop',
            },
          ],
          usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
        }),
      );
    } else if (request.url === '/v1/embeddings') {
      response.end(
        JSON.stringify({
          object: 'list',
          model: body.model,
          data: [{ object: 'embedding', index: 0, embedding: [0.1, 0.2, 0.3] }],
          usage: { prompt_tokens: 1, total_tokens: 1 },
        }),
      );
    } else if (request.url === '/collections') {
      response.end(
        JSON.stringify({ status: 'ok', result: { collections: [] } }),
      );
    } else {
      response.statusCode = 401;
      response.end(
        JSON.stringify({
          error: { message: '受控拒绝', type: 'authentication_error' },
        }),
      );
    }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const endpoint = `http://127.0.0.1:${server.address().port}`;
  const outgoing = [];
  const observe = (request) => {
    const path = new URL(request.url()).pathname;
    if (path.includes('/settings/') || path.startsWith('/api/v1/tasks/'))
      outgoing.push({
        method: request.method(),
        path,
        key: request.headers()['idempotency-key'],
        hasProtection: !!request.headers()['x-csrf-token'],
      });
  };
  page.on('request', observe);
  const panel = (kind) => page.locator(`[data-kind="${kind}"]`);
  const save = async (kind, values, action = 'replace') => {
    const form = panel(kind);
    await form.getByLabel('基础地址').fill(values.endpoint);
    if (kind !== 'vector') await form.getByLabel('模型标识').fill(values.model);
    if (kind === 'generation') {
      await form.getByLabel('上下文窗口词元数').fill('1024');
      await form.getByLabel('最大输出词元数').fill('16');
    }
    await form.getByLabel('密钥操作').selectOption(action);
    if (action === 'replace') await form.getByLabel('新密钥').fill(secret);
    const response = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        response
          .url()
          .includes(
            kind === 'vector'
              ? '/settings/vector-store'
              : `/settings/models/${kind}`,
          ),
    );
    await form.getByRole('button', { name: '保存配置', exact: true }).click();
    const saved = await response;
    assert.equal(saved.status(), 200);
    await form.getByRole('status').filter({ hasText: '已保存' }).waitFor();
    assert.equal(await form.locator('input[type=password]').count(), 0);
    return saved.json();
  };
  const check = async (kind, available = true) => {
    const form = panel(kind);
    if (kind !== 'vector') await form.getByRole('checkbox').check();
    const response = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response
          .url()
          .includes(
            kind === 'vector'
              ? '/settings/vector-store/test'
              : `/settings/models/${kind}/test`,
          ),
    );
    await form
      .getByRole('button', { name: '测试已保存连接', exact: true })
      .click();
    const accepted = await response;
    assert.equal(accepted.status(), 202);
    const data = await accepted.json();
    for (let index = 0; index < 100; index++) {
      if (
        await form
          .getByRole('status')
          .filter({ hasText: available ? '本次连接可用' : '本次连接不可用' })
          .count()
      )
        break;
      await page.waitForTimeout(100);
      const refresh = form.getByRole('button', {
        name: '查询原任务',
        exact: true,
      });
      if ((await refresh.count()) && (await refresh.isEnabled()))
        await refresh.click();
    }
    assert.equal(
      await form
        .getByRole('status')
        .filter({ hasText: available ? '本次连接可用' : '本次连接不可用' })
        .count(),
      1,
      `受控测试结果不符：${await form.textContent()}；已观测探测数：${probes.length}`,
    );
    return data.task;
  };
  try {
    await configureFixedModelLimits(
      page.context().request,
      new URL(page.url()).origin,
      [
        { endpoint: endpoint + '/v1', model: '未核实生成模型' },
        { endpoint: endpoint + '/v1', model: '固定嵌入模型' },
        { endpoint: endpoint + '/denied', model: '未核实生成模型' },
      ],
    );
    await page.getByRole('link', { name: '设置', exact: true }).click();
    await panel('generation').getByLabel('基础地址').waitFor();
    assert.equal(
      await panel('generation').getByLabel('基础地址').inputValue(),
      '',
    );
    assert.equal(
      await panel('embedding').getByLabel('模型标识').inputValue(),
      '',
    );
    assert.equal(await panel('vector').getByLabel('基础地址').inputValue(), '');
    assert.equal(
      await panel('generation')
        .getByRole('button', { name: '测试已保存连接' })
        .isDisabled(),
      true,
    );
    const generation = await save('generation', {
      endpoint: endpoint + '/v1',
      model: '未核实生成模型',
    });
    const embedding = await save('embedding', {
      endpoint: endpoint + '/v1',
      model: '固定嵌入模型',
    });
    await save('vector', { endpoint }, 'clear');
    assert.equal(probes.length, 0);
    assert.equal(
      await panel('generation')
        .getByRole('button', { name: '测试已保存连接' })
        .isDisabled(),
      true,
    );
    await check('generation');
    await check('embedding');
    await check('vector');
    assert.equal(probes.length, 3);
    assert.ok(probes.every((probe) => !probe.hasCookie && !probe.hasCsrf));
    const generated = probes.find(
      (probe) => probe.path === '/v1/chat/completions',
    );
    assert.equal(generated.bearer, true);
    assert.deepEqual(generated.body, {
      model: '未核实生成模型',
      messages: [{ role: 'user', content: '请回复连接正常。' }],
      stream: false,
      max_tokens: 16,
    });
    const embedded = probes.find((probe) => probe.path === '/v1/embeddings');
    assert.equal(embedded.bearer, true);
    assert.deepEqual(embedded.body, {
      model: '固定嵌入模型',
      input: ['连接测试'],
      encoding_format: 'float',
    });
    const vectorProbe = probes.find((probe) => probe.path === '/collections');
    assert.equal(vectorProbe.method, 'GET');
    assert.equal(vectorProbe.body, null);
    assert.equal(vectorProbe.vectorKey, false);
    const beforePosts = outgoing.filter(
      (request) => request.method === 'POST',
    ).length;
    await panel('generation')
      .getByRole('button', { name: '查询原任务', exact: true })
      .click();
    assert.equal(
      outgoing.filter((request) => request.method === 'POST').length,
      beforePosts,
    );
    const savedAgain = await save('generation', {
      endpoint: endpoint + '/v1',
      model: '未核实生成模型',
    });
    assert.equal(savedAgain.versionId, generation.versionId);
    assert.equal(
      await panel('generation')
        .getByRole('status')
        .filter({ hasText: '本次连接可用' })
        .count(),
      0,
    );
    const cross = panel('generation');
    await cross
      .getByLabel('基础地址')
      .fill('http://localhost:' + server.address().port + '/v1');
    const refused = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        response.url().endsWith('/settings/models/generation'),
    );
    await cross.getByRole('button', { name: '保存配置', exact: true }).click();
    assert.equal((await refused).status(), 400);
    await cross
      .getByText('连接来源已变化，请明确替换或清除密钥后保存。', {
        exact: true,
      })
      .waitFor();
    assert.equal(
      await cross
        .getByLabel('密钥操作')
        .evaluate((element) => element === document.activeElement),
      true,
    );
    assert.equal(probes.length, 3);
    await cross.getByLabel('基础地址').fill(endpoint + '/v1');
    const history = page.getByRole('region', {
      name: '已保存嵌入版本',
      exact: true,
    });
    await history
      .getByRole('button', { name: '刷新版本', exact: true })
      .click();
    await history
      .locator('li button')
      .filter({ hasText: embedding.versionId })
      .click();
    const details = panel('history');
    await details.getByLabel('密钥操作').selectOption('replace');
    await details.getByLabel('新密钥').fill(secret);
    const rotation = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        response.url().endsWith(`/versions/${embedding.versionId}/credential`),
    );
    await details
      .getByRole('button', { name: '保存配置', exact: true })
      .click();
    assert.equal((await rotation).status(), 200);
    await details.waitFor({ state: 'detached' });
    await save('generation', {
      endpoint: endpoint + '/denied',
      model: '未核实生成模型',
    });
    await check('generation', false);
    assert.ok(
      (await panel('generation').textContent()).includes('可能已产生模型费用'),
    );
    assert.ok(
      outgoing
        .filter(
          (request) => request.method === 'PUT' || request.method === 'POST',
        )
        .every((request) => request.hasProtection),
    );
    assert.ok(
      outgoing
        .filter((request) => request.method === 'POST')
        .every((request) => /^[0-9a-f-]{36}$/i.test(request.key)),
    );
    assert.equal(
      await page.evaluate(() => localStorage.length + sessionStorage.length),
      0,
    );
    assert.equal(await page.locator('input[type=password]').count(), 0);
    await mkdir('artifacts', { recursive: true });
    for (const width of [1280, 1440, 1920]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.locator('.formal-main').evaluate((element) => {
        element.scrollTop = 0;
      });
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      await page.screenshot({
        path: `artifacts/live-settings-${width}.png`,
        fullPage: true,
      });
    }
    tests.push({
      name: '真实设置页与受控兼容模型及向量服务端到端联通',
      passed: true,
      operationIds: [
        'getGenerationModel',
        'updateGenerationModel',
        'testGenerationModel',
        'getEmbeddingModel',
        'updateEmbeddingModel',
        'testEmbeddingModel',
        'listEmbeddingModelVersions',
        'getEmbeddingModelVersion',
        'updateEmbeddingVersionCredential',
        'getConnectionTestResult',
        'getVectorConfig',
        'updateVectorConfig',
        'testVectorConfig',
        'getTask',
      ],
      requests: outgoing.map(({ key, ...request }) => ({
        ...request,
        hasIdempotencyKey: !!key,
      })),
      assertions: [
        '初始空配置与完整性',
        '保存不外发且秘密清理',
        '三独立配置实际PUT与CSRF',
        '明确费用同意及幂等请求',
        '固定非个人负载与认证隔离',
        '向量只读且无模型请求',
        '原任务查询不重交',
        '相同版本成功保存仍撤销有效标记',
        '跨源keep原子400及操作字段焦点',
        '历史版本独立凭据轮换',
        '不可用结果与费用提示',
        '浏览器无持久化',
        '三宽无横向溢出与截图',
      ],
      constraintIds: [
        '#/paths/~1settings~1models~1generation/put/description',
        '#/paths/~1settings~1models~1embedding/put/description',
        '#/paths/~1settings~1models~1generation~1test/post/description',
        '#/paths/~1settings~1models~1embedding~1test/post/description',
        '#/paths/~1settings~1vector-store~1test/post/description',
        '#/paths/~1settings~1models~1embedding~1versions~1{modelConfigVersionId}~1credential/put/description',
        '#/paths/~1tasks~1{taskId}/get/description',
        '#/paths/~1tasks~1{taskId}~1connection-test/get/description',
        'security.7',
      ],
    });
  } finally {
    page.off('request', observe);
    await new Promise((resolve) => server.close(resolve));
  }
}
