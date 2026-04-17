import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { readSessionTranscript } from './transcript-reader.js';

// Mock fs/promises — readdir/access도 포함해야 findTranscriptFilePath가 동작함
vi.mock('fs/promises', () => ({
  readFile: vi.fn(),
  readdir: vi.fn().mockResolvedValue(['fake-project']),
  access: vi.fn().mockResolvedValue(undefined),
}));

import { readFile } from 'fs/promises';

describe('readSessionTranscript', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('should parse valid JSONL with user and assistant messages', async () => {
    const jsonl = [
      JSON.stringify({
        type: 'user',
        uuid: 'u1',
        timestamp: '2024-01-01T00:00:00Z',
        message: { content: [{ type: 'text', text: 'Hello' }] },
      }),
      JSON.stringify({
        type: 'assistant',
        uuid: 'a1',
        timestamp: '2024-01-01T00:00:01Z',
        message: { id: 'msg-1', content: [{ type: 'text', text: 'Hi there!' }] },
      }),
    ].join('\n');

    (readFile as ReturnType<typeof vi.fn>).mockResolvedValue(jsonl);

    const messages = await readSessionTranscript('test-session');
    expect(messages).toHaveLength(2);
    expect(messages[0].role).toBe('user');
    expect(messages[0].content).toBe('Hello');
    expect(messages[1].role).toBe('assistant');
    expect(messages[1].content).toBe('Hi there!');
  });

  it('should return empty array for empty file', async () => {
    (readFile as ReturnType<typeof vi.fn>).mockResolvedValue('');

    const messages = await readSessionTranscript('empty-session');
    expect(messages).toEqual([]);
  });

  it('should return empty array when file does not exist', async () => {
    (readFile as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('ENOENT'));

    const messages = await readSessionTranscript('nonexistent-session');
    expect(messages).toEqual([]);
  });

  it('should filter out user messages containing tool_result blocks', async () => {
    const jsonl = [
      JSON.stringify({
        type: 'user',
        uuid: 'u1',
        timestamp: '2024-01-01T00:00:00Z',
        message: { content: [{ type: 'text', text: 'Run this' }] },
      }),
      JSON.stringify({
        type: 'user',
        uuid: 'u2',
        timestamp: '2024-01-01T00:00:02Z',
        message: { content: [{ type: 'tool_result', tool_use_id: 'tu1', content: 'result data' }] },
      }),
      JSON.stringify({
        type: 'assistant',
        uuid: 'a1',
        timestamp: '2024-01-01T00:00:03Z',
        message: { id: 'msg-2', content: [{ type: 'text', text: 'Done' }] },
      }),
    ].join('\n');

    (readFile as ReturnType<typeof vi.fn>).mockResolvedValue(jsonl);

    const messages = await readSessionTranscript('filter-session');
    // tool_result user message should be excluded
    expect(messages).toHaveLength(2);
    expect(messages[0].role).toBe('user');
    expect(messages[0].content).toBe('Run this');
    expect(messages[1].role).toBe('assistant');
    expect(messages[1].content).toBe('Done');
  });

  it('should extract tool_use blocks as toolCalls from assistant messages', async () => {
    const jsonl = [
      JSON.stringify({
        type: 'user',
        uuid: 'u1',
        timestamp: '2024-01-01T00:00:00Z',
        message: { content: [{ type: 'text', text: '차트 보여줘' }] },
      }),
      JSON.stringify({
        type: 'assistant',
        uuid: 'a1',
        timestamp: '2024-01-01T00:00:01Z',
        message: {
          id: 'msg-1',
          content: [
            { type: 'text', text: '차트를 생성하겠습니다.' },
            {
              type: 'tool_use',
              id: 'tu-1',
              name: 'mcp__firehub__show_chart',
              input: { sql: 'SELECT * FROM t', chartType: 'BAR', config: { xAxis: 'name', yAxis: ['count'] }, columns: ['name', 'count'], rows: [{ name: 'a', count: 1 }] },
            },
          ],
        },
      }),
      // tool_result from user (SDK sends tool results as user messages)
      JSON.stringify({
        type: 'user',
        uuid: 'u2',
        timestamp: '2024-01-01T00:00:02Z',
        message: { content: [{ type: 'tool_result', tool_use_id: 'tu-1', content: '{"displayed":true}' }] },
      }),
      JSON.stringify({
        type: 'assistant',
        uuid: 'a2',
        timestamp: '2024-01-01T00:00:03Z',
        message: { id: 'msg-2', content: [{ type: 'text', text: '차트를 표시했습니다.' }] },
      }),
    ].join('\n');

    (readFile as ReturnType<typeof vi.fn>).mockResolvedValue(jsonl);

    const messages = await readSessionTranscript('chart-session');
    expect(messages).toHaveLength(3);

    // User message
    expect(messages[0].role).toBe('user');
    expect(messages[0].content).toBe('차트 보여줘');

    // Assistant message with toolCalls
    expect(messages[1].role).toBe('assistant');
    expect(messages[1].content).toBe('차트를 생성하겠습니다.');
    expect(messages[1].toolCalls).toHaveLength(1);
    expect(messages[1].toolCalls![0].name).toBe('mcp__firehub__show_chart');
    expect(messages[1].toolCalls![0].input.chartType).toBe('BAR');
    expect(messages[1].toolCalls![0].result).toBe('{"displayed":true}');

    // Final assistant message
    expect(messages[2].role).toBe('assistant');
    expect(messages[2].content).toBe('차트를 표시했습니다.');
    expect(messages[2].toolCalls).toBeUndefined();
  });

  it('should include assistant messages with only toolCalls and no text', async () => {
    const jsonl = [
      JSON.stringify({
        type: 'user',
        uuid: 'u1',
        timestamp: '2024-01-01T00:00:00Z',
        message: { content: [{ type: 'text', text: '데이터 조회해줘' }] },
      }),
      JSON.stringify({
        type: 'assistant',
        uuid: 'a1',
        timestamp: '2024-01-01T00:00:01Z',
        message: {
          id: 'msg-1',
          content: [
            { type: 'tool_use', id: 'tu-1', name: 'mcp__firehub__list_datasets', input: {} },
          ],
        },
      }),
      JSON.stringify({
        type: 'user',
        uuid: 'u2',
        timestamp: '2024-01-01T00:00:02Z',
        message: { content: [{ type: 'tool_result', tool_use_id: 'tu-1', content: [{ type: 'text', text: '[]' }] }] },
      }),
      JSON.stringify({
        type: 'assistant',
        uuid: 'a2',
        timestamp: '2024-01-01T00:00:03Z',
        message: { id: 'msg-2', content: [{ type: 'text', text: '데이터셋이 없습니다.' }] },
      }),
    ].join('\n');

    (readFile as ReturnType<typeof vi.fn>).mockResolvedValue(jsonl);

    const messages = await readSessionTranscript('tool-only-session');
    expect(messages).toHaveLength(3);

    // Assistant message with only toolCalls (no text)
    expect(messages[1].role).toBe('assistant');
    expect(messages[1].content).toBe('');
    expect(messages[1].toolCalls).toHaveLength(1);
    expect(messages[1].toolCalls![0].name).toBe('mcp__firehub__list_datasets');
    // tool_result with array content should be joined
    expect(messages[1].toolCalls![0].result).toBe('[]');
  });
});
