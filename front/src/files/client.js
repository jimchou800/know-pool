import { date, exact, uuid } from '../system/session';

const text = (value, min, max) =>
  typeof value === 'string' &&
  Array.from(value).length >= min &&
  Array.from(value).length <= max;
const identifier = (value) => typeof value === 'string' && uuid.test(value);
const nullableText = (value) => value === null || typeof value === 'string';
const count = (value) => Number.isSafeInteger(value) && value >= 0;
const validName = (value) =>
  text(value, 1, 255) &&
  value !== '.' &&
  value !== '..' &&
  Array.from(value).every(
    (character) =>
      character !== '/' &&
      character !== '\\' &&
      character.codePointAt(0) >= 32 &&
      character.codePointAt(0) !== 127,
  );
const media = {
  text: 'text/plain',
  markdown: 'text/markdown',
  pdf: 'application/pdf',
};
export const validFile = (value) => {
  if (
    !exact(value, [
      'id',
      'datasetId',
      'name',
      'createdBy',
      'createdAt',
      'lastUploadedAt',
      'updatedAt',
      'latestVersion',
    ])
  )
    return false;
  const version = value.latestVersion;
  return (
    identifier(value.id) &&
    identifier(value.datasetId) &&
    validName(value.name) &&
    nullableText(value.createdBy) &&
    date(value.createdAt) &&
    date(value.lastUploadedAt) &&
    date(value.updatedAt) &&
    exact(version, [
      'id',
      'fileId',
      'uploadedName',
      'fileType',
      'mediaType',
      'sizeBytes',
      'hashAlgorithm',
      'contentHash',
      'uploadedBy',
      'uploadedAt',
      'originalAvailable',
    ]) &&
    identifier(version.id) &&
    identifier(version.fileId) &&
    version.fileId.toLowerCase() === value.id.toLowerCase() &&
    validName(version.uploadedName) &&
    Object.hasOwn(media, version.fileType) &&
    media[version.fileType] === version.mediaType &&
    count(version.sizeBytes) &&
    version.hashAlgorithm === 'SHA-256' &&
    typeof version.contentHash === 'string' &&
    /^[a-f0-9]{64}$/.test(version.contentHash) &&
    nullableText(version.uploadedBy) &&
    date(version.uploadedAt) &&
    typeof version.originalAvailable === 'boolean'
  );
};
const validError = (value) =>
  exact(value, ['requestId', 'code', 'message', 'fieldErrors', 'retryable']) &&
  identifier(value.requestId) &&
  [
    'INVALID_REQUEST',
    'SESSION_EXPIRED',
    'CSRF_REJECTED',
    'ORIGIN_REJECTED',
    'NOT_FOUND',
    'NAME_EXISTS',
    'CONFLICT',
    'INVALID_STATE',
    'NO_EFFECTIVE_INDEX',
    'SCOPE_UNAVAILABLE',
    'DEPENDENCY_UNAVAILABLE',
    'QUEUE_FULL',
    'WRITES_PAUSED',
    'RESOURCE_LIMIT',
    'UNSUPPORTED_FILE',
    'INTERNAL_ERROR',
    'CONTENT_UNAVAILABLE',
    'RESULT_EXPIRED',
  ].includes(value.code) &&
  typeof value.message === 'string' &&
  Array.isArray(value.fieldErrors) &&
  value.fieldErrors.every(
    (field) =>
      exact(field, ['field', 'code', 'message']) &&
      ['field', 'code', 'message'].every(
        (key) => typeof field[key] === 'string',
      ),
  ) &&
  typeof value.retryable === 'boolean';
function verify(value, valid) {
  if (!valid) throw new Error('资料响应格式不符合约定，请重试或检查应用版本。');
  return value;
}
function datasetPath(id) {
  if (!identifier(id)) throw new Error('数据集标识无效，请重新选择。');
  return `/datasets/${id.toLowerCase()}`;
}
function filePath(id) {
  if (!identifier(id)) throw new Error('资料标识无效，请重新选择。');
  return `/files/${id.toLowerCase()}`;
}
export function createFileClient(session) {
  async function binary(id, kind) {
    const response = await session.request({
      method: 'get',
      url: `${filePath(id)}/latest/${kind}`,
      responseType: 'arraybuffer',
      rawResponse: true,
    });
    const header = (name) =>
      response.headers.get
        ? response.headers.get(name)
        : response.headers[name];
    const contentType = header('content-type') || '';
    const mediaType = contentType.split(';')[0].trim().toLowerCase();
    const disposition = header('content-disposition') || '';
    const encoded = /(?:^|;)\s*filename\*=UTF-8''([^;]+)/i.exec(disposition);
    const plain = /(?:^|;)\s*filename="([^"\r\n]+)"/i.exec(disposition);
    let filename;
    try {
      filename = encoded ? decodeURIComponent(encoded[1]) : plain?.[1];
    } catch {
      filename = undefined;
    }
    verify(response, validName(filename));
    const policy = header('content-security-policy') || '';
    verify(
      response,
      response.status === 200 &&
        Object.prototype.toString.call(response.data) ===
          '[object ArrayBuffer]' &&
        Object.values(media).includes(mediaType) &&
        (mediaType === 'application/pdf' ||
          /;\s*charset\s*=\s*utf-8\s*$/i.test(contentType)) &&
        disposition.startsWith(
          kind === 'preview' ? 'inline;' : 'attachment;',
        ) &&
        !/[\r\n]/.test(disposition) &&
        header('x-content-type-options') === 'nosniff' &&
        header('cache-control') === 'no-store' &&
        (kind !== 'preview' ||
          (/(?:^|;)\s*sandbox\s*(?:;|$)/.test(policy) &&
            /(?:^|;)\s*default-src\s+'none'\s*(?:;|$)/.test(policy))),
    );
    return { bytes: response.data, mediaType };
  }
  return {
    preview: (id) => binary(id, 'preview'),
    download: (id) => binary(id, 'download'),
    async list(datasetId, cursor) {
      const path = datasetPath(datasetId);
      if (cursor !== undefined && !text(cursor, 1, 2048))
        throw new Error('分页位置无效，请返回第一页。');
      const value = await session.request({
        method: 'get',
        url: `${path}/files`,
        params: { limit: 20, ...(cursor === undefined ? {} : { cursor }) },
      });
      return verify(
        value,
        exact(value, ['items', 'nextCursor']) &&
          Array.isArray(value.items) &&
          value.items.every(
            (item) =>
              validFile(item) &&
              item.datasetId.toLowerCase() === datasetId.toLowerCase(),
          ) &&
          (value.nextCursor === null || typeof value.nextCursor === 'string'),
      );
    },
    async get(id) {
      const value = await session.request({ method: 'get', url: filePath(id) });
      return verify(
        value,
        validFile(value) && value.id.toLowerCase() === id.toLowerCase(),
      );
    },
    async upload(datasetId, files) {
      const path = datasetPath(datasetId);
      if (
        !Array.isArray(files) ||
        files.length < 1 ||
        files.length > 1000 ||
        files.some((file) => !(file instanceof File))
      )
        throw new Error('请选择1至1000份文件。');
      const data = new FormData();
      for (const file of files) data.append('files', file);
      const value = await session.request({
        method: 'post',
        url: `${path}/files:upload`,
        data,
      });
      return verify(
        value,
        exact(value, ['items', 'requestId']) &&
          identifier(value.requestId) &&
          Array.isArray(value.items) &&
          value.items.length === files.length &&
          value.items.every(
            (item, index) =>
              exact(item, [
                'index',
                'submittedName',
                'outcome',
                'file',
                'error',
                'indexTaskIds',
              ]) &&
              item.index === index &&
              item.submittedName ===
                Array.from(files[index].name).slice(0, 4096).join('') &&
              ['created', 'replaced', 'unchanged', 'failed'].includes(
                item.outcome,
              ) &&
              Array.isArray(item.indexTaskIds) &&
              item.indexTaskIds.every(identifier) &&
              (item.outcome !== 'unchanged' ||
                item.indexTaskIds.length === 0) &&
              (item.outcome === 'failed'
                ? item.file === null && validError(item.error)
                : item.error === null &&
                  validFile(item.file) &&
                  item.file.datasetId.toLowerCase() ===
                    datasetId.toLowerCase()),
          ),
      );
    },
  };
}
