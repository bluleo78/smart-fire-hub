# GraphRAG Walking Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 화재조사 보고서(합성 문서) 1종을 온톨로지 기반 GraphRAG로 관통 — LLM 추출 → Neo4j 적재 → 챗에서 그래프 근거 인용 답변까지 end-to-end 배선.

**Architecture:** firehub-ai-agent 내 신규 `src/graphrag/` 모듈이 소유. 추출은 `classification-service.ts`의 raw axios→Anthropic messages 패턴 복제(“query()” 아님). 그래프는 Neo4j에 단일 `:Entity` 라벨(type 속성) + 단일 `:REL` 관계(type 속성)로 저장해 동적 라벨/APOC 의존을 회피. 청크 원천은 firehub-api에 신설하는 내부 bulk-read 엔드포인트로 당김. 질의 시드는 기존 `searchDocuments` 벡터검색 재사용.

**Tech Stack:** TypeScript(ESM), Vitest+nock, neo4j-driver, zod/v4, axios, `@anthropic-ai/claude-agent-sdk`(MCP tool 등록), Spring Boot/Java 21(jOOQ, Flyway 무관 — 신규 마이그레이션 없음), Neo4j 5(docker-compose).

## Global Constraints

- zod import는 반드시 `import { z } from 'zod/v4'` (프로젝트 규약).
- 한국어 주석 필수 — 클래스·메서드·주요 로직에 무엇을·왜.
- ai-agent 신규 파일은 소스와 같은 디렉토리에 `*.test.ts` (Vitest). 실행: `pnpm --filter @smart-fire-hub/firehub-ai-agent test`.
- ai-agent → api 호출 헤더: `Authorization: Internal <token>`, `X-On-Behalf-Of: <userId>` (FireHubApiClient가 자동 부착).
- 커밋/배포는 사용자 승인 후에만 (executor는 각 Task 끝 커밋 단계에서 멈춰 리뷰 받음).
- Neo4j 노드 모델 고정: `(:Entity {key, type, name, sourceChunkIds:int[]})-[:REL {type, sourceChunkIds:int[]}]->(:Entity)`. `key = "<Type>:<normalizedName>"`.
- 온톨로지 고정 — 엔티티 6: `Incident, Building, Cause, Damage, Equipment, Regulation`. 관계 6(허용 트리플):
  `Incident-OCCURRED_AT->Building`, `Incident-CAUSED_BY->Cause`, `Incident-RESULTED_IN->Damage`,
  `Building-HAS_EQUIPMENT->Equipment`, `Incident-VIOLATED->Regulation`, `Equipment-GOVERNED_BY->Regulation`.
- 성공 기준: 대표 질문 3개가 챗에서 그래프 노드/관계 + 출처 fileName을 인용해 답해짐. **품질 우위는 증명 대상 아님**(합성 샘플).

---

### Task 1: 온톨로지 정의 + 합성 샘플 문서

**Files:**
- Create: `apps/firehub-ai-agent/src/graphrag/ontology.ts`
- Test: `apps/firehub-ai-agent/src/graphrag/ontology.test.ts`
- Create (fixtures): `docs/superpowers/fixtures/graphrag-samples/report-01.md` … `report-06.md`

**Interfaces:**
- Produces:
  - `type EntityType = 'Incident'|'Building'|'Cause'|'Damage'|'Equipment'|'Regulation'`
  - `type RelationType = 'OCCURRED_AT'|'CAUSED_BY'|'RESULTED_IN'|'HAS_EQUIPMENT'|'VIOLATED'|'GOVERNED_BY'`
  - `interface ExtractedEntity { type: EntityType; name: string }`
  - `interface ExtractedRelation { subject: string; type: RelationType; object: string }`
  - `interface ExtractionResult { entities: ExtractedEntity[]; relations: ExtractedRelation[] }`
  - `isEntityType(x: string): x is EntityType`
  - `isRelationType(x: string): x is RelationType`
  - `isAllowedTriple(subjectType: EntityType, rel: RelationType, objectType: EntityType): boolean`
  - `ENTITY_TYPES: readonly EntityType[]`, `RELATION_TYPES: readonly RelationType[]`, `ONTOLOGY_TRIPLES`

- [ ] **Step 1: 실패 테스트 작성**

```ts
// apps/firehub-ai-agent/src/graphrag/ontology.test.ts
import { describe, it, expect } from 'vitest';
import { isEntityType, isRelationType, isAllowedTriple } from './ontology';

describe('ontology', () => {
  it('유효 엔티티/관계 타입을 인식한다', () => {
    expect(isEntityType('Incident')).toBe(true);
    expect(isEntityType('Person')).toBe(false);
    expect(isRelationType('CAUSED_BY')).toBe(true);
    expect(isRelationType('KNOWS')).toBe(false);
  });
  it('허용된 트리플만 통과시킨다', () => {
    expect(isAllowedTriple('Incident', 'CAUSED_BY', 'Cause')).toBe(true);
    expect(isAllowedTriple('Building', 'HAS_EQUIPMENT', 'Equipment')).toBe(true);
    // 방향/조합이 온톨로지에 없으면 거부
    expect(isAllowedTriple('Cause', 'CAUSED_BY', 'Incident')).toBe(false);
    expect(isAllowedTriple('Building', 'CAUSED_BY', 'Cause')).toBe(false);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/graphrag/ontology.test.ts`
Expected: FAIL — `Cannot find module './ontology'`

- [ ] **Step 3: 온톨로지 구현**

```ts
// apps/firehub-ai-agent/src/graphrag/ontology.ts
// 코어 온톨로지 — 화재조사 도메인의 엔티티/관계 타입과 허용 트리플을 고정 정의한다.
// walking skeleton 범위에서는 이 파일이 유일한 온톨로지 소스(추후 DB/관리 UI로 승격 예정).

export const ENTITY_TYPES = [
  'Incident', 'Building', 'Cause', 'Damage', 'Equipment', 'Regulation',
] as const;
export type EntityType = (typeof ENTITY_TYPES)[number];

export const RELATION_TYPES = [
  'OCCURRED_AT', 'CAUSED_BY', 'RESULTED_IN', 'HAS_EQUIPMENT', 'VIOLATED', 'GOVERNED_BY',
] as const;
export type RelationType = (typeof RELATION_TYPES)[number];

export interface ExtractedEntity { type: EntityType; name: string; }
export interface ExtractedRelation { subject: string; type: RelationType; object: string; }
export interface ExtractionResult { entities: ExtractedEntity[]; relations: ExtractedRelation[]; }

// 허용 트리플 (주어타입, 관계, 목적어타입) — 이 조합만 그래프에 적재한다.
export const ONTOLOGY_TRIPLES: ReadonlyArray<readonly [EntityType, RelationType, EntityType]> = [
  ['Incident', 'OCCURRED_AT', 'Building'],
  ['Incident', 'CAUSED_BY', 'Cause'],
  ['Incident', 'RESULTED_IN', 'Damage'],
  ['Building', 'HAS_EQUIPMENT', 'Equipment'],
  ['Incident', 'VIOLATED', 'Regulation'],
  ['Equipment', 'GOVERNED_BY', 'Regulation'],
];

export function isEntityType(x: string): x is EntityType {
  return (ENTITY_TYPES as readonly string[]).includes(x);
}
export function isRelationType(x: string): x is RelationType {
  return (RELATION_TYPES as readonly string[]).includes(x);
}
// 주어·관계·목적어 조합이 온톨로지 허용 트리플에 존재하는지 검사한다.
export function isAllowedTriple(subjectType: EntityType, rel: RelationType, objectType: EntityType): boolean {
  return ONTOLOGY_TRIPLES.some(([s, r, o]) => s === subjectType && r === rel && o === objectType);
}
```

- [ ] **Step 4: 통과 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/graphrag/ontology.test.ts`
Expected: PASS

- [ ] **Step 5: 합성 샘플 문서 6건 작성**

`docs/superpowers/fixtures/graphrag-samples/report-01.md` ~ `report-06.md`. 아래 **설계 요구사항을 반드시 충족**(엔티티가 문서 간 겹치고, 같은 실체를 다르게 표기):

- `report-01.md`, `report-04.md`: **같은 건물** — 표기를 다르게: "중앙로 상가건물" vs "중앙로 상가" (대소문자/공백/축약 변형). 재발 화재.
- `report-02.md`, `report-05.md`: 발화원인 **"전기적 요인(누전)"** 공유 → 반복 규정 위반 "소방시설법 제12조".
- `report-03.md`, `report-06.md`: **"스프링클러 미작동"** 설비 결함 → 피해 규모 확대 서술.

각 보고서 구조(한국어): `사건개요 / 발생장소(건물) / 발화원인 / 피해내역 / 소방설비 상태 / 관련 법규 위반` 절 포함. 예시 `report-01.md`:

```markdown
# 화재조사 보고서 2026-001

## 사건 개요
2026-01-15 03:20경 중앙로 상가건물 2층에서 화재 발생.

## 발생 장소
중앙로 상가건물 (지상 4층 근린생활시설).

## 발화 원인
전기적 요인 — 분전반 내부 누전으로 판단됨.

## 피해 내역
2층 점포 전소, 인명피해 없음, 재산피해 약 1.2억원.

## 소방설비 상태
스프링클러 설비 미작동(밸브 폐쇄 상태). 자동화재탐지설비는 정상 작동.

## 관련 법규 위반
소방시설법 제12조(소방시설의 유지·관리) 위반 확인.
```

나머지 5건도 위 겹침 규칙에 맞춰 실제 텍스트로 작성(플레이스홀더 금지 — 각 절에 구체 문장).

- [ ] **Step 6: 커밋**

```bash
git add -f apps/firehub-ai-agent/src/graphrag/ontology.ts apps/firehub-ai-agent/src/graphrag/ontology.test.ts docs/superpowers/fixtures/graphrag-samples/
git commit -m "feat(graphrag): 코어 온톨로지 정의 + 화재조사 합성 샘플 6건"
```

---

### Task 2: Extractor (청크 텍스트 → 온톨로지 준수 엔티티/관계 JSON)

**Files:**
- Create: `apps/firehub-ai-agent/src/graphrag/extractor.ts`
- Create (검수 스크립트): `apps/firehub-ai-agent/src/graphrag/dump-extraction.ts`
- Test: `apps/firehub-ai-agent/src/graphrag/extractor.test.ts`

**Interfaces:**
- Consumes: `ExtractionResult`, `isEntityType`, `isRelationType`, `isAllowedTriple`, `EntityType` (Task 1)
- Produces:
  - `interface ExtractOptions { model: string; apiKey: string; anthropicBaseUrl?: string }`
  - `async function extractGraph(text: string, opts: ExtractOptions): Promise<ExtractionResult>`
    — Anthropic messages API 단발 호출 후, 응답에서 JSON 파싱 → 온톨로지 밖 엔티티/관계 폐기 → 유효분만 반환.

**참조 패턴:** `apps/firehub-ai-agent/src/services/classification-service.ts:114` (`callAnthropicClassify` — raw `axios.post('https://api.anthropic.com/v1/messages', ...)`, headers `x-api-key`/`anthropic-version:'2023-06-01'`, 응답 텍스트에서 ```` ```json ``` ```` 블록 regex 추출).

- [ ] **Step 1: 실패 테스트 작성 (nock으로 Anthropic 모킹)**

```ts
// apps/firehub-ai-agent/src/graphrag/extractor.test.ts
import { describe, it, expect, afterEach } from 'vitest';
import nock from 'nock';
import { extractGraph } from './extractor';

const OPTS = { model: 'claude-haiku-4-5', apiKey: 'test-key', anthropicBaseUrl: 'https://api.anthropic.com' };

function mockAnthropic(jsonPayload: string) {
  return nock('https://api.anthropic.com')
    .post('/v1/messages')
    .reply(200, { content: [{ type: 'text', text: '```json\n' + jsonPayload + '\n```' }] });
}

afterEach(() => nock.cleanAll());

describe('extractGraph', () => {
  it('온톨로지 유효 엔티티/관계만 반환하고 무효분은 폐기한다', async () => {
    mockAnthropic(JSON.stringify({
      entities: [
        { type: 'Incident', name: '2026-001' },
        { type: 'Cause', name: '전기적 요인' },
        { type: 'Person', name: '홍길동' },          // 온톨로지 밖 → 폐기
      ],
      relations: [
        { subject: '2026-001', type: 'CAUSED_BY', object: '전기적 요인' }, // 유효
        { subject: '전기적 요인', type: 'CAUSED_BY', object: '2026-001' }, // 방향 위반 → 폐기
      ],
    }));
    const result = await extractGraph('화재 보고서 본문...', OPTS);
    expect(result.entities).toEqual([
      { type: 'Incident', name: '2026-001' },
      { type: 'Cause', name: '전기적 요인' },
    ]);
    expect(result.relations).toEqual([
      { subject: '2026-001', type: 'CAUSED_BY', object: '전기적 요인' },
    ]);
  });

  it('깨진 JSON이면 빈 결과를 반환한다(배치 중단 없이)', async () => {
    nock('https://api.anthropic.com').post('/v1/messages')
      .reply(200, { content: [{ type: 'text', text: 'no json here' }] });
    const result = await extractGraph('본문', OPTS);
    expect(result).toEqual({ entities: [], relations: [] });
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/graphrag/extractor.test.ts`
Expected: FAIL — `Cannot find module './extractor'`

- [ ] **Step 3: extractor 구현**

```ts
// apps/firehub-ai-agent/src/graphrag/extractor.ts
// 청크 텍스트를 LLM에 넘겨 온톨로지 준수 엔티티/관계를 추출한다.
// classification-service.ts와 동일하게 Anthropic messages API를 raw axios로 단발 호출한다(query() 아님).
import axios from 'axios';
import {
  ExtractionResult, EntityType, isEntityType, isRelationType, isAllowedTriple, ENTITY_TYPES, RELATION_TYPES,
} from './ontology';

export interface ExtractOptions { model: string; apiKey: string; anthropicBaseUrl?: string; }

// LLM에 온톨로지 스키마와 함께 JSON 산출을 지시하는 시스템 프롬프트.
const SYSTEM_PROMPT = `너는 화재조사 보고서에서 지식 그래프를 추출하는 도구다.
아래 온톨로지에 **정확히 일치하는** 엔티티와 관계만 추출한다.
엔티티 타입: ${ENTITY_TYPES.join(', ')}
관계 타입: ${RELATION_TYPES.join(', ')}
반드시 다음 형식의 JSON 코드블록만 출력한다:
\`\`\`json
{"entities":[{"type":"Incident","name":"..."}],"relations":[{"subject":"엔티티명","type":"CAUSED_BY","object":"엔티티명"}]}
\`\`\`
name은 본문에 등장한 표기를 그대로 사용한다.`;

// 응답 텍스트에서 첫 JSON 코드블록을 추출해 파싱한다. 실패 시 null.
function parseJsonBlock(text: string): unknown | null {
  const m = text.match(/```json\s*([\s\S]*?)```/);
  const raw = m ? m[1] : text;
  try { return JSON.parse(raw.trim()); } catch { return null; }
}

export async function extractGraph(text: string, opts: ExtractOptions): Promise<ExtractionResult> {
  const base = opts.anthropicBaseUrl ?? 'https://api.anthropic.com';
  let content = '';
  try {
    const resp = await axios.post(
      `${base}/v1/messages`,
      { model: opts.model, max_tokens: 2048, system: SYSTEM_PROMPT,
        messages: [{ role: 'user', content: text }] },
      { headers: { 'x-api-key': opts.apiKey, 'anthropic-version': '2023-06-01', 'content-type': 'application/json' },
        timeout: 60_000 },
    );
    content = resp.data?.content?.[0]?.text ?? '';
  } catch {
    return { entities: [], relations: [] }; // LLM 호출 실패 → 빈 결과(호출부에서 배치 계속)
  }

  const parsed = parseJsonBlock(content) as { entities?: unknown[]; relations?: unknown[] } | null;
  if (!parsed) return { entities: [], relations: [] };

  // 엔티티: 온톨로지 타입에 없는 것은 폐기. 이름→타입 맵을 만들어 관계 검증에 사용.
  const entities = (parsed.entities ?? [])
    .filter((e: any): e is { type: EntityType; name: string } =>
      e && typeof e.name === 'string' && isEntityType(e.type))
    .map((e) => ({ type: e.type, name: e.name }));
  const typeByName = new Map<string, EntityType>(entities.map((e) => [e.name, e.type]));

  // 관계: 관계타입 유효 + 주어·목적어가 추출된 엔티티 + 허용 트리플이어야 함.
  const relations = (parsed.relations ?? [])
    .filter((r: any) => r && isRelationType(r.type)
      && typeByName.has(r.subject) && typeByName.has(r.object)
      && isAllowedTriple(typeByName.get(r.subject)!, r.type, typeByName.get(r.object)!))
    .map((r: any) => ({ subject: r.subject, type: r.type, object: r.object }));

  return { entities, relations };
}
```

- [ ] **Step 4: 통과 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/graphrag/extractor.test.ts`
Expected: PASS (2 tests)

- [ ] **Step 5: 검수용 덤프 스크립트 작성 (리스크 먼저 깨기 — 사람이 눈으로 검수)**

```ts
// apps/firehub-ai-agent/src/graphrag/dump-extraction.ts
// 합성 샘플 문서들을 extractor로 돌려 결과 JSON을 stdout에 덤프한다.
// 목적: Neo4j 배선 전에 추출 품질을 사람이 눈으로 검수(엔티티 해소·타입 준수)한다.
// 실행: cd apps/firehub-ai-agent && ANTHROPIC_API_KEY=... npx tsx src/graphrag/dump-extraction.ts
import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { extractGraph } from './extractor';

async function main() {
  const dir = resolve(process.cwd(), '../../docs/superpowers/fixtures/graphrag-samples');
  const model = process.env.AI_DEFAULT_MODEL ?? 'claude-haiku-4-5';
  const apiKey = process.env.ANTHROPIC_API_KEY;
  if (!apiKey) throw new Error('ANTHROPIC_API_KEY 필요');
  for (const file of readdirSync(dir).filter((f) => f.endsWith('.md'))) {
    const text = readFileSync(resolve(dir, file), 'utf8');
    const result = await extractGraph(text, { model, apiKey });
    console.log(`\n===== ${file} =====`);
    console.log(JSON.stringify(result, null, 2));
  }
}
main().catch((e) => { console.error(e); process.exit(1); });
```

- [ ] **Step 6: 커밋**

```bash
git add -f apps/firehub-ai-agent/src/graphrag/extractor.ts apps/firehub-ai-agent/src/graphrag/extractor.test.ts apps/firehub-ai-agent/src/graphrag/dump-extraction.ts
git commit -m "feat(graphrag): LLM 엔티티/관계 추출기 + 검수 덤프 스크립트"
```

> **리뷰 게이트(수동, 선택):** API 키가 있으면 Step 5 스크립트를 실제 실행해 6건 추출 결과를 눈으로 검수. 엔티티가 온톨로지를 따르고 문서 간 겹침이 잡히면 다음 Task 진행. 이 단계가 skeleton의 진짜 리스크 지점이다.

---

### Task 3: Resolver (엔티티 정규화 + 병합 키)

**Files:**
- Create: `apps/firehub-ai-agent/src/graphrag/resolver.ts`
- Test: `apps/firehub-ai-agent/src/graphrag/resolver.test.ts`

**Interfaces:**
- Consumes: `ExtractionResult` (Task 1)
- Produces:
  - `interface ResolvedEntity { key: string; type: EntityType; name: string }`
  - `interface ResolvedRelation { subjectKey: string; type: RelationType; objectKey: string }`
  - `interface ResolvedGraph { entities: ResolvedEntity[]; relations: ResolvedRelation[] }`
  - `function normalizeName(name: string): string` — 앞뒤 공백 제거, 연속 공백 1개로, 소문자화
  - `function entityKey(type: EntityType, name: string): string` — `"<type>:<normalizeName>"`
  - `function resolveExtraction(extraction: ExtractionResult): ResolvedGraph` — 정규화 키로 중복 병합
- Note: 스켈레톤의 엔티티 해소는 **정규화 기반 dedup**(대소문자/공백 변형 병합)까지. 의미적 변형("중앙로 상가" vs "중앙로 상가건물") 병합은 알려진 한계 — 실제 문서 품질 게이트에서 다룬다.

- [ ] **Step 1: 실패 테스트 작성**

```ts
// apps/firehub-ai-agent/src/graphrag/resolver.test.ts
import { describe, it, expect } from 'vitest';
import { normalizeName, entityKey, resolveExtraction } from './resolver';

describe('resolver', () => {
  it('정규화는 공백/대소문자 변형을 하나로 만든다', () => {
    expect(normalizeName('  중앙로   상가 ')).toBe('중앙로 상가');
    expect(entityKey('Building', '중앙로  상가')).toBe(entityKey('Building', '중앙로 상가'));
  });
  it('정규화 변형 엔티티를 하나로 병합하고 관계를 키로 재작성한다', () => {
    const g = resolveExtraction({
      entities: [
        { type: 'Building', name: '중앙로 상가' },
        { type: 'Building', name: '중앙로  상가' },   // 공백 변형 → 병합
        { type: 'Incident', name: '2026-001' },
      ],
      relations: [
        { subject: '2026-001', type: 'OCCURRED_AT', object: '중앙로  상가' },
      ],
    });
    expect(g.entities).toHaveLength(2); // Building 1 + Incident 1
    expect(g.relations).toEqual([
      { subjectKey: entityKey('Incident', '2026-001'), type: 'OCCURRED_AT', objectKey: entityKey('Building', '중앙로 상가') },
    ]);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/graphrag/resolver.test.ts`
Expected: FAIL — `Cannot find module './resolver'`

- [ ] **Step 3: resolver 구현**

```ts
// apps/firehub-ai-agent/src/graphrag/resolver.ts
// 추출된 엔티티를 정규화 키로 병합하고, 관계를 키 기반으로 재작성한다(엔티티 해소).
import { ExtractionResult, EntityType, RelationType } from './ontology';

export interface ResolvedEntity { key: string; type: EntityType; name: string; }
export interface ResolvedRelation { subjectKey: string; type: RelationType; objectKey: string; }
export interface ResolvedGraph { entities: ResolvedEntity[]; relations: ResolvedRelation[]; }

// 앞뒤 공백 제거 + 연속 공백 1칸 + 소문자화. 표기 변형을 흡수하는 최소 정규화.
export function normalizeName(name: string): string {
  return name.trim().replace(/\s+/g, ' ').toLowerCase();
}
// 엔티티 병합 키 = "<type>:<정규화이름>". Neo4j 노드 유일성 기준.
export function entityKey(type: EntityType, name: string): string {
  return `${type}:${normalizeName(name)}`;
}

export function resolveExtraction(extraction: ExtractionResult): ResolvedGraph {
  // 이름(원표기)→키, 키→정규 엔티티. 첫 등장 표기를 대표 name으로 보존.
  const byKey = new Map<string, ResolvedEntity>();
  const keyByName = new Map<string, string>();
  for (const e of extraction.entities) {
    const key = entityKey(e.type, e.name);
    keyByName.set(e.name, key);
    if (!byKey.has(key)) byKey.set(key, { key, type: e.type, name: e.name.trim().replace(/\s+/g, ' ') });
  }
  // 관계를 키로 재작성 + (subjectKey,type,objectKey) 중복 제거.
  const relSet = new Set<string>();
  const relations: ResolvedRelation[] = [];
  for (const r of extraction.relations) {
    const sk = keyByName.get(r.subject), ok = keyByName.get(r.object);
    if (!sk || !ok) continue;
    const dedup = `${sk}|${r.type}|${ok}`;
    if (relSet.has(dedup)) continue;
    relSet.add(dedup);
    relations.push({ subjectKey: sk, type: r.type, objectKey: ok });
  }
  return { entities: [...byKey.values()], relations };
}
```

- [ ] **Step 4: 통과 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/graphrag/resolver.test.ts`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -f apps/firehub-ai-agent/src/graphrag/resolver.ts apps/firehub-ai-agent/src/graphrag/resolver.test.ts
git commit -m "feat(graphrag): 엔티티 정규화·병합 resolver"
```

---

### Task 4: Neo4j 인프라 + 클라이언트

**Files:**
- Modify: `docker-compose.yml` (dev — neo4j 서비스 추가)
- Modify: `apps/firehub-ai-agent/package.json` (neo4j-driver 의존성)
- Modify: `apps/firehub-ai-agent/.env.example`
- Create: `apps/firehub-ai-agent/src/graphrag/neo4j-client.ts`
- Test: `apps/firehub-ai-agent/src/graphrag/neo4j-client.integration.test.ts`

**Interfaces:**
- Produces:
  - `function getDriver(): Driver` (neo4j-driver `Driver`) — env `NEO4J_URI/NEO4J_USER/NEO4J_PASSWORD`로 싱글턴 생성
  - `function getSession(): Session`
  - `async function bootstrapConstraints(): Promise<void>` — `Entity.key` 유일성 제약 생성
  - `async function closeDriver(): Promise<void>`

- [ ] **Step 1: neo4j-driver 설치 + docker-compose neo4j 추가**

```bash
pnpm --filter @smart-fire-hub/firehub-ai-agent add neo4j-driver
```

`docker-compose.yml`에 서비스 추가 (기존 postgres 서비스 옆, dev 포트 규약 상 7474/7687 사용):

```yaml
  neo4j:
    image: neo4j:5.26-community
    container_name: smart-fire-hub-neo4j
    ports:
      - "7474:7474"   # HTTP 브라우저
      - "7687:7687"   # Bolt
    environment:
      NEO4J_AUTH: neo4j/firehub-graph-dev   # dev 전용 비밀번호
    volumes:
      - neo4j-data:/data
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:7474 || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
```

동일 파일의 `volumes:` 블록에 `neo4j-data:` 추가.

- [ ] **Step 2: .env.example 갱신**

```
# GraphRAG (Neo4j) — dev 기본값
NEO4J_URI=bolt://localhost:7687
NEO4J_USER=neo4j
NEO4J_PASSWORD=firehub-graph-dev
```

- [ ] **Step 3: 통합 실패 테스트 작성**

```ts
// apps/firehub-ai-agent/src/graphrag/neo4j-client.integration.test.ts
// 실제 Neo4j 필요: `pnpm db:up` 후 실행. 미기동 시 이 파일은 실패한다.
import { describe, it, expect, afterAll } from 'vitest';
import { getSession, bootstrapConstraints, closeDriver } from './neo4j-client';

afterAll(async () => { await closeDriver(); });

describe('neo4j-client (integration)', () => {
  it('제약을 부트스트랩하고 세션으로 쿼리할 수 있다', async () => {
    await bootstrapConstraints();
    const session = getSession();
    try {
      const r = await session.run('RETURN 1 AS n');
      expect(r.records[0].get('n').toNumber()).toBe(1);
    } finally { await session.close(); }
  });
});
```

- [ ] **Step 4: 실패 확인**

Run: `pnpm db:up` 후 `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/graphrag/neo4j-client.integration.test.ts`
Expected: FAIL — `Cannot find module './neo4j-client'`

- [ ] **Step 5: neo4j-client 구현**

```ts
// apps/firehub-ai-agent/src/graphrag/neo4j-client.ts
// Neo4j 드라이버 싱글턴 + 세션 팩토리 + 제약 부트스트랩.
import neo4j, { Driver, Session } from 'neo4j-driver';

let driver: Driver | null = null;

// env로 드라이버를 1회 생성해 재사용한다.
export function getDriver(): Driver {
  if (!driver) {
    const uri = process.env.NEO4J_URI ?? 'bolt://localhost:7687';
    const user = process.env.NEO4J_USER ?? 'neo4j';
    const password = process.env.NEO4J_PASSWORD ?? 'firehub-graph-dev';
    driver = neo4j.driver(uri, neo4j.auth.basic(user, password));
  }
  return driver;
}
export function getSession(): Session {
  return getDriver().session();
}
// Entity.key 유일성 제약 — MERGE 멱등성과 조회 성능의 기반.
export async function bootstrapConstraints(): Promise<void> {
  const session = getSession();
  try {
    await session.run(
      'CREATE CONSTRAINT entity_key IF NOT EXISTS FOR (n:Entity) REQUIRE n.key IS UNIQUE',
    );
  } finally { await session.close(); }
}
export async function closeDriver(): Promise<void> {
  if (driver) { await driver.close(); driver = null; }
}
```

- [ ] **Step 6: 통과 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/graphrag/neo4j-client.integration.test.ts`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add -f docker-compose.yml apps/firehub-ai-agent/package.json apps/firehub-ai-agent/.env.example apps/firehub-ai-agent/src/graphrag/neo4j-client.ts apps/firehub-ai-agent/src/graphrag/neo4j-client.integration.test.ts
git add pnpm-lock.yaml
git commit -m "feat(graphrag): Neo4j dev 인프라 + 드라이버 클라이언트"
```

---

### Task 5: Loader (ResolvedGraph → Neo4j MERGE 멱등 적재)

**Files:**
- Create: `apps/firehub-ai-agent/src/graphrag/loader.ts`
- Test: `apps/firehub-ai-agent/src/graphrag/loader.integration.test.ts`

**Interfaces:**
- Consumes: `ResolvedGraph` (Task 3), `getSession` (Task 4)
- Produces:
  - `async function loadGraph(graph: ResolvedGraph, sourceChunkId: number): Promise<{ nodes: number; relations: number }>`
    — 노드/관계를 `MERGE`로 멱등 적재하고, `sourceChunkIds` 배열에 chunkId를 중복 없이 누적.

- [ ] **Step 1: 멱등성 통합 테스트 작성**

```ts
// apps/firehub-ai-agent/src/graphrag/loader.integration.test.ts
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { getSession, bootstrapConstraints, closeDriver } from './neo4j-client';
import { loadGraph } from './loader';
import { entityKey } from './resolver';

const graph = {
  entities: [
    { key: entityKey('Incident', '2026-001'), type: 'Incident' as const, name: '2026-001' },
    { key: entityKey('Cause', '전기적 요인'), type: 'Cause' as const, name: '전기적 요인' },
  ],
  relations: [
    { subjectKey: entityKey('Incident', '2026-001'), type: 'CAUSED_BY' as const, objectKey: entityKey('Cause', '전기적 요인') },
  ],
};

async function count(label: 'Entity' | 'REL'): Promise<number> {
  const s = getSession();
  try {
    const q = label === 'Entity' ? 'MATCH (n:Entity) RETURN count(n) AS c'
                                 : 'MATCH ()-[r:REL]->() RETURN count(r) AS c';
    return (await s.run(q)).records[0].get('c').toNumber();
  } finally { await s.close(); }
}

beforeAll(async () => {
  await bootstrapConstraints();
  const s = getSession();
  try { await s.run('MATCH (n:Entity) DETACH DELETE n'); } finally { await s.close(); } // 테스트 격리
});
afterAll(async () => { await closeDriver(); });

describe('loadGraph (integration)', () => {
  it('두 번 적재해도 노드/관계 수가 불변이다(멱등)', async () => {
    await loadGraph(graph, 101);
    const n1 = await count('Entity'), r1 = await count('REL');
    await loadGraph(graph, 101);
    expect(await count('Entity')).toBe(n1);
    expect(await count('REL')).toBe(r1);
    expect(n1).toBe(2);
    expect(r1).toBe(1);
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/graphrag/loader.integration.test.ts`
Expected: FAIL — `Cannot find module './loader'`

- [ ] **Step 3: loader 구현**

```ts
// apps/firehub-ai-agent/src/graphrag/loader.ts
// ResolvedGraph를 Neo4j에 MERGE로 멱등 적재한다.
// 모델: (:Entity {key,type,name,sourceChunkIds})-[:REL {type,sourceChunkIds}]->(:Entity)
import { getSession } from './neo4j-client';
import { ResolvedGraph } from './resolver';

export async function loadGraph(
  graph: ResolvedGraph, sourceChunkId: number,
): Promise<{ nodes: number; relations: number }> {
  const session = getSession();
  try {
    // 노드 MERGE — key 기준. sourceChunkIds에 chunkId를 중복 없이 누적(멱등).
    await session.run(
      `UNWIND $entities AS e
       MERGE (n:Entity {key: e.key})
       SET n.type = e.type, n.name = e.name
       SET n.sourceChunkIds =
         CASE WHEN $chunkId IN coalesce(n.sourceChunkIds, [])
              THEN n.sourceChunkIds ELSE coalesce(n.sourceChunkIds, []) + $chunkId END`,
      { entities: graph.entities, chunkId: sourceChunkId },
    );
    // 관계 MERGE — (subjectKey)-[:REL {type}]->(objectKey). sourceChunkIds 동일 누적.
    await session.run(
      `UNWIND $rels AS r
       MATCH (a:Entity {key: r.subjectKey}), (b:Entity {key: r.objectKey})
       MERGE (a)-[x:REL {type: r.type}]->(b)
       SET x.sourceChunkIds =
         CASE WHEN $chunkId IN coalesce(x.sourceChunkIds, [])
              THEN x.sourceChunkIds ELSE coalesce(x.sourceChunkIds, []) + $chunkId END`,
      { rels: graph.relations, chunkId: sourceChunkId },
    );
    return { nodes: graph.entities.length, relations: graph.relations.length };
  } finally { await session.close(); }
}
```

- [ ] **Step 4: 통과 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/graphrag/loader.integration.test.ts`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -f apps/firehub-ai-agent/src/graphrag/loader.ts apps/firehub-ai-agent/src/graphrag/loader.integration.test.ts
git commit -m "feat(graphrag): Neo4j MERGE 멱등 loader"
```

---

### Task 6: 청크 bulk-read API 엔드포인트 + ai-agent 클라이언트

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/document/controller/DocumentChunkController.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/document/dto/ChunkContentResponse.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/document/controller/DocumentChunkControllerTest.java`
- Create: `apps/firehub-ai-agent/src/mcp/api-client/graph-source-api.ts`
- Modify: `apps/firehub-ai-agent/src/mcp/api-client.ts` (위임 메서드 추가)
- Test: `apps/firehub-ai-agent/src/mcp/api-client/graph-source-api.test.ts`

**Interfaces:**
- 기존 활용: `DocumentChunkRepository.findChunkContentsByDataset(long datasetId): List<ChunkContent>` (record `ChunkContent(long chunkId, String content)`), `@RequirePermission`, `Authentication.getPrincipal()→Long userId`.
- Produces (Java): `GET /api/v1/datasets/{datasetId}/document-chunks` → `List<ChunkContentResponse{ Long chunkId, String content }>`
- Produces (ai-agent):
  - `interface ChunkContent { chunkId: number; content: string }` (export)
  - `FireHubApiClient.listDocumentChunks(datasetId: number): Promise<ChunkContent[]>`

- [ ] **Step 1: Java 통합 실패 테스트 작성**

```java
// apps/firehub-api/src/test/java/com/smartfirehub/document/controller/DocumentChunkControllerTest.java
package com.smartfirehub.document.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.support.IntegrationTestBase; // 기존 통합 테스트 베이스 경로에 맞춤
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** 청크 bulk-read 엔드포인트의 인증·200 응답을 검증한다. */
class DocumentChunkControllerTest extends IntegrationTestBase {
  @Autowired MockMvc mockMvc;

  @Test
  void 청크목록_엔드포인트는_인증된_요청에_200을_반환한다() throws Exception {
    // IntegrationTestBase가 제공하는 인증 헬퍼로 dataset:read 권한 요청 생성(프로젝트 관례에 맞춰 헤더/토큰 주입)
    mockMvc.perform(get("/api/v1/datasets/{datasetId}/document-chunks", 1L)
            .with(authenticated())) // 프로젝트의 기존 인증 테스트 헬퍼명에 맞춰 조정
        .andExpect(status().isOk());
  }
}
```
> 주의: `IntegrationTestBase` 실제 패키지·인증 헬퍼명은 기존 `DocumentChunkRepositoryTest`를 참고해 정확히 맞춘다.

- [ ] **Step 2: 실패 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.document.controller.DocumentChunkControllerTest"`
Expected: FAIL — 컴파일 에러(컨트롤러/ DTO 없음) 또는 404

- [ ] **Step 3: DTO + 컨트롤러 구현**

```java
// apps/firehub-api/src/main/java/com/smartfirehub/document/dto/ChunkContentResponse.java
package com.smartfirehub.document.dto;

/** 데이터셋 청크 bulk-read 응답 — 내부 GraphRAG 추출용. */
public record ChunkContentResponse(Long chunkId, String content) {}
```

```java
// apps/firehub-api/src/main/java/com/smartfirehub/document/controller/DocumentChunkController.java
package com.smartfirehub.document.controller;

import com.smartfirehub.document.dto.ChunkContentResponse;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.global.security.RequirePermission;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 데이터셋의 문서 청크 전체를 반환하는 내부 bulk-read 엔드포인트(GraphRAG 추출 원천). */
@RestController
@RequestMapping("/api/v1/datasets/{datasetId}/document-chunks")
@RequiredArgsConstructor
public class DocumentChunkController {

  private final DocumentChunkRepository chunkRepository;

  /** datasetId의 모든 청크를 (chunkId, content)로 반환한다. 페이지네이션 없음(스켈레톤 범위). */
  @GetMapping
  @RequirePermission("dataset:read")
  public List<ChunkContentResponse> list(@PathVariable Long datasetId) {
    return chunkRepository.findChunkContentsByDataset(datasetId).stream()
        .map(c -> new ChunkContentResponse(c.chunkId(), c.content()))
        .toList();
  }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.document.controller.DocumentChunkControllerTest"`
Expected: PASS

- [ ] **Step 5: ai-agent 클라이언트 실패 테스트 작성 (nock)**

```ts
// apps/firehub-ai-agent/src/mcp/api-client/graph-source-api.test.ts
import { describe, it, expect, afterEach } from 'vitest';
import nock from 'nock';
import axios from 'axios';
import { createGraphSourceApi } from './graph-source-api';

afterEach(() => nock.cleanAll());

describe('graph-source-api', () => {
  it('listDocumentChunks는 datasetId 청크 배열을 반환한다', async () => {
    nock('http://api.test').get('/datasets/7/document-chunks')
      .reply(200, [{ chunkId: 1, content: 'a' }, { chunkId: 2, content: 'b' }]);
    const client = axios.create({ baseURL: 'http://api.test' });
    const api = createGraphSourceApi(client);
    const chunks = await api.listDocumentChunks(7);
    expect(chunks).toEqual([{ chunkId: 1, content: 'a' }, { chunkId: 2, content: 'b' }]);
  });
});
```

- [ ] **Step 6: 실패 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/mcp/api-client/graph-source-api.test.ts`
Expected: FAIL — `Cannot find module './graph-source-api'`

- [ ] **Step 7: 클라이언트 팩토리 구현 + 위임 등록**

```ts
// apps/firehub-ai-agent/src/mcp/api-client/graph-source-api.ts
// GraphRAG 추출 원천(청크 bulk-read) API. 기존 createXxxApi 팩토리 패턴을 따른다.
import type { AxiosInstance } from 'axios';

export interface ChunkContent { chunkId: number; content: string; }

export function createGraphSourceApi(client: AxiosInstance) {
  return {
    // datasetId의 모든 문서 청크를 반환한다.
    async listDocumentChunks(datasetId: number): Promise<ChunkContent[]> {
      return (await client.get(`/datasets/${datasetId}/document-chunks`)).data;
    },
  };
}
```

`apps/firehub-ai-agent/src/mcp/api-client.ts` 수정 — 생성자에서 팩토리 생성 + public 위임 메서드 추가(기존 `this._xxx = createXxxApi(this.client)` 패턴, 116-129행 인근):

```ts
// import 추가
import { createGraphSourceApi, ChunkContent } from './api-client/graph-source-api';
export type { ChunkContent };

// 생성자 내 (다른 _xxx 초기화 옆)
this._graphSource = createGraphSourceApi(this.client);

// 필드 선언부
private _graphSource: ReturnType<typeof createGraphSourceApi>;

// public 위임 메서드
/** GraphRAG 추출용 — datasetId의 문서 청크 전체를 가져온다. */
listDocumentChunks(datasetId: number): Promise<ChunkContent[]> {
  return this._graphSource.listDocumentChunks(datasetId);
}
```

- [ ] **Step 8: 통과 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/mcp/api-client/graph-source-api.test.ts` (PASS)
Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent typecheck` (PASS)

- [ ] **Step 9: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/document/controller/DocumentChunkController.java apps/firehub-api/src/main/java/com/smartfirehub/document/dto/ChunkContentResponse.java apps/firehub-api/src/test/java/com/smartfirehub/document/controller/DocumentChunkControllerTest.java
git add -f apps/firehub-ai-agent/src/mcp/api-client/graph-source-api.ts apps/firehub-ai-agent/src/mcp/api-client/graph-source-api.test.ts
git add apps/firehub-ai-agent/src/mcp/api-client.ts
git commit -m "feat(graphrag): 청크 bulk-read API 엔드포인트 + ai-agent 클라이언트"
```

---

### Task 7: Ingest 오케스트레이션 + graphrag_ingest MCP 도구

**Files:**
- Create: `apps/firehub-ai-agent/src/graphrag/ingest.ts`
- Test: `apps/firehub-ai-agent/src/graphrag/ingest.test.ts`
- Create: `apps/firehub-ai-agent/src/mcp/tools/graphrag-tools.ts`
- Modify: `apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts` (도구 등록 + 권한 맵)

**Interfaces:**
- Consumes: `FireHubApiClient.listDocumentChunks` (Task 6), `extractGraph` (Task 2), `resolveExtraction` (Task 3), `loadGraph` (Task 5)
- Produces:
  - `interface IngestSummary { datasetId: number; chunks: number; entities: number; relations: number }`
  - `async function ingestDataset(deps: IngestDeps, datasetId: number): Promise<IngestSummary>` where
    `interface IngestDeps { listChunks(id:number): Promise<{chunkId:number;content:string}[]>; extract(text:string): Promise<ExtractionResult>; load(graph:ResolvedGraph, chunkId:number): Promise<{nodes:number;relations:number}> }`
    — 의존성 주입 형태로 만들어 유닛 테스트에서 LLM/Neo4j 없이 검증.

- [ ] **Step 1: 실패 테스트 작성 (의존성 주입, 외부자원 없음)**

```ts
// apps/firehub-ai-agent/src/graphrag/ingest.test.ts
import { describe, it, expect, vi } from 'vitest';
import { ingestDataset } from './ingest';

describe('ingestDataset', () => {
  it('청크별 추출→해소→적재를 오케스트레이션하고 합계를 반환한다', async () => {
    const load = vi.fn().mockResolvedValue({ nodes: 2, relations: 1 });
    const deps = {
      listChunks: vi.fn().mockResolvedValue([
        { chunkId: 10, content: 'c1' }, { chunkId: 11, content: 'c2' },
      ]),
      extract: vi.fn().mockResolvedValue({
        entities: [{ type: 'Incident', name: 'A' }, { type: 'Cause', name: 'B' }],
        relations: [{ subject: 'A', type: 'CAUSED_BY', object: 'B' }],
      }),
      load,
    };
    const summary = await ingestDataset(deps as any, 7);
    expect(deps.extract).toHaveBeenCalledTimes(2);
    expect(load).toHaveBeenCalledWith(expect.anything(), 10);
    expect(load).toHaveBeenCalledWith(expect.anything(), 11);
    expect(summary).toEqual({ datasetId: 7, chunks: 2, entities: 4, relations: 2 });
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/graphrag/ingest.test.ts`
Expected: FAIL — `Cannot find module './ingest'`

- [ ] **Step 3: ingest 구현**

```ts
// apps/firehub-ai-agent/src/graphrag/ingest.ts
// 배치 오케스트레이션: 청크 bulk-read → 청크별 LLM 추출 → 해소 → Neo4j 적재.
// 의존성 주입으로 LLM/Neo4j를 분리해 유닛 테스트 가능하게 한다.
import { ExtractionResult } from './ontology';
import { resolveExtraction, ResolvedGraph } from './resolver';

export interface IngestDeps {
  listChunks(datasetId: number): Promise<{ chunkId: number; content: string }[]>;
  extract(text: string): Promise<ExtractionResult>;
  load(graph: ResolvedGraph, chunkId: number): Promise<{ nodes: number; relations: number }>;
}
export interface IngestSummary { datasetId: number; chunks: number; entities: number; relations: number; }

export async function ingestDataset(deps: IngestDeps, datasetId: number): Promise<IngestSummary> {
  const chunks = await deps.listChunks(datasetId);
  let entities = 0, relations = 0;
  for (const chunk of chunks) {
    const extraction = await deps.extract(chunk.content); // 추출 실패 시 extractor가 빈 결과 반환 → 계속
    const graph = resolveExtraction(extraction);
    const res = await deps.load(graph, chunk.chunkId);
    entities += res.nodes; relations += res.relations;
  }
  return { datasetId, chunks: chunks.length, entities, relations };
}
```

- [ ] **Step 4: 통과 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/graphrag/ingest.test.ts`
Expected: PASS

- [ ] **Step 5: graphrag_ingest MCP 도구 작성**

extractor의 model/apiKey는 `classification-service.ts`의 `getModelAndApiKey` 패턴과 동일하게 확보하되, 스켈레톤에서는 env 폴백(`AI_DEFAULT_MODEL`/`ANTHROPIC_API_KEY`)만 사용한다.

```ts
// apps/firehub-ai-agent/src/mcp/tools/graphrag-tools.ts
// GraphRAG 적재/질의 MCP 도구. register 규약: (apiClient, safeTool, jsonResult) => Tool[]
import { z } from 'zod/v4';
import { ingestDataset } from '../../graphrag/ingest.js';
import { extractGraph } from '../../graphrag/extractor.js';
import { loadGraph } from '../../graphrag/loader.js';
import { bootstrapConstraints } from '../../graphrag/neo4j-client.js';

export function registerGraphragTools(apiClient: any, safeTool: any, jsonResult: any) {
  const model = process.env.AI_DEFAULT_MODEL ?? 'claude-haiku-4-5';
  const apiKey = process.env.ANTHROPIC_API_KEY ?? '';

  return [
    safeTool(
      'graphrag_ingest',
      'DOCUMENT 데이터셋의 청크에서 엔티티/관계를 추출해 지식 그래프(Neo4j)에 적재한다. 관리/구축 목적으로만 사용.',
      { datasetId: z.number().describe('그래프로 적재할 DOCUMENT 데이터셋 ID') },
      async (args: { datasetId: number }) => {
        await bootstrapConstraints();
        const summary = await ingestDataset(
          {
            listChunks: (id) => apiClient.listDocumentChunks(id),
            extract: (text) => extractGraph(text, { model, apiKey }),
            load: (graph, chunkId) => loadGraph(graph, chunkId),
          },
          args.datasetId,
        );
        return jsonResult(summary);
      },
    ),
  ];
}
```

`firehub-mcp-server.ts` 수정:
- import 추가: `import { registerGraphragTools } from './tools/graphrag-tools.js';`
- `registerAllTools`의 배열(168-184)에 `...registerGraphragTools(apiClient, safeTool, jsonResult),` 추가
- `TOOL_PERMISSION_REQUIREMENTS`(108)에 `graphrag_ingest: 'dataset:write'` 추가(구축은 쓰기 권한)

- [ ] **Step 6: 타입체크 통과 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent typecheck`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add -f apps/firehub-ai-agent/src/graphrag/ingest.ts apps/firehub-ai-agent/src/graphrag/ingest.test.ts apps/firehub-ai-agent/src/mcp/tools/graphrag-tools.ts
git add apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts
git commit -m "feat(graphrag): ingest 오케스트레이션 + graphrag_ingest 도구"
```

---

### Task 8: Retriever (질문 → 시드 → 서브그래프 + 출처)

**Files:**
- Create: `apps/firehub-ai-agent/src/graphrag/retriever.ts`
- Test: `apps/firehub-ai-agent/src/graphrag/retriever.integration.test.ts`

**Interfaces:**
- Consumes: `FireHubApiClient.searchDocuments` (기존), `getSession` (Task 4)
- Produces:
  - `interface SubgraphNode { key: string; type: string; name: string }`
  - `interface SubgraphRelation { subject: string; type: string; object: string }`
  - `interface SourceChunk { chunkId: number; fileName: string; content: string }`
  - `interface RetrievalResult { nodes: SubgraphNode[]; relations: SubgraphRelation[]; sourceChunks: SourceChunk[] }`
  - `interface RetrieverDeps { searchDocuments(query:string, datasetIds?:number[], topK?:number, mode?:string): Promise<Array<{chunkId:number; fileName:string; content:string}>> }`
  - `async function retrieve(deps: RetrieverDeps, query: string, topK?: number): Promise<RetrievalResult>`
    — 1) searchDocuments로 시드 청크 확보 → 2) 그 chunkId를 sourceChunkIds에 포함하는 Entity에서 1~2홉 확장 → 3) 서브그래프 + 시드 청크 반환.

- [ ] **Step 1: 통합 테스트 작성 (Neo4j에 시드 그래프 주입 후 검증)**

```ts
// apps/firehub-ai-agent/src/graphrag/retriever.integration.test.ts
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { getSession, bootstrapConstraints, closeDriver } from './neo4j-client';
import { loadGraph } from './loader';
import { entityKey } from './resolver';
import { retrieve } from './retriever';

beforeAll(async () => {
  await bootstrapConstraints();
  const s = getSession();
  try { await s.run('MATCH (n:Entity) DETACH DELETE n'); } finally { await s.close(); }
  // 시드 그래프: 사건 2026-001 -(OCCURRED_AT)-> 건물, -(CAUSED_BY)-> 원인. 모두 chunkId 500에서 유래.
  await loadGraph({
    entities: [
      { key: entityKey('Incident', '2026-001'), type: 'Incident', name: '2026-001' },
      { key: entityKey('Building', '중앙로 상가'), type: 'Building', name: '중앙로 상가' },
      { key: entityKey('Cause', '전기적 요인'), type: 'Cause', name: '전기적 요인' },
    ],
    relations: [
      { subjectKey: entityKey('Incident', '2026-001'), type: 'OCCURRED_AT', objectKey: entityKey('Building', '중앙로 상가') },
      { subjectKey: entityKey('Incident', '2026-001'), type: 'CAUSED_BY', objectKey: entityKey('Cause', '전기적 요인') },
    ],
  }, 500);
});
afterAll(async () => { await closeDriver(); });

describe('retrieve (integration)', () => {
  it('시드 청크로부터 서브그래프와 출처를 회수한다', async () => {
    // searchDocuments를 스텁: chunkId 500 하나 반환.
    const deps = {
      searchDocuments: async () => [{ chunkId: 500, fileName: 'report-01.md', content: '중앙로 상가건물 화재...' }],
    };
    const result = await retrieve(deps, '중앙로 상가건물 화재의 원인은?');
    const names = result.nodes.map((n) => n.name).sort();
    expect(names).toContain('2026-001');
    expect(names).toContain('전기적 요인'); // 1홉 확장으로 원인 도달
    expect(result.relations.some((r) => r.type === 'CAUSED_BY')).toBe(true);
    expect(result.sourceChunks[0].fileName).toBe('report-01.md');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/graphrag/retriever.integration.test.ts`
Expected: FAIL — `Cannot find module './retriever'`

- [ ] **Step 3: retriever 구현**

```ts
// apps/firehub-ai-agent/src/graphrag/retriever.ts
// 질문 → 기존 벡터검색으로 시드 청크 확보 → 시드 유래 엔티티에서 1~2홉 확장 → 서브그래프+출처 조립.
// 의도적으로 얇게: 영리한 랭킹/요약 없음.
import { getSession } from './neo4j-client';

export interface SubgraphNode { key: string; type: string; name: string; }
export interface SubgraphRelation { subject: string; type: string; object: string; }
export interface SourceChunk { chunkId: number; fileName: string; content: string; }
export interface RetrievalResult { nodes: SubgraphNode[]; relations: SubgraphRelation[]; sourceChunks: SourceChunk[]; }

export interface RetrieverDeps {
  searchDocuments(query: string, datasetIds?: number[], topK?: number, mode?: string):
    Promise<Array<{ chunkId: number; fileName: string; content: string }>>;
}

export async function retrieve(deps: RetrieverDeps, query: string, topK = 8): Promise<RetrievalResult> {
  // 1) 시드: 기존 하이브리드 문서검색으로 관련 청크 확보.
  const hits = await deps.searchDocuments(query, undefined, topK, 'HYBRID');
  const chunkIds = hits.map((h) => h.chunkId);
  if (chunkIds.length === 0) return { nodes: [], relations: [], sourceChunks: [] };

  // 2) 시드 청크 유래 엔티티에서 최대 2홉 확장한 서브그래프.
  const session = getSession();
  try {
    const r = await session.run(
      `MATCH (seed:Entity) WHERE any(c IN seed.sourceChunkIds WHERE c IN $chunkIds)
       MATCH path = (seed)-[:REL*0..2]-(:Entity)
       WITH collect(path) AS paths
       UNWIND paths AS p
       WITH [n IN nodes(p) | {key:n.key, type:n.type, name:n.name}] AS ns,
            [rel IN relationships(p) | {subject: startNode(rel).name, type: rel.type, object: endNode(rel).name}] AS rs
       UNWIND ns AS n UNWIND (CASE WHEN size(rs)=0 THEN [null] ELSE rs END) AS r
       RETURN collect(DISTINCT n) AS nodes,
              collect(DISTINCT r) AS relations`,
      { chunkIds },
    );
    const rec = r.records[0];
    const nodes = (rec?.get('nodes') ?? []) as SubgraphNode[];
    const relations = ((rec?.get('relations') ?? []) as (SubgraphRelation | null)[]).filter((x): x is SubgraphRelation => x != null);
    return { nodes, relations, sourceChunks: hits };
  } finally { await session.close(); }
}
```

- [ ] **Step 4: 통과 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/graphrag/retriever.integration.test.ts`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add -f apps/firehub-ai-agent/src/graphrag/retriever.ts apps/firehub-ai-agent/src/graphrag/retriever.integration.test.ts
git commit -m "feat(graphrag): 시드→서브그래프 retriever"
```

---

### Task 9: graphrag_query MCP 도구 + 챗 라우팅 규칙

**Files:**
- Modify: `apps/firehub-ai-agent/src/mcp/tools/graphrag-tools.ts` (graphrag_query 추가)
- Modify: `apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts` (권한 맵)
- Modify: `apps/firehub-ai-agent/src/agent/system-prompt.ts` (라우팅 규칙)
- Test: `apps/firehub-ai-agent/src/mcp/tools/graphrag-tools.test.ts`

**Interfaces:**
- Consumes: `retrieve` (Task 8), `apiClient.searchDocuments` (기존)
- Produces: MCP 도구 `graphrag_query { query: string; topK?: number }` → `jsonResult({ subgraph:{nodes,relations}, sourceChunks })`

- [ ] **Step 1: 도구 단위 실패 테스트 작성 (retrieve 스텁)**

```ts
// apps/firehub-ai-agent/src/mcp/tools/graphrag-tools.test.ts
import { describe, it, expect, vi } from 'vitest';

vi.mock('../../graphrag/retriever.js', () => ({
  retrieve: vi.fn().mockResolvedValue({
    nodes: [{ key: 'Incident:x', type: 'Incident', name: 'X' }],
    relations: [{ subject: 'X', type: 'CAUSED_BY', object: 'Y' }],
    sourceChunks: [{ chunkId: 1, fileName: 'r.md', content: '...' }],
  }),
}));

import { registerGraphragTools } from './graphrag-tools';

describe('graphrag_query 도구', () => {
  it('retrieve 결과를 jsonResult로 반환한다', async () => {
    const jsonResult = (data: any) => ({ content: [{ type: 'text', text: JSON.stringify(data) }] });
    const safeTool = (_n: string, _d: string, _s: any, handler: any) => ({ name: _n, handler });
    const apiClient = { searchDocuments: vi.fn() };
    const tools: any[] = registerGraphragTools(apiClient, safeTool, jsonResult);
    const query = tools.find((t) => t.name === 'graphrag_query');
    const out = await query.handler({ query: '원인?' });
    const payload = JSON.parse(out.content[0].text);
    expect(payload.subgraph.nodes[0].name).toBe('X');
    expect(payload.sourceChunks[0].fileName).toBe('r.md');
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/mcp/tools/graphrag-tools.test.ts`
Expected: FAIL — `graphrag_query` 미정의(find가 undefined)

- [ ] **Step 3: graphrag_query 도구 추가**

`graphrag-tools.ts`의 return 배열에 도구 추가 + retrieve import:

```ts
// 파일 상단 import 추가
import { retrieve } from '../../graphrag/retriever.js';

// return [ ... ] 배열에 아래 도구 추가
    safeTool(
      'graphrag_query',
      '엔티티 간 관계/연결/공통점/경로를 묻는 질문에 지식 그래프로 답한다. '
        + '반환된 subgraph 노드·관계와 sourceChunks의 fileName을 반드시 인용해 답하라.',
      {
        query: z.string().describe('관계·연결을 묻는 자연어 질문'),
        topK: z.number().min(1).max(20).optional().describe('시드 문서 검색 수(기본 8)'),
      },
      async (args: { query: string; topK?: number }) => {
        const result = await retrieve(
          { searchDocuments: (q, ids, k, mode) => apiClient.searchDocuments(q, ids, k, mode) },
          args.query, args.topK,
        );
        // Neo4j 연결 불가 등은 retrieve에서 throw → safeTool이 isError로 감싸 폴백 메시지 제공.
        return jsonResult({
          subgraph: { nodes: result.nodes, relations: result.relations },
          sourceChunks: result.sourceChunks,
        });
      },
    ),
```

`firehub-mcp-server.ts`의 `TOOL_PERMISSION_REQUIREMENTS`에 `graphrag_query: 'dataset:read'` 추가.

- [ ] **Step 4: 통과 확인**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test -- src/mcp/tools/graphrag-tools.test.ts`
Expected: PASS

- [ ] **Step 5: system-prompt 라우팅 규칙 추가**

`apps/firehub-ai-agent/src/agent/system-prompt.ts`에 도구 선택 규칙 문단 추가(기존 검색 도구 안내 인근):

```
## 지식 그래프(GraphRAG) 도구 선택 규칙
- 엔티티 간 **관계·연결·공통점·경로**를 묻는 질문(예: "여러 화재의 공통 발화원인", "A와 연관된 규정")
  → `graphrag_query` 사용. 반환된 노드/관계와 sourceChunks의 fileName을 인용해 답한다.
- 단일 데이터셋 조회·단순 문서검색은 기존 `find_datasets`/`search_documents`를 사용한다.
- 그래프가 비어있거나 연결 실패로 graphrag_query가 근거를 못 주면, 기존 문서검색으로 우회한다.
```

- [ ] **Step 6: 전체 유닛 테스트 + 타입체크**

Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent test` (통합 테스트는 `pnpm db:up` 필요; 유닛만 그린 확인)
Run: `pnpm --filter @smart-fire-hub/firehub-ai-agent typecheck`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add -f apps/firehub-ai-agent/src/mcp/tools/graphrag-tools.ts apps/firehub-ai-agent/src/mcp/tools/graphrag-tools.test.ts
git add apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts apps/firehub-ai-agent/src/agent/system-prompt.ts
git commit -m "feat(graphrag): graphrag_query 도구 + 챗 라우팅 규칙"
```

---

## End-to-End 수동 검증 (전 Task 후, 성공 기준 확인)

1. `pnpm db:up` (postgres + neo4j 기동)
2. 샘플 문서 6건을 DOCUMENT 데이터셋으로 업로드 — 기존 UI 또는 `POST /api/v1/datasets/{id}/documents`. 인제스션 완료(청크 생성) 대기.
3. 챗 또는 MCP로 `graphrag_ingest(datasetId)` 실행 → summary(chunks/entities/relations > 0) 확인.
4. Neo4j 브라우저(http://localhost:7474)에서 `MATCH (n:Entity) RETURN n LIMIT 50`으로 그래프 육안 확인.
5. 챗에서 대표 질문 3개 질의:
   - "중앙로 상가건물에서 발생한 화재들의 공통 발화원인은?"
   - "전기적 요인으로 발생한 화재에서 반복 위반된 규정은?"
   - "스프링클러 미작동이 피해 규모와 어떻게 연관됐나?"
   → 답변이 그래프 노드/관계 + 출처 fileName을 인용하면 **skeleton 성공**.
6. (품질 게이트, 후속) 실제 문서로 동일 루프를 돌려 GraphRAG 답변 품질 우위를 검증한 뒤에만 ②③ 확장 착수.

---

## Self-Review 결과

- **Spec 커버리지:** 온톨로지(T1)·샘플(T1)·추출(T2)·해소(T3)·Neo4j인프라(T4)·적재(T5)·bulk-read(T6)·ingest+트리거(T7)·retriever(T8)·graphrag_query+라우팅(T9)·에러처리(T2 빈결과/T9 safeTool 폴백)·테스트(각 Task TDD) 모두 매핑됨. 리스크 순 구축(T2 검수 게이트 → T4/5 배선)도 반영.
- **플레이스홀더:** 없음(각 Step 실제 코드/명령). 단 T6 Java 테스트의 `IntegrationTestBase` 패키지·인증 헬퍼명은 기존 `DocumentChunkRepositoryTest` 참고해 실행자가 정합 — 그 지점 명시.
- **타입 일관성:** `ExtractionResult/ResolvedGraph/ChunkContent/RetrievalResult` 시그니처가 T1→T9에서 일치. `entityKey`·`loadGraph(graph, chunkId)`·`retrieve(deps, query, topK)` 호출부 일치 확인.
- **범위:** 단일 수직 슬라이스로 충분히 좁음. TABLE 통합·자동 재적재·전용 UI·품질 게이트는 명시적으로 범위 밖.
