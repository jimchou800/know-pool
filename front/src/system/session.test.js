import { afterAll, beforeEach, expect, it } from 'vitest';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createSystemClient } from './session';

const session = {
  sessionId: '00000000-0000-4000-8000-000000000001',
  csrfToken: '固定测试保护令牌，不是真实凭据，仅供测试使用。'.repeat(2),
  expiresAt: '2026-11-13T00:00:00Z',
};
const status = {
  applicationVersion: '0.1.0',
  startedAt: '2026-11-12T00:00:00Z',
  writeState: 'ready',
  dataDirectory: '测试数据目录',
  schemaVersion: 1,
  warnings: [],
};
const evidence = [];
const schemaConstraints = (schema, fields) => [
  `#/components/schemas/${schema}/type`,
  `#/components/schemas/${schema}/required`,
  `#/components/schemas/${schema}/additionalProperties`,
  ...fields.map(
    (field) => `#/components/schemas/${schema}/properties/${field}`,
  ),
];
const sessionConstraints = schemaConstraints('Session', [
  'sessionId/type',
  'sessionId/format',
  'csrfToken/type',
  'csrfToken/minLength',
  'expiresAt/type',
  'expiresAt/format',
  'expiresAt/pattern',
]);
const statusConstraints = schemaConstraints('SystemStatus', [
  'applicationVersion/type',
  'startedAt/type',
  'startedAt/format',
  'startedAt/pattern',
  'writeState/type',
  'writeState/enum',
  'dataDirectory/type',
  'schemaVersion/type',
  'schemaVersion/minimum',
  'schemaVersion/maximum',
  'warnings/type',
  'warnings/items/type',
]);
let requests;
let response;
let client;
beforeEach(() => {
  requests = [];
  response = { '/session': session, '/system/status': status };
  client = createSystemClient(async (config) => {
    requests.push({
      method: config.method.toUpperCase(),
      path: config.baseURL + config.url,
      headers: Object.keys(config.headers.toJSON()),
      withCredentials: config.withCredentials,
      bodyPresent: config.data !== undefined,
    });
    if (response[config.url] instanceof Error) throw response[config.url];
    return { data: response[config.url], status: 200, config, headers: {} };
  });
});

it('初始化后读取运行状态，实际请求使用同源地址与会话且无请求体', async () => {
  await expect(client.connect()).resolves.toEqual(status);
  expect(requests).toEqual([
    {
      method: 'GET',
      path: '/api/v1/session',
      headers: ['Accept'],
      withCredentials: true,
      bodyPresent: false,
    },
    {
      method: 'GET',
      path: '/api/v1/system/status',
      headers: ['Accept'],
      withCredentials: true,
      bodyPresent: false,
    },
  ]);
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
  evidence.push({
    operationId: ['initializeSession', 'getSystemStatus'],
    caseId: '成功请求与会话仅内存',
    passed: true,
    requests,
    assertions: [
      '真实Axios请求配置',
      '同源路径',
      '携带会话',
      '无请求体',
      '无前端持久化',
    ],
    constraintIds: [
      ...sessionConstraints,
      ...statusConstraints,
      ...['~1session', '~1system~1status'].flatMap((path) =>
        ['method', 'path', 'parameters', 'security', 'responses/200'].map(
          (part) => `#/paths/${path}/get/${part}`,
        ),
      ),
    ],
  });
});

it.each([
  ['未知字段', { ...session, unexpected: true }],
  ['缺失令牌', { sessionId: session.sessionId, expiresAt: session.expiresAt }],
  ['令牌过短', { ...session, csrfToken: '短' }],
  ['令牌码点数不足', { ...session, csrfToken: '😀'.repeat(16) }],
  ['非法标识', { ...session, sessionId: '无效' }],
  ['标识类型错误', { ...session, sessionId: 123 }],
  ['令牌类型错误', { ...session, csrfToken: 123 }],
  ['时间类型错误', { ...session, expiresAt: 123 }],
  ['时间非UTC', { ...session, expiresAt: '2026-11-13T00:00:00+08:00' }],
  ['时间无效', { ...session, expiresAt: '错误Z' }],
  ['日期不存在', { ...session, expiresAt: '2026-02-30T00:00:00Z' }],
  ['空响应', null],
  ['数组响应', []],
])('会话%s时拒绝继续读取状态', async (name, value) => {
  response['/session'] = value;
  await expect(client.connect()).rejects.toThrow(
    '服务响应格式不符合约定，请重试或检查应用版本。',
  );
  expect(requests).toHaveLength(1);
  evidence.push({
    operationId: 'initializeSession',
    caseId: name,
    passed: true,
    requests,
    assertions: ['拒绝无效会话', '不读取状态'],
  });
});

it.each([
  ['未知字段', { ...status, unexpected: true }],
  ['非法状态', { ...status, writeState: 'unknown' }],
  ['缺失版本', { ...status, applicationVersion: undefined }],
  ['整数小于下界', { ...status, schemaVersion: 0 }],
  ['整数超过安全上界', { ...status, schemaVersion: 9007199254740992 }],
  ['非整数', { ...status, schemaVersion: 1.5 }],
  ['警告类型错误', { ...status, warnings: [1] }],
  ['日期无效', { ...status, startedAt: '错误Z' }],
  ['数据目录类型错误', { ...status, dataDirectory: 123 }],
  ['警告不是数组', { ...status, warnings: null }],
  ['空响应', null],
])('运行状态%s时不伪装成功', async (name, value) => {
  response['/system/status'] = value;
  await expect(client.connect()).rejects.toThrow(
    '服务响应格式不符合约定，请重试或检查应用版本。',
  );
  evidence.push({
    operationId: 'getSystemStatus',
    caseId: name,
    passed: true,
    requests,
    assertions: ['拒绝无效状态响应'],
  });
});

it('网络异常不展示原始错误，也不自动重试', async () => {
  response['/session'] = new Error('内部路径和敏感异常');
  await expect(client.connect()).rejects.toThrow(
    '暂时无法连接本机服务，请确认应用已启动后重试。',
  );
  expect(requests).toHaveLength(1);
});

it.each([
  { unexpected: true },
  { code: '未知错误码' },
  { requestId: 123 },
  { message: 123 },
  { fieldErrors: [{ field: '/name', code: 'INVALID_REQUEST', message: 123 }] },
  {
    fieldErrors: [
      {
        field: '/name',
        code: 'INVALID_REQUEST',
        message: '错误',
        unexpected: true,
      },
    ],
  },
  { retryable: 'true' },
])('错误结构不符合契约时不泄漏原始响应 %#', async (mutation) => {
  const error = new Error('原始异常');
  error.response = {
    status: 400,
    data: {
      requestId: session.sessionId,
      code: 'INVALID_REQUEST',
      message: '不应展示的无效结构',
      fieldErrors: [],
      retryable: false,
      ...mutation,
    },
  };
  response['/session'] = error;
  await expect(client.connect()).rejects.toThrow(
    '暂时无法连接本机服务，请确认应用已启动后重试。',
  );
});

it.each([
  ['initializeSession', '/session', 400],
  ['initializeSession', '/session', 403],
  ['initializeSession', '/session', 429],
  ['initializeSession', '/session', 500],
  ['getSystemStatus', '/system/status', 400],
  ['getSystemStatus', '/system/status', 401],
  ['getSystemStatus', '/system/status', 403],
  ['getSystemStatus', '/system/status', 404],
  ['getSystemStatus', '/system/status', 429],
  ['getSystemStatus', '/system/status', 500],
  ['getSystemStatus', '/system/status', 503],
])('%s消费%s的%s统一错误和请求标识', async (operation, path, code) => {
  const error = new Error('原始异常');
  error.response = {
    status: code,
    data: {
      requestId: session.sessionId,
      code: 'SESSION_EXPIRED',
      message: '本机会话已失效，请重新连接。',
      fieldErrors: [
        {
          field: '/测试字段',
          code: 'INVALID_REQUEST',
          message: '测试字段错误',
        },
      ],
      retryable: true,
    },
  };
  response[path] = error;
  await expect(client.connect()).rejects.toThrow(
    `本机会话已失效，请重新连接。 请求标识：${session.sessionId}`,
  );
  evidence.push({
    operationId: operation,
    caseId: `${operation}统一错误${code}`,
    passed: true,
    requests,
    assertions: ['消费完整统一错误', '显示中文说明与请求标识', '不自动重试'],
    constraintIds: [
      `#/paths/${path.replaceAll('/', '~1')}/get/responses/${code}`,
      ...schemaConstraints('Error', [
        'requestId/type',
        'requestId/format',
        'code/type',
        'code/enum',
        'message/type',
        'fieldErrors/type',
        'retryable/type',
      ]),
      ...schemaConstraints('FieldError', [
        'field/type',
        'code/type',
        'message/type',
      ]),
    ],
  });
});

afterAll(() => {
  mkdirSync('artifacts', { recursive: true });
  writeFileSync(
    'artifacts/contract-consumer-01.json',
    JSON.stringify(
      {
        timestamp: new Date().toISOString(),
        implementationSha256: execFileSync(
          'python',
          ['../scripts/contract/verify.py', '--snapshot'],
          { encoding: 'utf8' },
        ).trim(),
        contractSha256: createHash('sha256')
          .update(readFileSync('../docs/agents/api/openapi.yaml'))
          .digest('hex'),
        scope: ['initializeSession', 'getSystemStatus'],
        tests: evidence.map(({ operationId, caseId, ...test }) => ({
          ...test,
          name: `${operationId}：${caseId}`,
          operationIds: Array.isArray(operationId)
            ? operationId
            : [operationId],
          constraintIds: test.constraintIds || [],
        })),
      },
      null,
      2,
    ),
  );
});

it('写入配置携带权威保护头并保留幂等头', async () => {
  const seen = [];
  const guarded = createSystemClient(async (config) => {
    seen.push(config);
    return {
      data: config.url === '/session' ? session : status,
      status: 200,
      headers: {},
      config,
    };
  });
  await guarded.connect();
  await guarded.request({
    method: 'put',
    url: '/settings/vector-store',
    headers: { 'X-CSRF-Token': '伪造', 'Idempotency-Key': session.sessionId },
  });
  expect(seen.at(-1).headers.get('X-CSRF-Token')).toBe(session.csrfToken);
  expect(seen.at(-1).headers.get('Idempotency-Key')).toBe(session.sessionId);
});
