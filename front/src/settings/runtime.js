import { exact } from '../system/session';
export const runtimeFields = [
  ['indexConcurrency', '索引并发数', 32],
  ['questionConcurrency', '问答并发数', 32],
  ['searchConcurrency', '检索并发数', 32],
  ['queueCapacity', '各类型等待队列容量', 100000],
  ['maxFileBytes', '单份文件字节上限'],
  ['maxUploadBytes', '上传整请求字节上限'],
  ['maxUploadFiles', '每次上传文件数', 1000],
  ['parseTimeoutSeconds', '解析超时秒数'],
  ['maxExtractedCodePoints', '解析文本码点上限'],
  ['chunkHardLimitCodePoints', '切片硬上限码点数'],
  ['maxRequestCandidates', '请求候选总上限'],
  ['finalEvidenceLimit', '最终证据条数'],
  ['rrfConstant', '融合排序常数'],
  ['requestModelCallBudget', '请求模型调用预算'],
  ['requestEmbeddingCallBudget', '请求嵌入调用预算'],
  ['modelTimeoutSeconds', '模型超时秒数'],
  ['contextEvidenceTokenBudget', '证据词元预算'],
  ['sqliteBusyTimeoutMilliseconds', '数据库忙等待毫秒数'],
  ['logMaxFileBytes', '单份日志字节上限'],
  ['logRetainedFileCount', '日志保留份数'],
  ['historyContextTokenBudget', '近期完整历史词元预算'],
];
const integer = (value, max = Number.MAX_SAFE_INTEGER) =>
  Number.isSafeInteger(value) && value >= 1 && value <= max;
function normalizedEndpoint(value) {
  if (
    typeof value !== 'string' ||
    !/^https?:\/\//.test(value) ||
    /[\s?#]/.test(value)
  )
    return null;
  try {
    const url = new URL(value);
    return url.hostname && !url.username && !url.password
      ? url.href.replace(/\/+$/, '')
      : null;
  } catch {
    return null;
  }
}
export function runtimeErrors(value) {
  const errors = [];
  if (!exact(value, [...runtimeFields.map(([key]) => key), 'modelRateLimits']))
    return [
      {
        field: '',
        message: '运行参数必须包含全部二十二字段，且不能含未知字段。',
      },
    ];
  for (const [key, label, max] of runtimeFields)
    if (
      key === 'rrfConstant'
        ? typeof value[key] !== 'number' ||
          !Number.isFinite(value[key]) ||
          value[key] <= 0
        : !integer(value[key], max)
    )
      errors.push({
        field: key,
        message: `${label}须为${key === 'rrfConstant' ? '大于零的有限数值' : `1 至 ${max ?? Number.MAX_SAFE_INTEGER} 的安全整数`}。`,
      });
  for (const [larger, smaller, message] of [
    ['maxUploadBytes', 'maxFileBytes', '上传整请求上限不能小于单份文件上限。'],
    [
      'maxRequestCandidates',
      'finalEvidenceLimit',
      '最终证据数不能超过请求候选数。',
    ],
    [
      'maxExtractedCodePoints',
      'chunkHardLimitCodePoints',
      '切片硬上限不能超过解析文本上限。',
    ],
  ])
    if (value[larger] < value[smaller])
      errors.push({ field: smaller, message });
  if (
    !Array.isArray(value.modelRateLimits) ||
    value.modelRateLimits.length > 100
  )
    errors.push({
      field: 'modelRateLimits',
      message: '模型限流须为不超过一百项的列表。',
    });
  else {
    const seen = new Set();
    value.modelRateLimits.forEach((item, index) => {
      const path = `modelRateLimits/${index}`,
        endpoint = normalizedEndpoint(item?.endpoint);
      if (
        !exact(item, [
          'endpoint',
          'model',
          'maxConcurrent',
          'requestsPerMinute',
          'tokensPerMinute',
        ]) ||
        !endpoint ||
        typeof item.model !== 'string' ||
        Array.from(item.model).length < 1 ||
        !['maxConcurrent', 'requestsPerMinute', 'tokensPerMinute'].every(
          (key) => integer(item[key]),
        )
      )
        errors.push({
          field: path,
          message: `第 ${index + 1} 项模型限流须含合法基础地址、模型名及三项正安全整数。`,
        });
      else {
        const identity = JSON.stringify([endpoint, item.model]);
        if (seen.has(identity))
          errors.push({
            field: path,
            message: '同一规范化端点与模型只能配置一项限流。',
          });
        seen.add(identity);
      }
    });
  }
  return errors;
}
export function createRuntimeClient(client) {
  const verify = (value) => {
    if (runtimeErrors(value).length)
      throw new Error('运行参数响应不符合约定，请重新读取，不能用默认值代替。');
    return value;
  };
  return {
    async get() {
      return verify(
        await client.request({ method: 'get', url: '/settings/runtime' }),
      );
    },
    async save(value) {
      const errors = runtimeErrors(value);
      if (errors.length) {
        const cause = new Error('运行参数尚有错误，请逐项修正。');
        cause.fieldErrors = errors;
        throw cause;
      }
      return verify(
        await client.request({
          method: 'put',
          url: '/settings/runtime',
          data: value,
        }),
      );
    },
  };
}
