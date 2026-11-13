import { expect, it, vi } from 'vitest';
import { watchTask } from './task-events';
import { otherId, time } from '../../test-fixtures/knowledge-bases';

it('事件流携带同源凭据和恢复头，拆帧注释不改结果，终态后结束', async () => {
  const event = {
    taskId: otherId,
    sequence: 2,
    state: 'succeeded',
    phase: 'completed',
    occurredAt: time,
  };
  const body = `:心跳\n\nid: ${otherId}:2\nevent: terminal\ndata: ${JSON.stringify(event)}\n\n`;
  const fetcher = vi.fn(
    async () =>
      new Response(
        new ReadableStream({
          start(controller) {
            for (const part of [body.slice(0, 10), body.slice(10)])
              controller.enqueue(new TextEncoder().encode(part));
            controller.close();
          },
        }),
        { headers: { 'Content-Type': 'text/event-stream' } },
      ),
  );
  const receive = vi.fn();
  await watchTask(otherId, {
    fetcher,
    lastEventId: `${otherId}:1`,
    onEvent: receive,
  });
  expect(fetcher.mock.calls[0][0]).toBe(`/api/v1/tasks/${otherId}/events`);
  expect(fetcher.mock.calls[0][1]).toMatchObject({
    credentials: 'same-origin',
    headers: { 'Last-Event-ID': `${otherId}:1` },
  });
  expect(receive).toHaveBeenCalledWith(event, `${otherId}:2`);
});

const frameResponse = (frame) =>
  new Response(frame, { headers: { 'Content-Type': 'text/event-stream' } });
const terminal = {
  taskId: otherId,
  sequence: 2,
  state: 'succeeded',
  phase: 'completed',
  occurredAt: time,
};
const frame = (
  item = terminal,
  name = 'terminal',
  id = `${otherId}:${item.sequence}`,
) => `id: ${id}\nevent: ${name}\ndata: ${JSON.stringify(item)}\n\n`;
it('重复和旧阶段不倒退结果，相同序号终态重发关闭且不重复通知', async () => {
  const receive = vi.fn();
  await watchTask(otherId, {
    lastEventId: `${otherId}:2`,
    onEvent: receive,
    fetcher: async () => frameResponse(frame()),
  });
  expect(receive).not.toHaveBeenCalled();
  const phase = {
    ...terminal,
    sequence: 1,
    state: 'running',
    phase: 'embedding',
  };
  await watchTask(otherId, {
    onEvent: receive,
    fetcher: async () =>
      frameResponse(frame(phase, 'phase') + frame(phase, 'phase') + frame()),
  });
  expect(receive.mock.calls.map(([item]) => item.sequence)).toEqual([1, 2]);
});

it.each([
  ['', '空游标'],
  [`${otherId}:0`, '零序号'],
  [`${otherId}:01`, '前导零'],
  [`${otherId}:9007199254740992`, '超出安全整数'],
  [`${otherId}:1\n`, '尾换行'],
  [`${otherId}:1\r\n`, '尾回车换行'],
  ['00000000-0000-4000-8000-000000000001:1', '别的任务'],
])('恢复头%s在网络前拒绝：%s', async (lastEventId) => {
  const fetcher = vi.fn();
  await expect(watchTask(otherId, { lastEventId, fetcher })).rejects.toThrow(
    '恢复位置',
  );
  expect(fetcher).not.toHaveBeenCalled();
});

it.each([
  [frame({ ...terminal, extra: true }), '未知字段'],
  [frame({ ...terminal, sequence: 0 }), '零序号'],
  [frame({ ...terminal, sequence: Number.MAX_SAFE_INTEGER + 1 }), '不安全序号'],
  [frame({ ...terminal, taskId: '错误' }), '错误任务'],
  [frame({ ...terminal, phase: 'embedding' }), '错误终态阶段'],
  [frame({ ...terminal, state: '错误' }), '错误状态'],
  [frame({ ...terminal, occurredAt: '错误' }), '错误时间'],
  [frame(terminal, 'phase'), '事件状态不匹配'],
  [frame(terminal, 'terminal', `${otherId}:1`), '帧标识不匹配'],
  ['id: 1\nevent: terminal\ndata: {}\n\n', '缺失字段'],
  ['data: {}\ndata: {}\n\n', '重复字段'],
  ['event\n\n', '无分隔符'],
  ['unknown: 字段\n\n', '未知帧字段'],
])('非法通知拒绝且不展示结果：%s（%s）', async (body) => {
  const receive = vi.fn();
  await expect(
    watchTask(otherId, {
      onEvent: receive,
      fetcher: async () => frameResponse(body),
    }),
  ).rejects.toThrow();
  expect(receive).not.toHaveBeenCalled();
});

it('空流、错误媒体和网络错误只报告中断，不能伪造完成', async () => {
  for (const response of [
    new Response('', { status: 403 }),
    new Response('', { headers: { 'Content-Type': 'application/json' } }),
    frameResponse(''),
    frameResponse('长'.repeat(65537)),
  ]) {
    await expect(
      watchTask(otherId, { onEvent: vi.fn(), fetcher: async () => response }),
    ).rejects.toThrow();
  }
  await expect(watchTask('错误')).rejects.toThrow('标识');
});
