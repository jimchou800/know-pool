import axios from 'axios';

export const uuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const date = (value) =>
  typeof value === 'string' &&
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value) &&
  Number.isFinite(Date.parse(value)) &&
  new Date(value).toISOString().slice(0, 19) === value.slice(0, 19);
export const exact = (value, fields) =>
  value !== null &&
  typeof value === 'object' &&
  !Array.isArray(value) &&
  Object.keys(value).length === fields.length &&
  fields.every((key) => Object.hasOwn(value, key));
const validSession = (value) =>
  exact(value, ['sessionId', 'csrfToken', 'expiresAt']) &&
  typeof value.sessionId === 'string' &&
  uuid.test(value.sessionId) &&
  typeof value.csrfToken === 'string' &&
  Array.from(value.csrfToken).length >= 32 &&
  date(value.expiresAt);
const validStatus = (value) =>
  exact(value, [
    'applicationVersion',
    'startedAt',
    'writeState',
    'dataDirectory',
    'schemaVersion',
    'warnings',
  ]) &&
  typeof value.applicationVersion === 'string' &&
  date(value.startedAt) &&
  ['ready', 'paused', 'migration_failed', 'schema_failed'].includes(
    value.writeState,
  ) &&
  typeof value.dataDirectory === 'string' &&
  Number.isSafeInteger(value.schemaVersion) &&
  value.schemaVersion >= 1 &&
  Array.isArray(value.warnings) &&
  value.warnings.every((warning) => typeof warning === 'string');
export const errorCodes = [
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
];

export function createSystemClient(adapter) {
  const http = axios.create({
    baseURL: '/api/v1',
    withCredentials: true,
    timeout: 15000,
    adapter,
  });
  let csrfToken;
  return {
    async request(config) {
      if (!csrfToken) throw new Error('会话已失效，请重新连接本机服务。');
      try {
        const write = ['post', 'put', 'patch', 'delete'].includes(
          config.method.toLowerCase(),
        );
        const headers = axios.AxiosHeaders.from(config.headers);
        headers.delete('X-CSRF-Token');
        if (write) headers.set('X-CSRF-Token', csrfToken);
        const response = await http.request({
          ...config,
          headers,
        });
        return config.rawResponse ? response : response.data;
      } catch (error) {
        let detail = error.response?.data;
        if (Object.prototype.toString.call(detail) === '[object ArrayBuffer]') {
          try {
            detail = JSON.parse(
              new TextDecoder('utf-8', { fatal: true }).decode(detail),
            );
          } catch {
            detail = null;
          }
        }
        if (
          exact(detail, [
            'requestId',
            'code',
            'message',
            'fieldErrors',
            'retryable',
          ]) &&
          typeof detail.requestId === 'string' &&
          uuid.test(detail.requestId) &&
          errorCodes.includes(detail.code) &&
          typeof detail.message === 'string' &&
          Array.isArray(detail.fieldErrors) &&
          detail.fieldErrors.every(
            (field) =>
              exact(field, ['field', 'code', 'message']) &&
              ['field', 'code', 'message'].every(
                (key) => typeof field[key] === 'string',
              ),
          ) &&
          typeof detail.retryable === 'boolean'
        ) {
          const failure = new Error(
            `${detail.message} 请求标识：${detail.requestId}`,
          );
          failure.fieldErrors = detail.fieldErrors;
          failure.code = detail.code;
          throw failure;
        }
        throw new Error('请求未完成，请重新读取记录确认保存结果后重试。');
      }
    },
    async connect() {
      csrfToken = undefined;
      try {
        const session = (await http.get('/session')).data;
        if (!validSession(session)) throw new TypeError();
        csrfToken = session.csrfToken;
        const status = (await http.get('/system/status')).data;
        if (!validStatus(status)) throw new TypeError();
        return status;
      } catch (error) {
        csrfToken = undefined;
        if (error instanceof TypeError)
          throw new Error('服务响应格式不符合约定，请重试或检查应用版本。');
        const detail = error.response?.data;
        if (
          exact(detail, [
            'requestId',
            'code',
            'message',
            'fieldErrors',
            'retryable',
          ]) &&
          typeof detail.requestId === 'string' &&
          uuid.test(detail.requestId) &&
          errorCodes.includes(detail.code) &&
          typeof detail.message === 'string' &&
          Array.isArray(detail.fieldErrors) &&
          detail.fieldErrors.every(
            (field) =>
              exact(field, ['field', 'code', 'message']) &&
              ['field', 'code', 'message'].every(
                (key) => typeof field[key] === 'string',
              ),
          ) &&
          typeof detail.retryable === 'boolean'
        ) {
          throw new Error(`${detail.message} 请求标识：${detail.requestId}`);
        }
        throw new Error('暂时无法连接本机服务，请确认应用已启动后重试。');
      }
    },
    get csrfToken() {
      return csrfToken;
    },
  };
}
