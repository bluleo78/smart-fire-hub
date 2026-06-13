import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createFireHubMcpServer } from '../firehub-mcp-server.js';
import { FireHubApiClient } from '../api-client.js';
import { clampDocumentHits } from './document-tools.js';
import { estimateTokens } from './analytics-tools.js';
import type { DocumentSearchHit } from '../api-client.js';

function createMockClient(): FireHubApiClient {
  const client = Object.create(FireHubApiClient.prototype);
  const methodNames = Object.getOwnPropertyNames(FireHubApiClient.prototype).filter(
    (name) => name !== 'constructor',
  );
  for (const name of methodNames) {
    client[name] = vi.fn().mockResolvedValue({ mocked: true });
  }
  return client as FireHubApiClient;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function invokeTool(server: any, toolName: string, args: Record<string, unknown> = {}) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const instance = server.instance as any;
  const entry = instance._registeredTools[toolName];
  if (!entry) throw new Error(`Tool ${toolName} not found in registered tools`);
  return entry.handler(args, {});
}

describe('Document MCP Tools', () => {
  let client: FireHubApiClient;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let server: any;

  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation(() => {});
    client = createMockClient();
    server = createFireHubMcpServer(client);
  });

  // --- search_documents ---
  it('search_documents calls apiClient.searchDocuments with query, datasetIds, topK', async () => {
    const mockHits: DocumentSearchHit[] = [
      {
        chunkId: 1,
        documentFileId: 10,
        datasetId: 3,
        fileName: '소방안전매뉴얼.pdf',
        chunkIndex: 0,
        content: '화재 발생 시 행동요령...',
        score: 0.92,
      },
    ];
    (client.searchDocuments as ReturnType<typeof vi.fn>).mockResolvedValue(mockHits);

    const result = await invokeTool(server, 'search_documents', {
      query: '질의',
      datasetIds: [3],
      topK: 5,
    });

    expect(client.searchDocuments).toHaveBeenCalledWith('질의', [3], 5);
    expect(result.content[0].type).toBe('text');
    expect(JSON.parse(result.content[0].text)).toEqual(mockHits);
    expect(result.isError).toBeUndefined();
  });

  it('search_documents works without datasetIds and topK', async () => {
    (client.searchDocuments as ReturnType<typeof vi.fn>).mockResolvedValue([]);

    await invokeTool(server, 'search_documents', { query: '안전' });

    expect(client.searchDocuments).toHaveBeenCalledWith('안전', undefined, undefined);
  });

  it('search_documents returns isError on failure', async () => {
    (client.searchDocuments as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error('embedding service unavailable'),
    );

    const result = await invokeTool(server, 'search_documents', { query: 'x' });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain('embedding service unavailable');
  });

  // --- 응답 크기 가드 (clampDocumentHits) ---
  describe('clampDocumentHits', () => {
    it('passes through small hit arrays unchanged', () => {
      const hits: DocumentSearchHit[] = [
        { chunkId: 1, documentFileId: 1, datasetId: 1, fileName: 'a.pdf', chunkIndex: 0, content: '짧은 내용', score: 0.5 },
      ];
      expect(clampDocumentHits(hits)).toBe(hits);
    });

    it('empty array returned unchanged', () => {
      const hits: DocumentSearchHit[] = [];
      expect(clampDocumentHits(hits)).toBe(hits);
    });

    it('truncates oversized content below token threshold (18K)', () => {
      // 본문이 매우 긴 청크 다수 → content 절단 + 청크 수 감소 두 경로 모두 유도.
      // 한글 비중이 높아 바이트 대비 토큰이 많으므로 18K 토큰 예산을 확실히 초과한다.
      const hits: DocumentSearchHit[] = Array.from({ length: 40 }, (_, i) => ({
        chunkId: i,
        documentFileId: i,
        datasetId: 1,
        fileName: `doc_${i}.pdf`,
        chunkIndex: 0,
        content: '가나다라마바사아자차카타파하'.repeat(800),
        score: 0.9,
      }));
      // 사전 조건: 입력이 실제로 예산을 초과해 clamp 경로가 동작하는지 보장
      expect(estimateTokens(JSON.stringify(hits))).toBeGreaterThan(18_000);

      const out = clampDocumentHits(hits);

      // (1) 기존: 출력이 입력보다 작아졌는지
      expect(JSON.stringify(out).length).toBeLessThan(JSON.stringify(hits).length);
      // (2) 핵심: clamp가 강제하는 실제 예산(18K 토큰) 이내인지 (analytics 선례와 동일)
      expect(estimateTokens(JSON.stringify(out))).toBeLessThanOrEqual(18_000);
      // (3) per-chunk content 절단 단계가 실행됐는지 — 절단 마커 포함 청크 존재 확인
      expect(out.some((h) => h.content.includes('…(내용 일부 생략)'))).toBe(true);
    });
  });

  // --- tool registration ---
  it('search_documents is registered in the MCP server', () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const registeredTools = Object.keys((server.instance as any)._registeredTools);
    expect(registeredTools).toContain('search_documents');
  });
});
