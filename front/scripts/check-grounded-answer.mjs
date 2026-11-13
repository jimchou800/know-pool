import assert from 'node:assert/strict';
import { createServer } from 'node:http';

export async function checkGroundedAnswer(
  page,
  tests,
  {
    http,
    base,
    headers,
    knowledgeBaseId,
    noMatches,
    gateEmbedding,
    releaseEmbedding,
  },
) {
  let mode = 'answered';
  const calls = [];
  const model = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString());
    assert.equal(request.url, '/v1/chat/completions');
    const instruction = body.messages.find(
      (item) => item.role === 'system',
    ).content;
    const context = JSON.parse(
      body.messages.find((item) => item.role === 'user').content,
    );
    let result;
    if (instruction.includes('任务是问题预处理')) {
      calls.push('preprocess');
      result = { additionalQueries: [] };
    } else if (instruction.includes('任务是严格审查候选回答')) {
      calls.push('validation');
      result = { supported: mode !== 'invalid' };
    } else {
      assert.ok(instruction.includes('任务是根据本次原文作答'));
      calls.push('generation');
      const evidence = context.evidence[0];
      assert.ok(evidence.id && evidence.text);
      result = {
        outcome: mode === 'partial' ? 'partial' : 'answered',
        text: `原文记载：${evidence.text}【${evidence.id}】`,
        citationEvidenceIds: [evidence.id],
        uncoveredQuestions: mode === 'partial' ? ['另一个子问题没有依据'] : [],
        contradictions:
          mode === 'partial'
            ? ['固定替身声明不同来源条件不一致，需核对原文']
            : [],
      };
    }
    response.setHeader('Content-Type', 'application/json');
    response.end(
      JSON.stringify({
        id: 'fixed-question',
        object: 'chat.completion',
        model: body.model,
        choices: [
          {
            index: 0,
            message: { role: 'assistant', content: JSON.stringify(result) },
            finish_reason: 'stop',
          },
        ],
        usage: { prompt_tokens: 16, completion_tokens: 16, total_tokens: 32 },
      }),
    );
  });
  await new Promise((resolve) => model.listen(0, '127.0.0.1', resolve));
  const root = `${base}/api/v1`;
  const submissions = [];
  const observe = (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/conversations/first-question'
    )
      submissions.push(request);
  };
  page.on('request', observe);
  const begin = async () => {
    await page.goto(`${base}/#/answers`);
    await page.reload();
    await page.getByRole('button', { name: '新建问答', exact: true }).click();
    await page.getByLabel('问题', { exact: true }).fill('请按原文说明版本规则');
    await page
      .getByLabel('问答名称（可选）', { exact: true })
      .fill('保留的手动问答名');
    await page.locator(`input[value="${knowledgeBaseId}"]`).check();
    await page
      .getByRole('checkbox', {
        name: '我同意本次问答可能调用嵌入、预处理、生成及校验模型并产生费用',
        exact: true,
      })
      .check();
    const accepted = page.waitForResponse(
      (reply) =>
        new URL(reply.url()).pathname ===
          '/api/v1/conversations/first-question' &&
        reply.request().method() === 'POST',
    );
    await page.getByRole('button', { name: '提交首问', exact: true }).click();
    const response = await accepted;
    assert.equal(response.status(), 202);
    return response.json();
  };
  try {
    const saved = await http.put(`${root}/settings/models/generation`, {
      headers,
      data: {
        endpoint: `http://127.0.0.1:${model.address().port}/v1`,
        model: 'fixed-question-model',
        contextWindowTokens: 131072,
        maxOutputTokens: 4096,
        credential: {
          action: 'replace',
          value: 'fixed-local-question-fixture',
        },
      },
    });
    assert.equal(saved.status(), 200);
    const accepted = await begin();
    await page
      .getByRole('heading', { name: '已依据资料回答', exact: true })
      .waitFor();
    assert.equal(accepted.conversation.name, '保留的手动问答名');
    const query = await http.get(
      `${root}/question-tasks/${accepted.questionTask.task.id}`,
    );
    const completed = await query.json();
    assert.equal(completed.task.state, 'succeeded');
    assert.equal(completed.answer.historyContext, 'none');
    assert.equal(completed.answer.omittedHistoryTurnCount, 0);
    const cited = completed.answer.evidence[0];
    await page
      .getByRole('button', { name: '查看引用原文', exact: true })
      .first()
      .click();
    const drawer = page.getByRole('region', { name: '引用原文', exact: true });
    await drawer.waitFor();
    assert.ok((await drawer.innerText()).includes(cited.contentVersionId));
    assert.equal(await drawer.locator('pre').innerText(), cited.text);
    for (const width of [1280, 1440, 1920]) {
      await page.setViewportSize({ width, height: 1000 });
      assert.equal(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        true,
      );
      await page.screenshot({
        path: `artifacts/live-grounded-answer-${width}.png`,
      });
    }
    await page
      .getByRole('button', { name: '关闭引用原文', exact: true })
      .click();
    mode = 'invalid';
    await begin();
    await page
      .getByText('回答未通过引用或事实校验，未展示草稿。', { exact: true })
      .waitFor();
    assert.equal(
      await page
        .getByRole('region', { name: '已校验回答', exact: true })
        .count(),
      0,
    );
    mode = 'partial';
    await begin();
    await page
      .getByRole('heading', { name: '部分问题有依据', exact: true })
      .waitFor();
    await page
      .getByText('缺少依据的问题：另一个子问题没有依据', { exact: true })
      .waitFor();
    await page
      .getByText('资料矛盾：固定替身声明不同来源条件不一致，需核对原文', {
        exact: true,
      })
      .waitFor();
    const before = calls.filter((call) => call === 'generation').length;
    noMatches(true);
    try {
      await begin();
      await page
        .getByRole('heading', { name: '资料中没有足够依据', exact: true })
        .waitFor();
      assert.equal(
        calls.filter((call) => call === 'generation').length,
        before,
      );
    } finally {
      noMatches(false);
    }
    mode = 'answered';
    await page.route('**/api/v1/question-tasks/*/events', (route) =>
      route.abort(),
    );
    const count = submissions.length;
    try {
      await begin();
      await page
        .getByRole('heading', { name: '已依据资料回答', exact: true })
        .waitFor();
      assert.equal(submissions.length, count + 1);
    } finally {
      await page.unroute('**/api/v1/question-tasks/*/events');
    }
    gateEmbedding();
    try {
      await begin();
      await page
        .getByRole('button', { name: '取消本次问答', exact: true })
        .click();
      await page
        .getByRole('button', { name: '确认取消问答', exact: true })
        .click();
      await page
        .getByText('问答已取消，不展示草稿。', { exact: true })
        .waitFor();
      assert.equal(
        await page
          .getByRole('region', { name: '已校验回答', exact: true })
          .count(),
        0,
      );
    } finally {
      releaseEmbedding();
    }
    tests.push({
      name: '真实首问异步校验引用快照缺口无依据断线恢复与取消',
      passed: true,
      operationIds: [
        'submitFirstQuestion',
        'getQuestionTask',
        'cancelQuestionTask',
        'watchQuestionTask',
        'getConversationEvidence',
      ],
      assertions: [
        '本机固定模型替身连接真实任务持久化；首问保留手动名和费用同意',
        '未校验草稿不显示，校验失败与无依据及部分缺口矛盾分别展示，无依据不调用生成',
        '引用抽屉核对实际版本完整切片，三宽截图',
        '事件断线只查询原任务，取消后不展示回答',
      ],
    });
  } finally {
    releaseEmbedding();
    page.off('request', observe);
    await new Promise((resolve) => model.close(resolve));
    await page.goto(`${base}/#/knowledge-bases/${knowledgeBaseId}`);
  }
}
