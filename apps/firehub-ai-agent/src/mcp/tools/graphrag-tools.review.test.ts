// AI 검수 인박스 소비 도구 + 적재 이력 도구 단위 테스트.
// 배경: 에이전트는 검수 항목을 큐에 "등록"만 할 수 있고 조회·승인·거부는 웹 UI 전용이었다(HITL 비대칭).
// 승인은 Neo4j 를 비가역적으로 바꾸므로, 가드(항목별 확인·property 정정값 필수)를 여기서 고정한다.
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../graphrag/neo4j-client.js', () => ({ bootstrapConstraints: vi.fn().mockResolvedValue(undefined) }));
vi.mock('../../graphrag/loader.js', () => ({
  loadGraph: vi.fn().mockResolvedValue({ nodes: 0, relations: 0 }),
  loadTableGraph: vi.fn().mockResolvedValue({ nodes: 0, relations: 0 }),
}));

import { registerGraphragTools, summarizeReviewItem } from './graphrag-tools.js';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const safeTool = (name: string, description: string, _s: unknown, handler: (a: any) => Promise<any>) =>
  ({ name, description, handler });
const jsonResult = (data: unknown) => data;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function findTool(apiClient: any, name: string) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const tools = registerGraphragTools(apiClient, safeTool as any, jsonResult as any) as any[];
  const tool = tools.find((t) => t.name === name);
  expect(tool, `${name} 미등록`).toBeDefined();
  return tool!;
}

// itemType 별 payload 형태(백엔드 ReviewItemService 가 만드는 실제 키에 맞춤).
const ITEMS = [
  {
    id: 1, itemType: 'synonym', status: 'pending', datasetId: 5, signalType: 'similarity',
    signalScore: 0.72, reason: '동일 대상 추정', createdAt: '2026-01-01T00:00:00',
    payload: { entityType: 'Incident', nameA: '가산동 화재', nameB: '가산동火災' },
  },
  {
    id: 2, itemType: 'property', status: 'pending', datasetId: 5, signalType: 'normalize_fail',
    signalScore: null, reason: '숫자 변환 실패', createdAt: '2026-01-01T00:00:00',
    payload: { entityKey: 'k1', entityType: 'Incident', propertyName: '피해액', dataType: 'number', rawText: '약 1억 2천만원' },
  },
  {
    id: 3, itemType: 'entity', status: 'pending', datasetId: 5, signalType: 'confidence',
    signalScore: 0.41, reason: '추정 서술', createdAt: '2026-01-01T00:00:00',
    payload: { entityType: 'Building', name: '○○빌딩', relations: [] },
  },
  {
    id: 4, itemType: 'relation', status: 'pending', datasetId: 5, signalType: 'confidence',
    signalScore: 0.38, reason: '암시적 서술', createdAt: '2026-01-01T00:00:00',
    payload: { subjectKey: 'a', relType: 'CAUSED_BY', objectKey: 'b', subjectName: '화재A', objectName: '누전' },
  },
];

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function baseClient(overrides: Partial<any> = {}) {
  return {
    listReviewItems: vi.fn().mockResolvedValue(ITEMS),
    getReviewItemEvidence: vi.fn().mockResolvedValue([{ chunkId: 11, content: '원문 스니펫' }]),
    approveReviewItem: vi.fn().mockImplementation(async (id: number) => ({
      ...ITEMS.find((i) => i.id === id)!, status: 'approved',
    })),
    rejectReviewItem: vi.fn().mockImplementation(async (id: number) => ({
      ...ITEMS.find((i) => i.id === id)!, status: 'rejected',
    })),
    listGraphIngests: vi.fn().mockResolvedValue([
      { id: 1, datasetId: 5, ingestedAt: '2026-01-01T00:00:00', schemaVersionAtIngest: 2, chunkCount: 10, nodeCount: 30, edgeCount: 20, extractionFailures: 0, status: 'SUCCESS' },
    ]),
    listStaleGraphIngests: vi.fn().mockResolvedValue([
      { datasetId: 5, latestIngestedAt: '2026-01-01T00:00:00', schemaVersionAtIngest: 2, currentSchemaVersion: 3 },
    ]),
    ...overrides,
  };
}

beforeEach(() => vi.clearAllMocks());

describe('summarizeReviewItem', () => {
  // payload 를 그대로 흘리면 모델이 타입별 필드명을 추측(환각)하거나 프롬프트에 하드코딩한다.
  it('4개 itemType 을 모두 사람이 읽을 수 있는 summary 로 환원한다', () => {
    const summaries = ITEMS.map((i) => summarizeReviewItem(i).summary as string);
    expect(summaries[0]).toContain('가산동 화재');
    expect(summaries[1]).toContain('약 1억 2천만원');
    expect(summaries[2]).toContain('○○빌딩');
    expect(summaries[3]).toContain('CAUSED_BY');
    // 어떤 타입도 "[object Object]" 나 빈 요약으로 새지 않아야 한다
    for (const s of summaries) expect(s).not.toMatch(/object Object|undefined/);
  });

  it('알 수 없는 itemType 도 throw 하지 않고 표기한다', () => {
    const out = summarizeReviewItem({
      id: 9, itemType: 'unknown_kind', status: 'pending', datasetId: null,
      signalScore: null, reason: null, payload: {},
    });
    expect(out.summary).toContain('unknown_kind');
  });

  it('property 항목은 정정값 입력을 위해 rawText 를 별도 노출한다', () => {
    expect(summarizeReviewItem(ITEMS[1]).rawText).toBe('약 1억 2천만원');
    expect(summarizeReviewItem(ITEMS[0]).rawText).toBeUndefined();
  });
});

describe('graphrag_list_review_items', () => {
  it('필터를 그대로 전달하고 정규화된 항목을 반환한다', async () => {
    const client = baseClient();
    const out = await findTool(client, 'graphrag_list_review_items').handler({ status: 'pending', itemType: 'synonym' });
    expect(client.listReviewItems).toHaveBeenCalledWith('pending', 'synonym');
    expect(out.items[0].summary).toContain('동의어');
    // 원본 payload 는 흘리지 않는다
    expect(out.items[0].payload).toBeUndefined();
  });

  // 조용한 절단은 "전부 처리했다"는 오답으로 이어진다.
  it('limit 초과 시 truncated 를 표면화한다', async () => {
    const client = baseClient();
    const out = await findTool(client, 'graphrag_list_review_items').handler({ limit: 2 });
    expect(out.total).toBe(4);
    expect(out.returned).toBe(2);
    expect(out.truncated).toBe(true);
  });

  it('항목 수가 limit 이하면 truncated=false', async () => {
    const client = baseClient();
    const out = await findTool(client, 'graphrag_list_review_items').handler({});
    expect(out.truncated).toBe(false);
  });
});

describe('graphrag_review_evidence', () => {
  it('원문 청크를 반환하고 비었으면 empty 로 표시한다', async () => {
    const client = baseClient();
    expect((await findTool(client, 'graphrag_review_evidence').handler({ id: 1 })).empty).toBe(false);

    const noEvidence = baseClient({ getReviewItemEvidence: vi.fn().mockResolvedValue([]) });
    expect((await findTool(noEvidence, 'graphrag_review_evidence').handler({ id: 1 })).empty).toBe(true);
  });
});

describe('graphrag_approve_review_item', () => {
  it('비-property 항목은 정정값 없이 승인한다', async () => {
    const client = baseClient();
    const out = await findTool(client, 'graphrag_approve_review_item').handler({ id: 1 });
    expect(client.approveReviewItem).toHaveBeenCalledWith(1, undefined);
    expect(out.status).toBe('approved');
  });

  // 백엔드도 400 으로 막지만, 여기서 걸러야 모델이 같은 턴에 원문을 보고 정정값을 물을 수 있다.
  it('property 항목을 정정값 없이 승인하면 원문과 함께 거부한다', async () => {
    const client = baseClient();
    await expect(
      findTool(client, 'graphrag_approve_review_item').handler({ id: 2 }),
    ).rejects.toThrow(/약 1억 2천만원/);
    expect(client.approveReviewItem).not.toHaveBeenCalled();
  });

  it('property 항목도 정정값이 있으면 승인한다', async () => {
    const client = baseClient();
    await findTool(client, 'graphrag_approve_review_item').handler({ id: 2, correctedValue: '120000000' });
    expect(client.approveReviewItem).toHaveBeenCalledWith(2, '120000000');
  });

  it('공백만 있는 정정값은 누락으로 취급한다', async () => {
    const client = baseClient();
    await expect(
      findTool(client, 'graphrag_approve_review_item').handler({ id: 2, correctedValue: '   ' }),
    ).rejects.toThrow();
    expect(client.approveReviewItem).not.toHaveBeenCalled();
  });

  // 배치 승인 중 정정값을 다음 항목까지 끌고 가는 실수는 흔하다. 백엔드는 synonym 분기에서
  // correctedValue 를 무시한 채 엔티티를 병합하므로, 조용한 오적재가 된다.
  it('비-property 항목에 정정값을 주면 오적재 위험을 알리고 거부한다', async () => {
    const client = baseClient();
    await expect(
      findTool(client, 'graphrag_approve_review_item').handler({ id: 1, correctedValue: '120000000' }),
    ).rejects.toThrow(/correctedValue|정정값/);
    expect(client.approveReviewItem).not.toHaveBeenCalled();
  });

  it('pending 목록에 없는 id 는 승인 시도 전에 거부한다', async () => {
    const client = baseClient({ listReviewItems: vi.fn().mockResolvedValue([]) });
    await expect(
      findTool(client, 'graphrag_approve_review_item').handler({ id: 1 }),
    ).rejects.toThrow(/이미 처리|찾을 수 없/);
    expect(client.approveReviewItem).not.toHaveBeenCalled();
  });

  it('도구 설명이 비가역성과 일괄 승인 금지를 명시한다', () => {
    const tool = findTool(baseClient(), 'graphrag_approve_review_item');
    expect(tool.description).toMatch(/되돌릴 수 없|비가역/);
    expect(tool.description).toMatch(/일괄 승인 금지/);
  });
});

describe('graphrag_reject_review_item', () => {
  it('거부는 상태만 바꾼다', async () => {
    const client = baseClient();
    const out = await findTool(client, 'graphrag_reject_review_item').handler({ id: 3 });
    expect(client.rejectReviewItem).toHaveBeenCalledWith(3);
    expect(out.status).toBe('rejected');
  });
});

describe('graphrag_ingest_history', () => {
  it('datasetId 지정 시 해당 데이터셋 이력을 반환한다', async () => {
    const client = baseClient();
    const out = await findTool(client, 'graphrag_ingest_history').handler({ datasetId: 5 });
    expect(client.listGraphIngests).toHaveBeenCalledWith(5);
    expect(out.mode).toBe('history');
    expect(out.count).toBe(1);
  });

  it('datasetId 생략 시 stale 목록을 반환한다', async () => {
    const client = baseClient();
    const out = await findTool(client, 'graphrag_ingest_history').handler({});
    expect(client.listStaleGraphIngests).toHaveBeenCalledTimes(1);
    expect(client.listGraphIngests).not.toHaveBeenCalled();
    expect(out.mode).toBe('stale');
    expect(out.staleDatasets[0]).toMatchObject({ datasetId: 5, currentSchemaVersion: 3 });
  });
});
