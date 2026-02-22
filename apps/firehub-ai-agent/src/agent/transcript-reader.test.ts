import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { readSessionTranscript } from './transcript-reader.js';

// Mock fs/promises
vi.mock('fs/promises', () => ({
  readFile: vi.fn(),
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
});
