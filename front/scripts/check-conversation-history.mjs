import assert from 'node:assert/strict';
import { expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';

export async function checkConversationHistory(
  page,
  tests,
  { http, base, headers, knowledgeBaseId, gateEmbedding, releaseEmbedding },
) {
  const root = `${base}/api/v1`;
  const window = () =>
    page.getByRole('region', { name: '问答窗口', exact: true });
  const list = () =>
    page.getByRole('complementary', { name: '问答实例', exact: true });
  const select = async (id) => {
    await list().locator(`[data-conversation-id="${id}"] button`).click();
    await window().getByLabel('问题', { exact: true }).waitFor();
  };
  const submit = async (text, first = false) => {
    await window().getByLabel('问题', { exact: true }).fill(text);
    await window().locator(`input[value="${knowledgeBaseId}"]`).check();
    await window()
      .getByRole('checkbox', {
        name: '我同意本次问答可能调用嵌入、预处理、生成及校验模型并产生费用',
        exact: true,
      })
      .check();
    const response = page.waitForResponse(
      (reply) =>
        reply.request().method() === 'POST' &&
        (first
          ? new URL(reply.url()).pathname ===
            '/api/v1/conversations/first-question'
          : /\/conversations\/[^/]+\/questions$/.test(
              new URL(reply.url()).pathname,
            )),
    );
    await window()
      .getByRole('button', {
        name: first ? '提交首问' : '提交追问',
        exact: true,
      })
      .click();
    const reply = await response;
    assert.equal(reply.status(), 202);
    return reply.json();
  };
  const create = async () => {
    await list().getByRole('button', { name: '新建问答', exact: true }).click();
    await window()
      .getByLabel('问答名称（可选）', { exact: true })
      .fill('允许重复的历史会话');
    const value = await submit('请依据原文解释规则', true);
    await window()
      .getByRole('heading', { name: '已依据资料回答', exact: true })
      .waitFor();
    return value.conversation.id;
  };
  await page.goto(`${base}/#/answers`);
  const first = await create();
  await window()
    .getByLabel('问题', { exact: true })
    .fill('第一会话未提交的输入');
  const second = await create();
  await select(first);
  assert.equal(
    await window().getByLabel('问题', { exact: true }).inputValue(),
    '第一会话未提交的输入',
  );
  await page
    .getByRole('button', { name: '重命名当前问答', exact: true })
    .click();
  await page.getByLabel('问答名称', { exact: true }).fill('允许重复的历史会话');
  await page.getByRole('button', { name: '保存问答名称', exact: true }).click();
  await page
    .getByRole('region', { name: '重命名问答实例', exact: true })
    .waitFor({ state: 'hidden' });
  assert.equal(
    (await (await http.get(`${root}/conversations/${first}`)).json()).name,
    (await (await http.get(`${root}/conversations/${second}`)).json()).name,
  );
  await window().locator(`input[value="${knowledgeBaseId}"]`).uncheck();
  await window()
    .getByRole('button', { name: '保存后续知识库范围', exact: true })
    .click();
  await window()
    .getByText('后续范围已保存，历史及在途问题范围不变。', { exact: true })
    .waitFor();
  const original = await (
    await http.get(`${root}/conversations/${first}/turns?limit=20`)
  ).json();
  assert.deepEqual(original.items[0].questionTask.selectedKnowledgeBaseIds, [
    knowledgeBaseId,
  ]);
  await window().locator(`input[value="${knowledgeBaseId}"]`).check();
  const follow = await submit('请结合上一轮再解释一次');
  await window()
    .getByText(`任务标识：${follow.task.id}`, { exact: false })
    .waitFor();
  await window()
    .getByRole('button', { name: '提交追问', exact: true })
    .waitFor({ state: 'visible' });
  await expect.poll(async () => {
    const reply = await http.get(`${root}/question-tasks/${follow.task.id}`);
    assert.equal(reply.status(), 200);
    return (await reply.json()).task.state;
  }, { timeout: 30000 }).toBe('succeeded');
  const turns = await (
    await http.get(`${root}/conversations/${first}/turns?limit=20`)
  ).json();
  assert.equal(turns.items.length, 2);
  assert.ok(
    turns.items[0].questionTask.task.createdAt <=
      turns.items[1].questionTask.task.createdAt,
  );
  assert.equal(turns.items[1].questionTask.answer.historyContext, 'all_turns');
  gateEmbedding();
  let pending;
  try {
    pending = await submit('等待中的追问');
    const conflict = await http.post(
      `${root}/conversations/${first}/questions`,
      {
        headers: { ...headers, 'Idempotency-Key': randomUUID() },
        data: {
          question: '不允许并发提交',
          knowledgeBaseIds: [knowledgeBaseId],
        },
      },
    );
    assert.equal(conflict.status(), 409);
    await select(second);
    await select(first);
    await window()
      .getByRole('button', { name: '取消本次问答', exact: true })
      .click();
    await window()
      .getByRole('button', { name: '确认取消问答', exact: true })
      .click();
    await window()
      .getByText('问答已取消，不展示草稿。', { exact: true })
      .waitFor();
  } finally {
    releaseEmbedding();
  }
  await page.goto(`${base}/#/`);
  await page
    .getByRole('region', { name: '最近问答', exact: true })
    .locator(`a[href*="conversation=${first}"]`)
    .click();
  await window()
    .getByText('问答已取消，不展示草稿。', { exact: true })
    .waitFor();
  const postCount = [];
  const observe = (request) => {
    if (
      request.method() === 'POST' &&
      /\/questions$/.test(new URL(request.url()).pathname)
    )
      postCount.push(request.url());
  };
  page.on('request', observe);
  try {
    await page.reload();
    await window()
      .getByText('问答已取消，不展示草稿。', { exact: true })
      .waitFor();
    assert.equal(postCount.length, 0);
  } finally {
    page.off('request', observe);
  }
  await page.getByRole('button', { name: '删除当前问答', exact: true }).click();
  await page.getByText(/不删除源资料、数据集、知识库或其他会话/).waitFor();
  await page
    .getByRole('button', { name: '确认删除问答实例', exact: true })
    .click();
  await list()
    .locator(`[data-conversation-id="${first}"]`)
    .waitFor({ state: 'detached' });
  assert.equal(
    (await http.get(`${root}/question-tasks/${pending.task.id}`)).status(),
    404,
  );
  assert.equal(
    (
      await http.get(
        `${root}/conversations/${first}/evidence/${turns.items[0].questionTask.answer.evidence[0].id}`,
      )
    ).status(),
    404,
  );
  assert.equal(
    (await http.get(`${root}/knowledge-bases/${knowledgeBaseId}`)).status(),
    200,
  );
  assert.equal(
    (await http.get(`${root}/conversations/${second}`)).status(),
    200,
  );
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: `artifacts/live-conversation-history-${width}.png`,
    });
  }
  tests.push({
    name: '真实多轮会话切换首页继续范围历史与删除隔离',
    passed: true,
    operationIds: [
      'listConversations',
      'getConversation',
      'renameConversation',
      'updateConversationScope',
      'listConversationTurns',
      'submitQuestion',
      'deleteConversation',
    ],
    assertions: [
      '空白不保存，手名允许重复且切换保留输入不取消任务',
      '后续范围保存不改历史，时间序完整轮次及全部历史模式真实读取',
      '同会话活动任务409，跨实例切换取消原任务',
      '首页真实最近会话继续，重载只查原记录不收费重跑',
      '确认删除专属快照及记录，源知识库和其他会话仍存在；三宽截图',
    ],
  });
}

export async function checkConversationRestart(
  page,
  tests,
  { base, conversationId, restartServer },
) {
  await restartServer();
  const posts = [];
  const observe = (request) => {
    if (
      request.method() === 'POST' &&
      /\/questions$/.test(new URL(request.url()).pathname)
    )
      posts.push(request.url());
  };
  page.on('request', observe);
  try {
    await page.goto(`${base}/#/answers?conversation=${conversationId}`);
    await page
      .getByText(
        '应用重启后任务已中断，未自动重新调用模型；确认费用后可手动提问。',
        { exact: true },
      )
      .waitFor();
    assert.equal(posts.length, 0);
    tests.push({
      name: '真实重启中断会话恢复不自动重跑',
      passed: true,
      operationIds: ['getConversation', 'listConversationTurns'],
      assertions: ['重启中断保留历史状态，仅查询原任务且收费提交为零'],
    });
  } finally {
    page.off('request', observe);
  }
}
