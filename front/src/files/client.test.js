import { afterAll, beforeEach, expect, it } from 'vitest';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { createSystemClient } from '../system/session';
import { createFileClient } from './client';

const id = '00000000-0000-4000-8000-000000000001';
const now = '2026-11-13T00:00:00Z';
const record = {
  id,
  datasetId: id,
  name: '资料.txt',
  createdBy: null,
  createdAt: now,
  lastUploadedAt: now,
  updatedAt: now,
  latestVersion: {
    id,
    fileId: id,
    uploadedName: '资料.txt',
    fileType: 'text',
    mediaType: 'text/plain',
    sizeBytes: 0,
    hashAlgorithm: 'SHA-256',
    contentHash: 'a'.repeat(64),
    uploadedBy: null,
    uploadedAt: now,
    originalAvailable: true,
  },
};
let requests, response, api, headers, failure;
const operations = [
  'listDatasetFiles',
  'uploadFiles',
  'getFile',
  'previewLatestFile',
  'downloadLatestFile',
  'renameFile',
  'listFileVersions',
  'previewVersionFile',
  'downloadVersionFile',
];
const prefixes = [
  '#/paths/~1datasets~1{datasetId}~1files/get',
  '#/paths/~1datasets~1{datasetId}~1files:upload/post',
  '#/paths/~1files~1{fileId}/get',
  '#/paths/~1files~1{fileId}~1latest~1preview/get',
  '#/paths/~1files~1{fileId}~1latest~1download/get',
  '#/paths/~1files~1{fileId}~1name/patch',
  '#/paths/~1files~1{fileId}~1versions/get',
  '#/paths/~1files~1{fileId}~1versions~1{contentVersionId}~1preview/get',
  '#/paths/~1files~1{fileId}~1versions~1{contentVersionId}~1download/get',
];
const evidence = [];
const schema = (
  name,
  properties,
  extras = ['type', 'required', 'additionalProperties'],
) => [
  ...extras.map((part) => `#/components/schemas/${name}/${part}`),
  ...properties.map(
    (part) => `#/components/schemas/${name}/properties/${part}`,
  ),
];
const fileConstraints = [
  ...schema('File', [
    'id/type',
    'id/format',
    'datasetId/type',
    'datasetId/format',
    'name/type',
    'name/minLength',
    'name/maxLength',
    'name/pattern',
    'createdBy/anyOf',
    'createdBy/anyOf/0/type',
    'createdBy/anyOf/1/type',
    ...['createdAt', 'updatedAt', 'lastUploadedAt'].flatMap((key) => [
      `${key}/type`,
      `${key}/format`,
      `${key}/pattern`,
    ]),
  ]),
  ...schema('FileVersion', [
    'id/type',
    'id/format',
    'fileId/type',
    'fileId/format',
    'uploadedName/type',
    'uploadedName/minLength',
    'uploadedName/maxLength',
    'uploadedName/pattern',
    'fileType/type',
    'fileType/enum',
    'mediaType/type',
    'mediaType/enum',
    'sizeBytes/type',
    'sizeBytes/minimum',
    'sizeBytes/maximum',
    'hashAlgorithm/type',
    'hashAlgorithm/const',
    'contentHash/type',
    'contentHash/pattern',
    'uploadedBy/anyOf',
    'uploadedBy/anyOf/0/type',
    'uploadedBy/anyOf/1/type',
    'uploadedAt/type',
    'uploadedAt/format',
    'uploadedAt/pattern',
    'originalAvailable/type',
  ]),
];
const errorConstraints = [
  ...schema('Error', [
    'requestId/type',
    'requestId/format',
    'code/type',
    'code/enum',
    'message/type',
    'fieldErrors/type',
    'retryable/type',
  ]),
  ...schema('FieldError', ['field/type', 'code/type', 'message/type']),
];
function observed(index, name, constraints) {
  evidence.push({
    name: `${operations[index]}：${name}`,
    operationIds: [operations[index]],
    constraintIds: [...new Set(constraints)],
    passed: true,
    requests: requests.map((config) => ({
      method: config.method.toUpperCase(),
      path: config.baseURL + config.url,
      params: config.params,
      headers: Object.keys(config.headers.toJSON()),
      withCredentials: config.withCredentials,
      files:
        config.data instanceof FormData
          ? config.data.getAll('files').map((file) => ({
              name: file.name,
              mediaType: file.type,
              size: file.size,
            }))
          : undefined,
    })),
    assertions: [
      '从实际Axios请求及消费结果断言；仅固定资料，保护头只保留名称，不保存令牌',
    ],
  });
}
beforeEach(async () => {
  failure = null;
  headers = {};
  record.latestVersion.sizeBytes = 0;
  requests = [];
  response = {
    items: [
      {
        index: 0,
        submittedName: '资料.txt',
        outcome: 'created',
        file: record,
        error: null,
        indexTaskIds: [],
      },
    ],
    requestId: id,
  };
  const session = createSystemClient(async (config) => {
    let data;
    if (config.url === '/session')
      data = { sessionId: id, csrfToken: 'x'.repeat(32), expiresAt: now };
    else if (config.url === '/system/status')
      data = {
        applicationVersion: '0.1.0',
        startedAt: now,
        writeState: 'ready',
        dataDirectory: '测试目录',
        schemaVersion: 1,
        warnings: [],
      };
    else {
      requests.push(config);
      if (failure) throw failure;
      data = response;
    }
    return { data, status: 200, config, headers };
  });
  await session.connect();
  api = createFileClient(session);
});
it('重命名实际发送完整名称，不修剪空白或限制扩展名且验证集中元数据', async () => {
  response = { ...record, name: ' 改名.pdf ' };
  expect(await api.rename(id, response.name)).toEqual(response);
  expect(requests[0]).toMatchObject({
    method: 'patch',
    url: `/files/${id}/name`,
    data: JSON.stringify({ name: ' 改名.pdf ' }),
  });
  observed(5, '完整名称与稳定身份集中元数据', [
    ...fileConstraints,
    ...schema('RenameFileInput', [
      'name/type',
      'name/minLength',
      'name/maxLength',
      'name/pattern',
    ]),
    `${prefixes[5]}/requestBody/required`,
    `${prefixes[5]}/requestBody`,
    `${prefixes[5]}/responses/200`,
    ...['method', 'path', 'parameters', 'security'].map(
      (part) => `${prefixes[5]}/${part}`,
    ),
    ...['required', 'schema/type', 'schema/format'].map(
      (part) => `${prefixes[5]}/parameters/0/${part}`,
    ),
    ...['required', 'schema/type', 'schema/minLength'].map(
      (part) => `${prefixes[5]}/parameters/1/${part}`,
    ),
  ]);
});
it('版本分页消费真实游标请求与版本身份，不把当前名当上传名', async () => {
  response = { items: [record.latestVersion], nextCursor: '下一页' };
  expect(await api.versions(id, '上一页')).toEqual(response);
  expect(requests[0]).toMatchObject({
    method: 'get',
    url: `/files/${id}/versions`,
    params: { limit: 20, cursor: '上一页' },
  });
  observed(6, '内容版本全字段与不透明分页游标', [
    ...fileConstraints.filter((part) =>
      part.startsWith('#/components/schemas/FileVersion/'),
    ),
    ...schema('FileVersionPage', [
      'items/type',
      'nextCursor/anyOf',
      'nextCursor/anyOf/0/type',
      'nextCursor/anyOf/1/type',
    ]),
    `${prefixes[6]}/responses/200`,
    ...['method', 'path', 'parameters', 'security'].map(
      (part) => `${prefixes[6]}/${part}`,
    ),
    ...['required', 'schema/type', 'schema/format'].map(
      (part) => `${prefixes[6]}/parameters/0/${part}`,
    ),
    '#/components/parameters/Limit/required',
    ...['type', 'minimum', 'maximum', 'default'].map(
      (part) => `#/components/parameters/Limit/schema/${part}`,
    ),
    '#/components/parameters/Cursor/required',
    ...['type', 'minLength', 'maxLength'].map(
      (part) => `#/components/parameters/Cursor/schema/${part}`,
    ),
  ]);
});
it('重命名名称边界和版本游标在发送前校验，版本响应拒绝跨资料与损坏结构', async () => {
  for (const name of [
    '',
    '.',
    '..',
    'a/b',
    'a\\b',
    '\u0000',
    '\n',
    '\x7f',
    '𠮷'.repeat(256),
    null,
  ])
    await expect(api.rename(id, name)).rejects.toThrow('文件名');
  for (const invalid of ['', 3, '错误']) {
    await expect(api.rename(invalid, '名称')).rejects.toThrow('标识');
    await expect(api.versions(invalid)).rejects.toThrow('标识');
  }
  for (const cursor of ['', 3, '字'.repeat(2049)])
    await expect(api.versions(id, cursor)).rejects.toThrow('分页位置');
  expect(requests).toHaveLength(0);
  for (const value of [
    null,
    { items: {} },
    { items: [], nextCursor: 4 },
    { items: [], nextCursor: null, extra: true },
    { items: [null], nextCursor: null },
    {
      items: [
        {
          ...record.latestVersion,
          fileId: '00000000-0000-4000-8000-000000000002',
        },
      ],
      nextCursor: null,
    },
    {
      items: [{ ...record.latestVersion, originalAvailable: null }],
      nextCursor: null,
    },
  ]) {
    response = value;
    await expect(api.versions(id)).rejects.toThrow('资料响应格式');
  }
  response = { ...record, name: '𠮷'.repeat(255) };
  expect(await api.rename(id, response.name)).toEqual(response);
  expect(requests.at(-1).headers.get('X-CSRF-Token')).toHaveLength(32);
  expect(requests.at(-1).withCredentials).toBe(true);
  response = { items: [], nextCursor: null };
  expect(await api.versions(id)).toEqual(response);
  expect(requests.at(-1).params).toEqual({ limit: 20 });
});
it('二进制请求的JSON错误仍解析副本不可用与会话状态，不成为下载正文', async () => {
  failure = new Error();
  failure.response = {
    data: new TextEncoder().encode(
      JSON.stringify({
        requestId: id,
        code: 'CONTENT_UNAVAILABLE',
        message: '原副本不可用',
        fieldErrors: [],
        retryable: false,
      }),
    ).buffer,
  };
  await expect(api.download(id)).rejects.toMatchObject({
    code: 'CONTENT_UNAVAILABLE',
    message: `原副本不可用 请求标识：${id}`,
  });
});
it('纯文本预览保留原字节并检查完整响应安全头', async () => {
  response = new Uint8Array([239, 187, 191, 60, 62]).buffer;
  headers = {
    'content-type': 'text/plain;charset=UTF-8',
    'content-disposition': "inline; filename*=UTF-8''%E8%B5%84%E6%96%99.txt",
    'x-content-type-options': 'nosniff',
    'cache-control': 'no-store',
    'content-security-policy': "sandbox; default-src 'none'",
  };
  const result = await api.preview(id);
  expect(new Uint8Array(result.bytes)).toEqual(
    new Uint8Array([239, 187, 191, 60, 62]),
  );
  expect(result.mediaType).toBe('text/plain');
  expect(requests[0].responseType).toBe('arraybuffer');
});
it('列表游标与单份详情使用稳定标识并完整验证文件结构', async () => {
  response = { items: [record], nextCursor: '下一页' };
  expect(await api.list(id, '当前页')).toEqual(response);
  expect(requests[0].params).toEqual({ limit: 20, cursor: '当前页' });
  response = record;
  expect(await api.get(id)).toEqual(record);
});
it('合法大写标识与规范化文件响应可一致关联', async () => {
  const canonical = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
  const file = {
    ...record,
    id: canonical,
    datasetId: canonical,
    latestVersion: { ...record.latestVersion, fileId: canonical },
  };
  response = { items: [file], nextCursor: null };
  expect(await api.list(canonical.toUpperCase())).toEqual(response);
  response = file;
  expect(await api.get(canonical.toUpperCase())).toEqual(file);
  response = {
    requestId: id,
    items: [
      {
        index: 0,
        submittedName: '资料.txt',
        outcome: 'created',
        file,
        error: null,
        indexTaskIds: [],
      },
    ],
  };
  expect(
    await api.upload(canonical.toUpperCase(), [new File([], '资料.txt')]),
  ).toEqual(response);
  expect(requests.map((request) => request.url)).toEqual([
    `/datasets/${canonical}/files`,
    `/files/${canonical}`,
    `/datasets/${canonical}/files:upload`,
  ]);
});
it('单份空文本经正式files字段及会话令牌上传并保留集中元数据', async () => {
  const file = new File([], '资料.txt', { type: 'text/plain' });
  expect(await api.upload(id, [file])).toEqual(response);
  expect(requests[0].url).toBe(`/datasets/${id}/files:upload`);
  expect(requests[0].data.getAll('files')).toEqual([file]);
  expect(requests[0].headers.get('X-CSRF-Token')).toBe('x'.repeat(32));
});
it('上传拒绝错误结果关联和不完整元数据，不将200当作成功', async () => {
  response.items[0].file.latestVersion.sizeBytes = -1;
  await expect(api.upload(id, [new File([], '资料.txt')])).rejects.toThrow(
    '资料响应格式',
  );
});
it.each([0, 1, 2])('元数据成功消费与实际路径安全信息%d', async (index) => {
  const file = {
    ...record,
    name: '𠮷'.repeat(255),
    createdBy: '系统账号',
    latestVersion: {
      ...record.latestVersion,
      uploadedName: '𠮷'.repeat(255),
      sizeBytes: Number.MAX_SAFE_INTEGER,
      uploadedBy: '系统账号',
      originalAvailable: false,
    },
  };
  if (index === 0) {
    response = { items: [file], nextCursor: null };
    expect(await api.list(id)).toEqual(response);
  }
  if (index === 1) {
    response = {
      requestId: id,
      items: [
        {
          index: 0,
          submittedName: '资料.txt',
          outcome: 'created',
          file,
          error: null,
          indexTaskIds: [id],
        },
      ],
    };
    expect(await api.upload(id, [new File([], '资料.txt')])).toEqual(response);
  }
  if (index === 2) {
    response = file;
    expect(await api.get(id)).toEqual(file);
  }
  expect(requests[0].withCredentials).toBe(true);
  expect(requests[0].method).toBe(index === 1 ? 'post' : 'get');
  expect(requests[0].url).toBe(
    index === 0
      ? `/datasets/${id}/files`
      : index === 1
        ? `/datasets/${id}/files:upload`
        : `/files/${id}`,
  );
  observed(index, '实际路径与安全信息及集中元数据全字段', [
    ...fileConstraints,
    ...['method', 'path', 'parameters', 'security', 'responses/200'].map(
      (part) => `${prefixes[index]}/${part}`,
    ),
    ...['required', 'schema/type', 'schema/format'].map(
      (part) => `${prefixes[index]}/parameters/0/${part}`,
    ),
    ...(index === 0
      ? schema('FilePage', [
          'items/type',
          'nextCursor/anyOf',
          'nextCursor/anyOf/0/type',
          'nextCursor/anyOf/1/type',
        ])
      : []),
  ]);
});
const malformed = [
  null,
  [],
  { ...record, extra: 1 },
  { ...record, id: 3 },
  { ...record, id: '错误' },
  { ...record, datasetId: '错误' },
  ...[
    '',
    '.',
    '..',
    'a/b',
    'a\\b',
    'a\u0000b',
    'a\nb',
    'a\x7fb',
    '𠮷'.repeat(256),
    4,
  ].map((name) => ({ ...record, name })),
  { ...record, createdBy: 1 },
  { ...record, createdAt: '2026-02-30T00:00:00Z' },
  { ...record, updatedAt: '2026-11-13T00:00:00+00:00' },
  { ...record, lastUploadedAt: null },
  ...[
    { fileId: '错误' },
    { id: '错误' },
    { uploadedName: '..' },
    { fileType: '未知' },
    { mediaType: 'text/html' },
    { mediaType: 'application/pdf' },
    { sizeBytes: -1 },
    { sizeBytes: 0.5 },
    { sizeBytes: Number.MAX_SAFE_INTEGER + 1 },
    { hashAlgorithm: 'MD5' },
    { contentHash: 'A'.repeat(64) },
    { contentHash: 5 },
    { uploadedBy: false },
    { uploadedAt: '错误' },
    { originalAvailable: 1 },
    { extra: 1 },
  ].map((change) => ({
    ...record,
    latestVersion: { ...record.latestVersion, ...change },
  })),
  Object.fromEntries(Object.entries(record).filter(([key]) => key !== 'name')),
];
it.each(malformed.map((value, index) => [index, value]))(
  '列表与详情拒绝损坏结构%d',
  async (_, value) => {
    response = value;
    await expect(api.get(id)).rejects.toThrow('资料响应格式');
    await expect(api.rename(id, '新名称.txt')).rejects.toThrow('资料响应格式');
    response = { items: [value], nextCursor: null };
    await expect(api.list(id)).rejects.toThrow('资料响应格式');
  },
);
it('多files三媒体和四结果部分失败按原位置消费', async () => {
  const files = [
    new File(['文字'], '文字.pdf', { type: 'application/pdf' }),
    new File(['# 标题'], '资料.md', { type: 'text/markdown' }),
    new File(['内容'], '资料.txt', { type: 'text/plain' }),
    new File([], '失败资料.txt', { type: 'text/plain' }),
  ];
  response = {
    requestId: id,
    items: files.map((file, index) => ({
      index,
      submittedName: file.name,
      outcome: ['created', 'replaced', 'unchanged', 'failed'][index],
      file:
        index === 3
          ? null
          : {
              ...record,
              latestVersion: {
                ...record.latestVersion,
                fileType: ['pdf', 'markdown', 'text'][index],
                mediaType: file.type,
              },
            },
      error:
        index === 3
          ? {
              requestId: id,
              code: 'UNSUPPORTED_FILE',
              message: '不支持的内容',
              fieldErrors: [
                {
                  field: '/files/3',
                  code: 'UNSUPPORTED_FILE',
                  message: '内容无效',
                },
              ],
              retryable: false,
            }
          : null,
      indexTaskIds: [],
    })),
  };
  expect(await api.upload(id, files)).toEqual(response);
  expect(requests[0].data.getAll('files')).toEqual(files);
  expect(requests[0].headers.get('X-CSRF-Token')).toBe('x'.repeat(32));
  expect(requests[0].headers.get('Content-Type')).not.toContain('boundary=');
  observed(1, '多文件三媒体四结果及逐项失败结构消费', [
    ...schema('UploadResult', [
      'items/type',
      'items/minItems',
      'requestId/type',
      'requestId/format',
    ]),
    ...schema('UploadItem', [
      'index/type',
      'index/minimum',
      'index/maximum',
      'submittedName/type',
      'submittedName/maxLength',
      'outcome/type',
      'outcome/enum',
      'file/anyOf',
      'file/anyOf/1/type',
      'error/anyOf',
      'error/anyOf/1/type',
      'indexTaskIds/type',
      'indexTaskIds/items/type',
      'indexTaskIds/items/format',
    ]),
    '#/components/schemas/UploadItem/allOf',
    '#/components/schemas/UploadItem/allOf/0/if',
    '#/components/schemas/UploadItem/allOf/0/if/properties/outcome/const',
    '#/components/schemas/UploadItem/allOf/0/then',
    '#/components/schemas/UploadItem/allOf/0/then/properties/file/type',
    '#/components/schemas/UploadItem/allOf/0/else',
    '#/components/schemas/UploadItem/allOf/0/else/properties/error/type',
    ...errorConstraints,
    ...[
      'requestBody',
      'requestBody/required',
      'requestBody/content/multipart~1form-data/schema/type',
      'requestBody/content/multipart~1form-data/schema/required',
      'requestBody/content/multipart~1form-data/schema/additionalProperties',
      'requestBody/content/multipart~1form-data/schema/properties/files/type',
      'requestBody/content/multipart~1form-data/schema/properties/files/minItems',
      'requestBody/content/multipart~1form-data/schema/properties/files/maxItems',
      'requestBody/content/multipart~1form-data/schema/properties/files/items/type',
      'requestBody/content/multipart~1form-data/schema/properties/files/items/format',
      'parameters/1/required',
      'parameters/1/schema/type',
      'parameters/1/schema/minLength',
    ].map((part) => `${prefixes[1]}/${part}`),
  ]);
});
it('批内同名组全组失败而其他项继续，替换与跳过保持稳定身份及失败后仍可读', async () => {
  const file = new File([], '资料.txt', { type: 'text/plain' });
  const repeated = new File([], '资料.TXT', { type: 'text/plain' });
  const other = new File([], '其他.txt', { type: 'text/plain' });
  response = {
    requestId: id,
    items: [file, repeated, other].map((entry, index) => ({
      index,
      submittedName: entry.name,
      outcome: index < 2 ? 'failed' : 'created',
      file:
        index < 2
          ? null
          : {
              ...record,
              name: '其他.txt',
              latestVersion: {
                ...record.latestVersion,
                uploadedName: '其他.txt',
              },
            },
      error:
        index < 2
          ? {
              requestId: id,
              code: 'CONFLICT',
              message: '同批名称冲突',
              fieldErrors: [],
              retryable: false,
            }
          : null,
      indexTaskIds: [],
    })),
  };
  const results = await api.upload(id, [file, repeated, other]);
  expect(results.items.map((item) => item.outcome)).toEqual([
    'failed',
    'failed',
    'created',
  ]);
  for (const outcome of ['replaced', 'unchanged']) {
    response = {
      requestId: id,
      items: [
        {
          index: 0,
          submittedName: '资料.txt',
          outcome,
          file: record,
          error: null,
          indexTaskIds: [],
        },
      ],
    };
    expect((await api.upload(id, [file])).items[0].file.id).toBe(id);
  }
  response = record;
  expect((await api.get(id)).latestVersion.contentHash).toBe('a'.repeat(64));
  observed(1, '完整逐项语义消费及稳定身份读取，原子性与并发须提供方真实验证', [
    `${prefixes[1]}/description`,
  ]);
});
it('正式1000项上限与超长原名4096码点失败回显均完整消费', async () => {
  const files = Array.from(
    { length: 1000 },
    (_, index) => new File([], `${index}.txt`, { type: 'text/plain' }),
  );
  files[0] = new File([], '𠮷'.repeat(4097));
  response = {
    requestId: id,
    items: files.map((file, index) => ({
      index,
      submittedName: Array.from(file.name).slice(0, 4096).join(''),
      outcome: 'failed',
      file: null,
      error: {
        requestId: id,
        code: index === 0 ? 'INVALID_REQUEST' : 'UNSUPPORTED_FILE',
        message: '固定失败',
        fieldErrors: [],
        retryable: false,
      },
      indexTaskIds: [],
    })),
  };
  const result = await api.upload(id, files);
  expect(requests[0].data.getAll('files')).toHaveLength(1000);
  expect(result.items).toHaveLength(1000);
  expect(Array.from(result.items[0].submittedName)).toHaveLength(4096);
});
it('请求标识、输入项数量与分页边界提前拒绝，合法上限与空列表消费', async () => {
  for (const invalid of ['', null, 5, '错误']) {
    await expect(api.list(invalid)).rejects.toThrow('标识');
    await expect(api.upload(invalid, [])).rejects.toThrow('标识');
    await expect(api.get(invalid)).rejects.toThrow('标识');
    await expect(api.preview(invalid)).rejects.toThrow('标识');
    await expect(api.download(invalid)).rejects.toThrow('标识');
  }
  for (const files of [
    null,
    [],
    [null],
    Array(1001).fill(new File([], '资料.txt')),
  ])
    await expect(api.upload(id, files)).rejects.toThrow('1至1000');
  for (const cursor of ['', 3, '字'.repeat(2049)])
    await expect(api.list(id, cursor)).rejects.toThrow('分页位置');
  response = { items: [], nextCursor: null };
  expect(await api.list(id, 'opaque +/游标')).toEqual(response);
  expect(requests[0].params).toEqual({ limit: 20, cursor: 'opaque +/游标' });
  observed(0, '默认分页与不透明游标及边界', [
    '#/components/parameters/Limit/required',
    ...['type', 'minimum', 'maximum', 'default'].map(
      (part) => `#/components/parameters/Limit/schema/${part}`,
    ),
    '#/components/parameters/Cursor/required',
    ...['type', 'minLength', 'maxLength'].map(
      (part) => `#/components/parameters/Cursor/schema/${part}`,
    ),
  ]);
});
it('缺少或危险文件名的下载头不可当作成功响应', async () => {
  response = new Uint8Array([65]).buffer;
  headers = {
    'content-type': 'text/plain;charset=UTF-8',
    'content-disposition': 'attachment;',
    'x-content-type-options': 'nosniff',
    'cache-control': 'no-store',
  };
  await expect(api.download(id)).rejects.toThrow('资料响应格式');
});
it.each([3, 4, 7, 8])('三媒体原字节与完整二进制响应头%d', async (index) => {
  for (const mediaType of ['application/pdf', 'text/markdown', 'text/plain']) {
    response = new Uint8Array([239, 187, 191, 60, 62, 13, 10]).buffer;
    headers = {
      'content-type':
        mediaType + (mediaType.startsWith('text/') ? ';charset=UTF-8' : ''),
      'content-disposition': `${[4, 8].includes(index) ? 'attachment' : 'inline'}; filename*=UTF-8''%E8%B5%84%E6%96%99.txt`,
      'x-content-type-options': 'nosniff',
      'cache-control': 'no-store',
      'content-security-policy': "sandbox; default-src 'none'",
    };
    const result = await (index === 8
      ? api.downloadVersion(id, id)
      : index === 7
        ? api.previewVersion(id, id)
        : index === 3
          ? api.preview(id)
          : api.download(id));
    expect(new Uint8Array(result.bytes)).toEqual(
      new Uint8Array([239, 187, 191, 60, 62, 13, 10]),
    );
    expect(result.mediaType).toBe(mediaType);
    expect(result.filename).toBe('资料.txt');
    expect(requests.at(-1).method).toBe('get');
    expect(requests.at(-1).withCredentials).toBe(true);
    if (index === 7)
      expect(requests.at(-1).url).toBe(`/files/${id}/versions/${id}/preview`);
    if (index === 8)
      expect(requests.at(-1).url).toBe(`/files/${id}/versions/${id}/download`);
  }
  observed(index, '三媒体二进制原字节与安全响应头', [
    ...([7, 8].includes(index)
      ? ['required', 'schema/type', 'schema/format'].map(
          (part) => `${prefixes[index]}/parameters/1/${part}`,
        )
      : []),
    ...[
      'method',
      'path',
      'parameters',
      'security',
      'responses/200',
      'description',
      'parameters/0/required',
      'parameters/0/schema/type',
      'parameters/0/schema/format',
    ].map((part) => `${prefixes[index]}/${part}`),
    ...['application~1pdf', 'text~1markdown', 'text~1plain'].flatMap((type) =>
      ['type', 'format'].map(
        (part) =>
          `${prefixes[index]}/responses/200/content/${type}/schema/${part}`,
      ),
    ),
    ...['Content-Disposition', 'Content-Security-Policy'].map(
      (name) => `${prefixes[index]}/responses/200/headers/${name}/schema/type`,
    ),
    ...['X-Content-Type-Options', 'Cache-Control'].flatMap((name) =>
      ['type', 'const'].map(
        (part) =>
          `${prefixes[index]}/responses/200/headers/${name}/schema/${part}`,
      ),
    ),
  ]);
});
it.each([
  ['content-type', 'text/html'],
  ['content-type', 'text/plain'],
  ['content-type', undefined],
  ['content-disposition', undefined],
  ['content-disposition', 'inline; filename="../资料.txt"'],
  ['content-disposition', "inline; filename*=UTF-8''%ZZ"],
  ['x-content-type-options', ''],
  ['cache-control', 'public'],
  ['content-security-policy', "default-src 'none'"],
  ['content-security-policy', 'sandbox; default-src *'],
])('二进制拒绝不安全响应头%s %s', async (name, value) => {
  response = new ArrayBuffer(0);
  headers = {
    'content-type': 'text/plain;charset=UTF-8',
    'content-disposition': 'inline; filename="资料.txt"',
    'x-content-type-options': 'nosniff',
    'cache-control': 'no-store',
    'content-security-policy': "sandbox; default-src 'none'",
    [name]: value,
  };
  await expect(api.preview(id)).rejects.toThrow('资料响应格式');
});
it('客户端直接拒绝含换行的原始响应头', async () => {
  const client = createFileClient({
    request: async () => ({
      status: 200,
      data: new ArrayBuffer(0),
      headers: {
        'content-type': 'text/plain;charset=UTF-8',
        'content-disposition': 'inline; filename="资料.txt"\r\nX: injected',
        'x-content-type-options': 'nosniff',
        'cache-control': 'no-store',
        'content-security-policy': "sandbox; default-src 'none'",
      },
    }),
  });
  await expect(client.preview(id)).rejects.toThrow('资料响应格式');
});
const invoke = (index) =>
  [
    () => api.list(id),
    () => api.upload(id, [new File([], '资料.txt')]),
    () => api.get(id),
    () => api.preview(id),
    () => api.download(id),
    () => api.rename(id, '资料.txt'),
    () => api.versions(id),
    () => api.previewVersion(id, id),
    () => api.downloadVersion(id, id),
  ][index]();
const statuses = [
  [400, 401, 403, 404, 429, 500, 503],
  [400, 401, 403, 404, 409, 413, 415, 422, 429, 500, 503],
  [400, 401, 403, 404, 429, 500, 503],
  [400, 401, 403, 404, 410, 429, 500, 503],
  [400, 401, 403, 404, 410, 429, 500, 503],
  [400, 401, 403, 404, 409, 422, 429, 500, 503],
  [400, 401, 403, 404, 429, 500, 503],
  [400, 401, 403, 404, 410, 429, 500, 503],
  [400, 401, 403, 404, 410, 429, 500, 503],
];
it.each(
  statuses.flatMap((values, index) => values.map((status) => [index, status])),
)('操作%d消费统一错误%d，不重复提交', async (index, status) => {
  const detail = {
    requestId: id,
    code:
      status === 410
        ? 'CONTENT_UNAVAILABLE'
        : status === 401
          ? 'SESSION_EXPIRED'
          : status === 413
            ? 'RESOURCE_LIMIT'
            : 'INVALID_REQUEST',
    message: '固定失败说明',
    fieldErrors: [
      { field: '/files', code: 'INVALID_REQUEST', message: '资料无效' },
    ],
    retryable: false,
  };
  failure = new Error();
  failure.response = {
    status,
    data: [3, 4, 7, 8].includes(index)
      ? new TextEncoder().encode(JSON.stringify(detail)).buffer
      : detail,
  };
  await expect(invoke(index)).rejects.toMatchObject({
    code: detail.code,
    fieldErrors: detail.fieldErrors,
    message: `固定失败说明 请求标识：${id}`,
  });
  expect(requests).toHaveLength(1);
  observed(index, `统一错误${status}及非空字段反馈`, [
    ...errorConstraints,
    `${prefixes[index]}/responses/${status}`,
  ]);
});
it.each([
  null,
  { items: [], requestId: id },
  { items: [], requestId: 2 },
  { items: [], requestId: '错误' },
  { items: null, requestId: id },
])('拒绝错误上传外层结构%j', async (value) => {
  response = value;
  await expect(api.upload(id, [new File([], '资料.txt')])).rejects.toThrow(
    '资料响应格式',
  );
});
it.each([
  { index: 1 },
  { index: -1 },
  { submittedName: 2 },
  { submittedName: '另一份' },
  { outcome: '未知' },
  { error: {} },
  { file: null },
  { indexTaskIds: null },
  { indexTaskIds: [1] },
  { indexTaskIds: ['错误'] },
  { outcome: 'unchanged', indexTaskIds: [id] },
  { outcome: 'failed', file: null, error: null },
  {
    outcome: 'failed',
    file: null,
    error: {
      requestId: id,
      code: '错误',
      message: '',
      fieldErrors: [],
      retryable: false,
    },
  },
  { extra: 1 },
])('拒绝非法逐项结果%j', async (change) => {
  response.items[0] = { ...response.items[0], ...change };
  await expect(api.upload(id, [new File([], '资料.txt')])).rejects.toThrow(
    '资料响应格式',
  );
});
it.each([
  { items: {}, nextCursor: null },
  { items: [], nextCursor: 2 },
  { items: [], nextCursor: null, extra: 1 },
  { items: [] },
])('分页拒绝非法外层结构%j', async (value) => {
  response = value;
  await expect(api.list(id)).rejects.toThrow('资料响应格式');
});
afterAll(() => {
  mkdirSync('artifacts', { recursive: true });
  writeFileSync(
    'artifacts/contract-consumer-03.json',
    JSON.stringify(
      {
        timestamp: new Date().toISOString(),
        contractSha256: createHash('sha256')
          .update(readFileSync('../docs/agents/api/openapi.yaml'))
          .digest('hex'),
        implementationSha256: execFileSync(
          'python',
          ['../scripts/contract/verify.py', '--snapshot'],
          { encoding: 'utf8' },
        ).trim(),
        scope: operations,
        tests: evidence,
      },
      null,
      2,
    ),
  );
});
