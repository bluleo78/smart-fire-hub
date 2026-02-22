import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { shouldCompact } from './compaction.js';

// Mock fs/promises
vi.mock('fs/promises', () => ({
  stat: vi.fn(),
}));

import { stat } from 'fs/promises';

describe('shouldCompact', () => {
  let tokenStore: Map<string, number>;

  beforeEach(() => {
    tokenStore = new Map();
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('should return true when stored tokens exceed threshold', async () => {
    tokenStore.set('session-1', 60_000);

    const result = await shouldCompact('session-1', tokenStore, 50_000);
    expect(result).toBe(true);
  });

  it('should return false when stored tokens are within threshold', async () => {
    tokenStore.set('session-1', 30_000);

    const result = await shouldCompact('session-1', tokenStore, 50_000);
    expect(result).toBe(false);
  });

  it('should fall back to file size check when token store has no entry', async () => {
    // File size = 100KB, threshold = 50_000 tokens * 1.45 bytes/token = 72_500 bytes
    // 100KB = 102_400 bytes > 72_500, so should compact
    (stat as ReturnType<typeof vi.fn>).mockResolvedValue({ size: 102_400 });

    const result = await shouldCompact('session-2', tokenStore, 50_000);
    expect(result).toBe(true);
  });
});
