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
