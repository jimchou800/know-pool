import { afterEach, expect, it } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import Settings from './Settings.vue';
import { createSystemClient } from '../system/session';
const time = '2026-01-01T00:00:00Z';
const id = '00000000-0000-4000-8000-000000000061';
const model = (kind) => ({
  kind,
  versionId: id,
  endpoint: null,
  model: null,
  credential: { configured: false, mask: null },
  contextWindowTokens: null,
  maxOutputTokens: null,
  complete: false,
  updatedAt: time,
});
let wrapper;
afterEach(() => wrapper?.unmount());
it('设置展示三独立配置初值，保存后清理秘密且不自动发起收费测试', async () => {
  const requests = [];
  const client = createSystemClient(async (config) => {
    requests.push(config);
    let data;
    if (config.url === '/session')
      data = {
        sessionId: id,
        csrfToken: '固定保护'.repeat(12),
        expiresAt: time,
      };
    else if (config.url === '/system/status')
      data = {
        applicationVersion: '0.1',
        startedAt: time,
        writeState: 'ready',
        dataDirectory: '测试',
        schemaVersion: 1,
        warnings: [],
      };
    else if (config.url.endsWith('/versions'))
      data = { items: [model('embedding')], nextCursor: null };
    else if (config.url.includes('vector-store'))
      data = {
        endpoint: null,
        credential: { configured: false, mask: null },
        configured: false,
        updatedAt: time,
      };
    else
      data = model(
        config.url.endsWith('generation') ? 'generation' : 'embedding',
      );
    return { data, status: 200, headers: {}, config };
  });
  await client.connect();
  wrapper = mount(Settings, {
    props: { client, writable: true },
    attachTo: document.body,
  });
  await flushPromises();
  expect(wrapper.text()).toContain('生成模型');
  expect(wrapper.text()).toContain('嵌入模型');
  expect(wrapper.text()).toContain('向量连接');
  const form = wrapper.get('[data-kind="generation"]');
  await form.get('[name="endpoint"]').setValue('http://127.0.0.1:4567/v1');
  await form.get('[name="model"]').setValue('测试模型');
  await form.get('[name="contextWindowTokens"]').setValue('1024');
  await form.get('[name="maxOutputTokens"]').setValue('16');
  await form.get('[name="action"]').setValue('replace');
  await form.get('[name="secret"]').setValue('固定测试凭据');
  await form.get('form').trigger('submit');
  await flushPromises();
  expect(form.find('[name="secret"]').exists()).toBe(false);
  expect(form.text()).toContain('已保存');
  expect(requests.some((config) => config.url.endsWith('/test'))).toBe(false);
  expect(localStorage.length + sessionStorage.length).toBe(0);
});

const configured = (kind) => ({
  ...model(kind),
  endpoint: 'http://127.0.0.1:4567/v1',
  model: '固定测试模型',
  credential: { configured: true, mask: '【密钥已设置】' },
  contextWindowTokens: kind === 'generation' ? 1024 : null,
  maxOutputTokens: kind === 'generation' ? 16 : null,
  complete: true,
});
const task = (state) => ({
  id,
  kind: 'model_test',
  state,
  phase: ['queued', 'running'].includes(state)
    ? state === 'queued'
      ? 'queued'
      : 'validating'
    : 'completed',
  createdAt: time,
  startedAt: state === 'queued' ? null : time,
  finishedAt: ['queued', 'running'].includes(state) ? null : time,
  knowledgeBaseId: null,
  fileId: null,
  targetContentVersionId: null,
  targetConfigVersionId: id,
  retryOfTaskId: null,
  attempt: 0,
  progressCompleted: 0,
  progressTotal: null,
  failure: ['failed', 'interrupted'].includes(state)
    ? { code: 'TASK_INTERRUPTED', message: '连接中断', retryable: false }
    : null,
  warnings: [],
  resultResourceId: null,
});
async function page(respond = () => undefined, writable = true) {
  const requests = [];
  const client = createSystemClient(async (config) => {
    if (config.url === '/session')
      return {
        data: {
          sessionId: id,
          csrfToken: '固定保护'.repeat(12),
          expiresAt: time,
        },
        status: 200,
        headers: {},
        config,
      };
    if (config.url === '/system/status')
      return {
        data: {
          applicationVersion: '0.1',
          startedAt: time,
          writeState: 'ready',
          dataDirectory: '测试',
          schemaVersion: 1,
          warnings: [],
        },
        status: 200,
        headers: {},
        config,
      };
    requests.push(config);
    let data = await respond(config);
    if (data === undefined) {
      if (config.url.endsWith('/versions'))
        data = { items: [configured('embedding')], nextCursor: null };
      else if (config.url.endsWith('/connection-test'))
        data = {
          available: true,
          checkedAt: time,
          message: '受控连接成功',
          mayHaveIncurredCost: true,
        };
      else if (config.url.startsWith('/tasks/')) data = task('succeeded');
      else if (config.url.endsWith('/test')) data = { task: task('queued') };
      else if (config.url.includes('/vector-store'))
        data = {
          endpoint: 'http://127.0.0.1:4567',
          credential: { configured: false, mask: null },
          configured: true,
          updatedAt: time,
        };
      else
        data = configured(
          config.url.includes('generation') ? 'generation' : 'embedding',
        );
    }
    return { data, status: 200, headers: {}, config };
  });
  await client.connect();
  wrapper = mount(Settings, {
    props: { client, writable },
    attachTo: document.body,
  });
  await flushPromises();
  return { requests, form: (kind) => wrapper.get(`[data-kind="${kind}"]`) };
}
function button(form, text) {
  return form.findAll('button').find((item) => item.text() === text);
}
function reject(field, message = '请检查配置', code = 'INVALID_REQUEST') {
  const failure = new Error();
  failure.response = {
    status: 400,
    data: {
      requestId: id,
      code,
      message,
      fieldErrors: [{ field, code: 'INVALID_REQUEST', message }],
      retryable: false,
    },
  };
  throw failure;
}
it('跨源保持失败聚焦操作字段，原配置与输入仍保留且不自动外发', async () => {
  const { form, requests } = await page((config) =>
    config.method === 'put'
      ? reject(
          '/credential/action',
          '连接来源已变化，请明确替换或清除密钥后保存。',
        )
      : undefined,
  );
  const generation = form('generation');
  await generation.get('[name="endpoint"]').setValue('http://other.invalid/v1');
  await generation.get('form').trigger('submit');
  await flushPromises();
  expect(document.activeElement).toBe(
    generation.get('[name="action"]').element,
  );
  expect(generation.get('[name="endpoint"]').element.value).toBe(
    'http://other.invalid/v1',
  );
  expect(generation.text()).toContain('连接来源已变化');
  expect(generation.text()).toContain(id);
  expect(requests.filter((c) => c.url.endsWith('/test'))).toHaveLength(0);
});
it('保存失败清空秘密，根级历史错误聚焦新密钥且不泄漏原输入', async () => {
  const { form } = await page((config) =>
    config.method === 'put'
      ? reject(
          config.url.endsWith('/credential') ? '/value' : '/credential/value',
          '密钥不能为显示掩码',
        )
      : undefined,
  );
  await wrapper.get('.embedding-history li button').trigger('click');
  await flushPromises();
  const history = form('history');
  await history.get('[name="action"]').setValue('replace');
  await history.get('[name="secret"]').setValue('固定临时测试秘密');
  await history.get('form').trigger('submit');
  await flushPromises();
  expect(history.get('[name="secret"]').element.value).toBe('');
  expect(document.activeElement).toBe(history.get('[name="secret"]').element);
  expect(wrapper.html()).not.toContain('固定临时测试秘密');
  expect(history.text()).toContain('密钥不能为显示掩码');
});
it('费用同意后只提交一次，以原任务查询结果，保存同版本新凭据撤销绿色结果', async () => {
  const { form, requests } = await page();
  const generation = form('generation');
  expect(
    button(generation, '测试已保存连接').attributes('disabled'),
  ).toBeDefined();
  await generation.get('[type="checkbox"]').setValue(true);
  await button(generation, '测试已保存连接').trigger('click');
  await flushPromises();
  expect(generation.text()).toContain('本次连接可用');
  const posted = requests.filter((config) => config.url.endsWith('/test'));
  expect(posted).toHaveLength(1);
  expect(JSON.parse(posted[0].data)).toEqual({ allowChargedCall: true });
  await button(generation, '查询原任务').trigger('click');
  await flushPromises();
  expect(
    requests.filter((config) => config.url.endsWith('/test')),
  ).toHaveLength(1);
  await generation.get('[name="action"]').setValue('replace');
  await generation.get('[name="secret"]').setValue('固定轮换测试秘密');
  await generation.get('form').trigger('submit');
  await flushPromises();
  expect(generation.text()).not.toContain('本次连接可用');
  expect(generation.text()).toContain('尚未进行新的连接测试');
  expect(generation.get('[type="checkbox"]').element.checked).toBe(false);
});
it('测试接收响应丢失时再次明确点击复用原幂等键，禁止隐式重交', async () => {
  let count = 0;
  const { form, requests } = await page((config) => {
    if (config.url.endsWith('/generation/test') && ++count === 1)
      throw new Error('受控断线');
  });
  const generation = form('generation');
  await generation.get('[type="checkbox"]').setValue(true);
  await button(generation, '测试已保存连接').trigger('click');
  await flushPromises();
  expect(count).toBe(1);
  await button(generation, '测试已保存连接').trigger('click');
  await flushPromises();
  const posts = requests.filter((config) =>
    config.url.endsWith('/generation/test'),
  );
  expect(posts).toHaveLength(2);
  expect(posts[0].headers.get('Idempotency-Key')).toBe(
    posts[1].headers.get('Idempotency-Key'),
  );
  expect(generation.text()).toContain('本次连接可用');
});
it.each(['failed', 'interrupted'])(
  '模型测试%s提示可能费用且不读取不存在结果',
  async (state) => {
    const { form, requests } = await page((config) =>
      config.url.startsWith('/tasks/') ? task(state) : undefined,
    );
    const generation = form('generation');
    await generation.get('[type="checkbox"]').setValue(true);
    await button(generation, '测试已保存连接').trigger('click');
    await flushPromises();
    expect(generation.text()).toContain('连接测试可能已产生费用');
    expect(
      requests.some((config) => config.url.endsWith('/connection-test')),
    ).toBe(false);
    await button(generation, '准备新的测试').trigger('click');
    await flushPromises();
    expect(generation.get('[type="checkbox"]').element.checked).toBe(false);
  },
);
it('排队任务只查询原任务，查询失败保留身份，不生成新费用请求', async () => {
  let read = 0;
  const { form, requests } = await page((config) => {
    if (
      config.url.startsWith('/tasks/') &&
      !config.url.endsWith('/connection-test')
    ) {
      if (++read === 1) return task('running');
      if (read === 2) reject('/taskId', '结果暂不可读', 'INVALID_STATE');
      return task('succeeded');
    }
  });
  const generation = form('generation');
  await generation.get('[type="checkbox"]').setValue(true);
  await button(generation, '测试已保存连接').trigger('click');
  await flushPromises();
  expect(generation.text()).toContain('执行中');
  await button(generation, '查询原任务').trigger('click');
  await flushPromises();
  expect(generation.text()).toContain('结果暂不可读');
  await button(generation, '查询原任务').trigger('click');
  await flushPromises();
  expect(generation.text()).toContain('本次连接可用');
  expect(
    requests.filter((config) => config.url.endsWith('/test')),
  ).toHaveLength(1);
});
it('保存世代隔离迟到结果，未按相同版本标识恢复旧测试', async () => {
  let resolveResult;
  const pending = new Promise((resolve) => (resolveResult = resolve));
  const { form } = await page((config) =>
    config.url.endsWith('/connection-test') ? pending : undefined,
  );
  const generation = form('generation');
  await generation.get('[type="checkbox"]').setValue(true);
  await button(generation, '测试已保存连接').trigger('click');
  await flushPromises();
  await generation.get('form').trigger('submit');
  await flushPromises();
  resolveResult({
    available: true,
    checkedAt: time,
    message: '迟到成功',
    mayHaveIncurredCost: true,
  });
  await flushPromises();
  expect(generation.text()).not.toContain('迟到成功');
  expect(generation.text()).not.toContain('本次连接可用');
});
it('离开时清理秘密并忽略迟到接收，不取消或重新提交后端任务', async () => {
  let resolveAccepted;
  const pending = new Promise((resolve) => (resolveAccepted = resolve));
  const { form, requests } = await page((config) =>
    config.url.endsWith('/generation/test') ? pending : undefined,
  );
  const generation = form('generation');
  await generation.get('[name="action"]').setValue('replace');
  await generation.get('[name="secret"]').setValue('固定离开清理测试秘密');
  await generation.get('[type="checkbox"]').setValue(true);
  await button(generation, '测试已保存连接').trigger('click');
  await flushPromises();
  wrapper.unmount();
  resolveAccepted({ task: task('queued') });
  await flushPromises();
  expect(requests.filter((config) => config.method === 'post')).toHaveLength(1);
  expect(requests.some((config) => config.url.includes('cancel'))).toBe(false);
  expect(localStorage.length + sessionStorage.length).toBe(0);
});
it('历史独立轮换与分页详情按版本身份请求，更新不改变版本', async () => {
  let pageCount = 0;
  const { form, requests } = await page((config) => {
    if (config.url.endsWith('/versions'))
      return {
        items: [
          {
            ...configured('embedding'),
            versionId: pageCount++
              ? '00000000-0000-4000-8000-000000000062'
              : id,
          },
        ],
        nextCursor: pageCount === 1 ? 'opaque +/游标' : null,
      };
  });
  await button(wrapper, '加载更多版本').trigger('click');
  await flushPromises();
  expect(requests.find((config) => config.params?.cursor)?.params).toEqual({
    limit: 20,
    cursor: 'opaque +/游标',
  });
  await wrapper.get('.embedding-history li button').trigger('click');
  await flushPromises();
  const history = form('history');
  await history.get('[name="action"]').setValue('clear');
  await history.get('form').trigger('submit');
  await flushPromises();
  const rotate = requests.find((config) => config.url.endsWith('/credential'));
  expect(JSON.parse(rotate.data)).toEqual({ action: 'clear' });
  expect(rotate.url).toContain(id);
  expect(wrapper.text()).not.toContain('必须重建');
});
it('向量无需模型费用同意且测试无请求体，读取与测试失败可恢复', async () => {
  let fail = true;
  const { form, requests } = await page((config) => {
    if (config.url.endsWith('/vector-store') && fail) {
      fail = false;
      throw new Error('受控读取断线');
    }
    if (config.url.endsWith('/vector-store/test'))
      return {
        task: {
          ...task('queued'),
          kind: 'vector_connection_test',
          targetConfigVersionId: null,
        },
      };
    if (config.url.startsWith('/tasks/'))
      return config.url.endsWith('/connection-test')
        ? {
            available: false,
            checkedAt: time,
            message: '连接不可用',
            mayHaveIncurredCost: false,
          }
        : {
            ...task('succeeded'),
            kind: 'vector_connection_test',
            targetConfigVersionId: null,
          };
  });
  const vector = form('vector');
  expect(vector.text()).toContain('请求未完成');
  await button(vector, '重新读取').trigger('click');
  await flushPromises();
  await button(vector, '测试已保存连接').trigger('click');
  await flushPromises();
  expect(vector.text()).toContain('本次连接不可用');
  expect(
    requests.find((config) => config.url.endsWith('/vector-store/test')).data,
  ).toBeUndefined();
});
it('暂停写入禁用表单与测试，历史空态错误可刷新恢复', async () => {
  let reads = 0;
  const { form } = await page((config) => {
    if (config.url.endsWith('/versions')) {
      if (++reads === 1) throw new Error('受控列表失败');
      return { items: [], nextCursor: null };
    }
  }, false);
  expect(
    form('generation').get('fieldset').attributes('disabled'),
  ).toBeDefined();
  expect(
    button(form('generation'), '测试已保存连接').attributes('disabled'),
  ).toBeDefined();
  expect(wrapper.text()).toContain('当前写入已暂停');
  await button(wrapper, '刷新版本').trigger('click');
  await flushPromises();
  expect(wrapper.text()).toContain('尚无可展示版本');
});
it('历史详情错误与迟到旧选择不会覆盖新选择', async () => {
  const other = '00000000-0000-4000-8000-000000000062';
  let resolveDetail;
  const pending = new Promise((resolve) => (resolveDetail = resolve));
  const { requests } = await page((config) => {
    if (config.url.endsWith('/versions'))
      return {
        items: [
          configured('embedding'),
          { ...configured('embedding'), versionId: other },
        ],
        nextCursor: null,
      };
    if (config.url.endsWith('/versions/' + id)) return pending;
    if (config.url.endsWith('/versions/' + other))
      return { ...configured('embedding'), versionId: other, model: '新选择' };
  });
  await wrapper.findAll('.embedding-history li button')[0].trigger('click');
  await flushPromises();
  await wrapper.findAll('.embedding-history li button')[1].trigger('click');
  await flushPromises();
  resolveDetail(configured('embedding'));
  await flushPromises();
  expect(
    wrapper.get('[data-kind="history"] [name="model"]').element.value,
  ).toBe('新选择');
  expect(
    requests.filter((config) => config.url.includes('/versions/')),
  ).toHaveLength(2);
});

it('历史初始空版本可清除凭据，不要求补全不可编辑端点', async () => {
  const { form, requests } = await page((config) =>
    config.url.endsWith('/versions/' + id) || config.url.endsWith('/credential')
      ? model('embedding')
      : undefined,
  );
  await wrapper.get('.embedding-history li button').trigger('click');
  await flushPromises();
  const history = form('history');
  expect(
    history.get('[name="endpoint"]').attributes('required'),
  ).toBeUndefined();
  await history.get('[name="action"]').setValue('clear');
  await history.get('form').trigger('submit');
  await flushPromises();
  expect(requests.some((config) => config.url.endsWith('/credential'))).toBe(
    true,
  );
});
it('另一配置成功保存也撤销当前旧测试标记，历史当前凭据轮换更新完整状态', async () => {
  const { form } = await page((config) =>
    config.url.endsWith('/credential')
      ? {
          ...configured('embedding'),
          credential: { configured: false, mask: null },
          complete: false,
        }
      : undefined,
  );
  const generation = form('generation');
  await generation.get('[type="checkbox"]').setValue(true);
  await button(generation, '测试已保存连接').trigger('click');
  await flushPromises();
  expect(generation.text()).toContain('本次连接可用');
  await form('vector').get('form').trigger('submit');
  await flushPromises();
  expect(generation.text()).not.toContain('本次连接可用');
  await wrapper.get('.embedding-history li button').trigger('click');
  await flushPromises();
  await form('history').get('[name="action"]').setValue('clear');
  await form('history').get('form').trigger('submit');
  await flushPromises();
  expect(form('embedding').text()).toContain('配置未完整');
  expect(form('embedding').text()).toContain('未设置');
});

it('预算错误与输入通过可访问说明关联，失败后焦点落到具体预算字段', async () => {
  const { form } = await page((config) =>
    config.method === 'put'
      ? reject('/contextWindowTokens', '上下文预算须为安全正整数')
      : undefined,
  );
  const generation = form('generation');
  await generation.get('form').trigger('submit');
  await flushPromises();
  const input = generation.get('[name="contextWindowTokens"]');
  expect(document.activeElement).toBe(input.element);
  expect(input.attributes('aria-invalid')).toBe('true');
  const description = generation.get(
    '#' + input.attributes('aria-describedby'),
  );
  expect(description.text()).toBe('上下文预算须为安全正整数');
});

it('历史轮换先于延迟当前配置读取完成时，不恢复旧凭据状态或开启测试', async () => {
  let resolveCurrent;
  const pending = new Promise((resolve) => (resolveCurrent = resolve));
  const cleared = {
    ...configured('embedding'),
    credential: { configured: false, mask: null },
    complete: false,
  };
  let reads = 0;
  const { form } = await page((config) =>
    config.url === '/settings/models/embedding' && config.method === 'get'
      ? ++reads === 1
        ? pending
        : cleared
      : config.url.endsWith('/credential')
        ? cleared
        : undefined,
  );
  await wrapper.get('.embedding-history li button').trigger('click');
  await flushPromises();
  await form('history').get('[name="action"]').setValue('clear');
  await form('history').get('form').trigger('submit');
  await flushPromises();
  resolveCurrent(configured('embedding'));
  await flushPromises();
  expect(form('embedding').text()).toContain('配置未完整');
  expect(form('embedding').text()).toContain('密钥状态：未设置');
  expect(
    button(form('embedding'), '测试已保存连接').attributes('disabled'),
  ).toBeDefined();
});
