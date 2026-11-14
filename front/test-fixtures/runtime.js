import { runtimeFields } from '../src/settings/runtime';

export const fixedRuntime = {
  ...Object.fromEntries(runtimeFields.map(([key]) => [key, 1])),
  rrfConstant: 60,
  maxFileBytes: 1024,
  maxUploadBytes: 2048,
  maxExtractedCodePoints: 4000,
  chunkHardLimitCodePoints: 2000,
  modelRateLimits: [
    {
      endpoint: 'http://127.0.0.1:4567/v1',
      model: '固定测试模型',
      maxConcurrent: 1,
      requestsPerMinute: 60,
      tokensPerMinute: 10000,
    },
  ],
};
