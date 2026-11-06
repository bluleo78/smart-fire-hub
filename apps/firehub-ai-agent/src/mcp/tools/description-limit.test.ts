import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createFireHubMcpServer } from '../firehub-mcp-server.js';
import { FireHubApiClient } from '../api-client.js';
import { MAX_DESCRIPTION_LENGTH } from './shared-schemas.js';

/**
 * 자산 설명 길이 상한 (WD-520)
 *
 * 서버는 설명이 2,000자를 넘으면 400 으로 거부한다. 설명을 받는 생성·수정 도구가 입력 스키마에서 먼저 막아,
 * 모델이 대화 중간의 400 대신 설명 칸에 대한 분명한 검증 오류를 받는지 확인한다.
 * 다른 필수 필드는 채우지 않으므로 전체 통과 여부가 아니라 description 경로의 오류만 본다.
 */

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

/** 도구 이름 → 안내 문구에 들어가는 자산 이름. */
const TOOLS: Array<[string, string]> = [
  ['create_saved_query', '쿼리'],
  ['create_chart', '차트'],
  ['create_dashboard', '대시보드'],
  ['create_dataset', '데이터셋'],
  ['update_dataset', '데이터셋'],
  ['create_pipeline', '파이프라인'],
  ['update_pipeline', '파이프라인'],
  ['create_api_connection', 'API 연결'],
  ['update_api_connection', 'API 연결'],
  ['create_report_template', '리포트 양식'],
  ['update_report_template', '리포트 양식'],
];

describe('설명 길이 상한 (WD-520)', () => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let tools: Record<string, any>;

  beforeEach(() => {
    const server = createFireHubMcpServer(createMockClient());
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    tools = (server.instance as any)._registeredTools;
  });

  /** 주어진 설명으로 입력 스키마를 검사해 description 경로의 오류 메시지만 돌려준다. */
  function descriptionIssues(toolName: string, description: string | undefined): string[] {
    const entry = tools[toolName];
    if (!entry) throw new Error(`Tool ${toolName} not found in registered tools`);
    const result = entry.inputSchema.safeParse({ description });
    if (result.success) return [];
    return result.error.issues
      .filter((issue: { path: PropertyKey[] }) => issue.path[0] === 'description')
      .map((issue: { message: string }) => issue.message);
  }

  it.each(TOOLS)('%s — 2,001자 설명은 한국어 안내로 거부한다', (toolName, asset) => {
    expect(descriptionIssues(toolName, '가'.repeat(MAX_DESCRIPTION_LENGTH + 1))).toEqual([
      `${asset} 설명은 최대 2,000자까지 입력할 수 있습니다.`,
    ]);
  });

  it.each(TOOLS)('%s — 2,000자 설명과 생략은 통과한다', (toolName) => {
    expect(descriptionIssues(toolName, '가'.repeat(MAX_DESCRIPTION_LENGTH))).toEqual([]);
    expect(descriptionIssues(toolName, undefined)).toEqual([]);
  });

  it.each(TOOLS)('%s — 설명 파라미터 안내에 상한(최대 2,000자)이 적혀 있다', (toolName) => {
    const shape = tools[toolName].inputSchema.shape;
    expect(shape.description.description).toContain('최대 2,000자');
  });
});
