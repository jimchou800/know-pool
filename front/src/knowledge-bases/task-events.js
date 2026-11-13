import { exact, uuid, date } from '../system/session';

const terminal = ['succeeded', 'failed', 'cancelled', 'interrupted'];
const phases = [
  'queued',
  'initializing',
  'parsing',
  'chunking',
  'embedding',
  'vector_writing',
  'validating',
  'switching',
  'retrieving',
  'generating',
  'answer_validating',
  'copying',
  'verifying',
  'saving_config',
  'cleaning',
  'completed',
];
export async function watchTask(
  taskId,
  { onEvent, signal, lastEventId, fetcher = globalThis.fetch } = {},
) {
  if (!uuid.test(taskId) || typeof taskId !== 'string')
    throw new Error('任务标识无效。');
  let previous = 0;
  if (lastEventId !== undefined) {
    const parts = lastEventId.split(':');
    if (
      parts.length !== 2 ||
      parts[0].toLowerCase() !== taskId.toLowerCase() ||
      !/^[1-9][0-9]*$(?![\s\S])/.test(parts[1]) ||
      !Number.isSafeInteger(Number(parts[1]))
    )
      throw new Error('任务恢复位置无效。');
    previous = Number(parts[1]);
  }
  const response = await fetcher(
    `/api/v1/tasks/${taskId.toLowerCase()}/events`,
    {
      credentials: 'same-origin',
      headers:
        lastEventId === undefined ? {} : { 'Last-Event-ID': lastEventId },
      signal,
    },
  );
  if (
    !response.ok ||
    !/^text\/event-stream(?:;|$)/i.test(
      response.headers.get('content-type') || '',
    ) ||
    !response.body
  )
    throw new Error('任务通知中断，请查询原任务。');
  const reader = response.body.getReader(),
    decoder = new TextDecoder('utf-8', { fatal: true });
  let buffer = '',
    ended = false;
  try {
    while (!ended) {
      const { done, value } = await reader.read();
      buffer += done
        ? decoder.decode()
        : decoder.decode(value, { stream: true });
      buffer = buffer.replace(/\r\n/g, '\n');
      if (buffer.length > 65536)
        throw new Error('任务通知超过资源限制，请查询原任务。');
      let boundary;
      while ((boundary = buffer.indexOf('\n\n')) !== -1) {
        const frame = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        const fields = {};
        for (const line of frame.split('\n')) {
          if (!line || line.startsWith(':')) continue;
          const colon = line.indexOf(':');
          if (colon < 0) throw new Error('任务通知格式不符合约定。');
          const name = line.slice(0, colon),
            content = line.slice(colon + 1).replace(/^ /, '');
          if (
            !['id', 'event', 'data'].includes(name) ||
            Object.hasOwn(fields, name)
          )
            throw new Error('任务通知格式不符合约定。');
          fields[name] = content;
        }
        if (!Object.keys(fields).length) continue;
        const item = JSON.parse(fields.data);
        const isTerminal = terminal.includes(item.state);
        if (
          !exact(item, [
            'taskId',
            'sequence',
            'state',
            'phase',
            'occurredAt',
          ]) ||
          item.taskId !== taskId ||
          !Number.isSafeInteger(item.sequence) ||
          item.sequence < 1 ||
          !['queued', 'running', ...terminal].includes(item.state) ||
          !phases.includes(item.phase) ||
          !date(item.occurredAt) ||
          fields.id !== `${taskId}:${item.sequence}` ||
          fields.event !== (isTerminal ? 'terminal' : 'phase') ||
          (isTerminal && item.phase !== 'completed')
        )
          throw new Error('任务通知格式不符合约定。');
        if (item.sequence > previous) {
          previous = item.sequence;
          await onEvent(item, fields.id);
        }
        if (isTerminal) {
          ended = true;
          break;
        }
      }
      if (done) {
        if (!ended) throw new Error('任务通知中断，请查询原任务。');
        break;
      }
    }
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}
