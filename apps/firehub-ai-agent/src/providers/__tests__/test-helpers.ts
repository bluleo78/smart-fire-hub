import type { SSEEvent } from '../types.js';

export async function* makeStream(events: SSEEvent[]) {
  for (const e of events) yield e;
}
