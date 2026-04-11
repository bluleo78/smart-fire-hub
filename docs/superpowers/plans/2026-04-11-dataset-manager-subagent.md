# dataset-manager Subagent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Smart Fire Hub AI 챗에서 데이터셋 도메인(생성·수정·삭제·컬럼 변경·CSV 임포트)을 전담하는 `dataset-manager` 서브에이전트를 신설하고, 부족한 MCP 도구(삭제·컬럼·임포트·참조 조회)를 함께 추가한다.

**Architecture:** 기존 서브에이전트 패턴(pipeline-builder) 그대로. `apps/firehub-ai-agent/src/agent/subagents/dataset-manager/` 디렉터리에 `agent.md`/`rules.md`/`examples.md` 생성. MCP 도구는 `dataset-tools.ts`·`data-tools.ts`·`misc-tools.ts`에 추가하거나 신규 `dataimport-tools.ts` 생성. 백엔드는 `get_dataset_references` 전용 엔드포인트만 신설, 나머지는 기존 API 재사용. 권한 기반 도구 필터링은 `firehub-mcp-server.ts`의 도구 등록 지점에서 수행.

**Tech Stack:** TypeScript(Node.js, ESM), Claude Agent SDK, Zod v4, Vitest + nock (ai-agent) / Java Spring Boot + jOOQ, JUnit IntegrationTestBase (api) / React + Playwright (web).

**Spec:** `docs/superpowers/specs/2026-04-11-dataset-manager-subagent-design.md`

---

## File Structure

### New files
- `apps/firehub-ai-agent/src/agent/subagents/dataset-manager/agent.md` — 역할·위임 정책·파괴 작업 게이팅·GIS 가이드
- `apps/firehub-ai-agent/src/agent/subagents/dataset-manager/rules.md` — 타입 매핑·GIS 감지·체크리스트
- `apps/firehub-ai-agent/src/agent/subagents/dataset-manager/examples.md` — 대화 샘플
- `apps/firehub-ai-agent/src/mcp/tools/dataimport-tools.ts` — 임포트 MCP 도구 5종
- `apps/firehub-ai-agent/src/mcp/tools/dataimport-tools.test.ts` — 임포트 도구 테스트
- `apps/firehub-api/src/main/java/com/smartfirehub/dataset/dto/DatasetReferencesResponse.java` — 참조 집계 DTO
- `apps/firehub-api/src/test/java/com/smartfirehub/dataset/service/DatasetReferencesServiceTest.java` — 참조 조회 테스트
- `apps/firehub-web/e2e/pages/ai-chat/dataset-manager.spec.ts` — E2E 시나리오 (생성·삭제)

### Modified files
- `apps/firehub-ai-agent/src/mcp/tools/dataset-tools.ts` — `delete_dataset`, `add_dataset_column`, `drop_dataset_column`, `get_dataset_references` 추가
- `apps/firehub-ai-agent/src/mcp/tools/dataset-tools.test.ts` — 신규 도구 테스트 추가 (없으면 생성)
- `apps/firehub-ai-agent/src/mcp/api-client.ts` — 신규 엔드포인트 메서드 추가
- `apps/firehub-ai-agent/src/mcp/api-client.test.ts` — 메서드 테스트
- `apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts` — 신규 도구 등록 + 권한 기반 필터링
- `apps/firehub-ai-agent/src/mcp/firehub-mcp-server.test.ts` — 권한 필터 테스트
- `apps/firehub-ai-agent/src/agent/system-prompt.ts` — 신규 도구 설명 추가
- `apps/firehub-ai-agent/src/agent/subagent-loader.test.ts` — dataset-manager 로딩 검증
- `apps/firehub-api/src/main/java/com/smartfirehub/dataset/controller/DatasetController.java` — `GET /{id}/references` 엔드포인트 추가
- `apps/firehub-api/src/main/java/com/smartfirehub/dataset/service/DatasetService.java` — `getReferences()` 메서드 추가
- `docs/ROADMAP.md` — Phase 5.10 신설 + 본 작업 체크리스트

---

## Task 1: ROADMAP.md에 Phase 5.10 추가

**Files:**
- Modify: `docs/ROADMAP.md` (진행 현황 요약 테이블 + 새 Phase 섹션 추가)

- [ ] **Step 1: 진행 현황 요약 테이블에 Phase 5.10 행 추가**

`docs/ROADMAP.md`의 요약 테이블(Phase 5.9 행 다음)에 다음 행을 추가한다:

```markdown
| [Phase 5.10](#phase-510-ai-챗-데이터-플랫폼-전면-제어) | **진행 중** | 1/7 | 데이터셋 전담 서브에이전트 + 후속 에이전트 로드맵 |
```

- [ ] **Step 2: Phase 5.9 섹션 다음에 Phase 5.10 섹션 추가**

`## Phase 5.9` 섹션 뒤, `## Phase 6` 앞에 삽입:

```markdown
## Phase 5.10: AI 챗 데이터 플랫폼 전면 제어

> **의존**: Phase 5.8 (서브에이전트 시스템), Phase 5.9 완료
> **목표**: AI 챗 한 곳에서 데이터셋·분석·연동·트리거·대시보드·운영 전부 제어 가능

- ⬜ **5.10.1 dataset-manager 서브에이전트** — 데이터셋 생성/수정/삭제/컬럼/CSV 임포트 + GIS 자동 감지 (본 계획)
- ⬜ 5.10.2 data-analyst 서브에이전트 — 자연어 → SQL 분석/EDA/해석 (별도 스펙)
- ⬜ 5.10.3 api-connection-manager 서브에이전트 — 외부 API 커넥션 설계/등록/테스트
- ⬜ 5.10.4 trigger-manager (또는 pipeline-builder 확장) — 스케줄/웹훅/체인 트리거 대화형 설정
- ⬜ 5.10.5 dashboard-builder 서브에이전트 — 대시보드/위젯/필터 대화형 구성
- ⬜ 5.10.6 admin-manager 서브에이전트 — 사용자/권한 관리(권한 게이팅 강화)
- ⬜ 5.10.7 audit-analyst 서브에이전트 — 감사 로그/운영 모니터링/이상 탐지
```

- [ ] **Step 3: 커밋**

```bash
git add docs/ROADMAP.md
git commit -m "docs(roadmap): Phase 5.10 AI 챗 데이터 플랫폼 전면 제어 추가"
```

---

## Task 2: 백엔드 `get_dataset_references` 서비스 + 테스트

데이터셋 삭제 전 영향 범위(파이프라인/대시보드/스마트잡 참조 개수·이름 목록)를 반환하는 API를 신설한다.

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/dataset/dto/DatasetReferencesResponse.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/dataset/service/DatasetService.java`
- Create: `apps/firehub-api/src/test/java/com/smartfirehub/dataset/service/DatasetReferencesServiceTest.java`

- [ ] **Step 1: DTO 생성**

`DatasetReferencesResponse.java`:
```java
package com.smartfirehub.dataset.dto;

import java.util.List;

/**
 * 데이터셋을 참조하는 자원 집계 응답. 삭제 전 영향 범위 확인 용도로 사용한다.
 */
public record DatasetReferencesResponse(
    long datasetId,
    List<ReferenceItem> pipelines,
    List<ReferenceItem> dashboards,
    List<ReferenceItem> proactiveJobs,
    int totalCount) {

  public record ReferenceItem(long id, String name) {}
}
```

- [ ] **Step 2: 테스트 먼저 작성 (실패)**

`DatasetReferencesServiceTest.java`:
```java
package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.DatasetReferencesResponse;
import com.smartfirehub.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DatasetReferencesServiceTest extends IntegrationTestBase {

  @Autowired DatasetService datasetService;

  @Test
  void getReferences_noReferences_returnsEmptyCounts() {
    // 준비: 아무도 참조하지 않는 데이터셋 (테스트 fixture 활용)
    long datasetId = TestFixtures.createDataset(datasetService, "standalone_ds");

    DatasetReferencesResponse result = datasetService.getReferences(datasetId);

    assertThat(result.datasetId()).isEqualTo(datasetId);
    assertThat(result.pipelines()).isEmpty();
    assertThat(result.dashboards()).isEmpty();
    assertThat(result.proactiveJobs()).isEmpty();
    assertThat(result.totalCount()).isZero();
  }

  @Test
  void getReferences_withPipelineReference_returnsPipelineList() {
    long datasetId = TestFixtures.createDataset(datasetService, "referenced_ds");
    long pipelineId = TestFixtures.createPipelineUsingDataset(datasetId, "daily_import");

    DatasetReferencesResponse result = datasetService.getReferences(datasetId);

    assertThat(result.pipelines()).extracting(DatasetReferencesResponse.ReferenceItem::id).contains(pipelineId);
    assertThat(result.totalCount()).isEqualTo(1);
  }
}
```

> 실제 `TestFixtures` 유틸리티가 이 용도에 없을 수 있다. 없으면 기존 `@BeforeEach` 직접 세팅 방식 유지 (IntegrationTestBase 패턴 따름).

- [ ] **Step 3: 실패 확인**

Run:
```bash
cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.dataset.service.DatasetReferencesServiceTest"
```
Expected: FAIL (`getReferences` 메서드 없음)

- [ ] **Step 4: `DatasetService.getReferences()` 구현**

`DatasetService.java`에 추가:
```java
/**
 * 데이터셋이 참조되고 있는 파이프라인/대시보드/스마트잡을 집계한다.
 * 삭제 전 영향 범위를 사용자에게 고지하기 위해 사용한다.
 */
public DatasetReferencesResponse getReferences(Long datasetId) {
  // 존재 검증
  datasetRepository.findById(datasetId)
      .orElseThrow(() -> new DatasetNotFoundException(datasetId));

  // 파이프라인 참조: pipeline_step에 input_dataset_id / output_dataset_id 컬럼 사용
  List<DatasetReferencesResponse.ReferenceItem> pipelines =
      pipelineRepository.findByReferencedDatasetId(datasetId).stream()
          .map(p -> new DatasetReferencesResponse.ReferenceItem(p.id(), p.name()))
          .toList();

  // 대시보드 참조: dashboard_widget.config JSONB에서 datasetId 필드 추출
  List<DatasetReferencesResponse.ReferenceItem> dashboards =
      dashboardRepository.findByWidgetDatasetId(datasetId).stream()
          .map(d -> new DatasetReferencesResponse.ReferenceItem(d.id(), d.name()))
          .toList();

  // 스마트잡 참조: proactive_job.config JSONB에서 datasetId 필드 추출
  List<DatasetReferencesResponse.ReferenceItem> jobs =
      proactiveJobRepository.findByDatasetId(datasetId).stream()
          .map(j -> new DatasetReferencesResponse.ReferenceItem(j.id(), j.name()))
          .toList();

  int total = pipelines.size() + dashboards.size() + jobs.size();
  return new DatasetReferencesResponse(datasetId, pipelines, dashboards, jobs, total);
}
```

> `findByReferencedDatasetId`, `findByWidgetDatasetId`, `findByDatasetId`가 기존 Repository에 없으면 같은 Task에서 추가한다. 쿼리는 jOOQ DSLContext로 작성한다 (CLAUDE.md 규약).

- [ ] **Step 5: 통과 확인**

Run: `./gradlew test --tests "com.smartfirehub.dataset.service.DatasetReferencesServiceTest"`
Expected: PASS (2 tests)

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/dataset/ apps/firehub-api/src/test/java/com/smartfirehub/dataset/service/DatasetReferencesServiceTest.java
git commit -m "feat(api): 데이터셋 참조 집계 서비스 추가"
```

---

## Task 3: 백엔드 `/datasets/{id}/references` REST 엔드포인트

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/dataset/controller/DatasetController.java`
- Create: `apps/firehub-api/src/test/java/com/smartfirehub/dataset/controller/DatasetReferencesControllerTest.java`

- [ ] **Step 1: 테스트 작성**

`DatasetReferencesControllerTest.java` — MockMvc로 `GET /api/v1/datasets/{id}/references` 호출, 200 및 응답 스키마 검증.

```java
@Test
@WithMockUser
void getReferences_returnsAggregatedCounts() throws Exception {
  long datasetId = /* fixture 생성 */;
  mockMvc.perform(get("/api/v1/datasets/{id}/references", datasetId))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.datasetId").value(datasetId))
      .andExpect(jsonPath("$.pipelines").isArray())
      .andExpect(jsonPath("$.totalCount").isNumber());
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew test --tests "DatasetReferencesControllerTest"`
Expected: FAIL (404)

- [ ] **Step 3: 컨트롤러 엔드포인트 추가**

`DatasetController.java`에 삽입:
```java
/** 데이터셋을 참조하는 파이프라인/대시보드/스마트잡 집계. 삭제 전 영향 범위 확인용. */
@GetMapping("/{id}/references")
@RequirePermission("dataset:read")
public ResponseEntity<DatasetReferencesResponse> getReferences(@PathVariable Long id) {
  return ResponseEntity.ok(datasetService.getReferences(id));
}
```

- [ ] **Step 4: 통과 확인**

Run: `./gradlew test --tests "DatasetReferencesControllerTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/dataset/controller/DatasetController.java apps/firehub-api/src/test/java/com/smartfirehub/dataset/controller/DatasetReferencesControllerTest.java
git commit -m "feat(api): GET /datasets/{id}/references 엔드포인트 추가"
```

---

## Task 4: MCP `delete_dataset` 도구

**Files:**
- Modify: `apps/firehub-ai-agent/src/mcp/api-client.ts`
- Modify: `apps/firehub-ai-agent/src/mcp/api-client.test.ts`
- Modify: `apps/firehub-ai-agent/src/mcp/tools/dataset-tools.ts`
- Create/Modify: `apps/firehub-ai-agent/src/mcp/tools/dataset-tools.test.ts`

- [ ] **Step 1: api-client 테스트 추가**

`api-client.test.ts`에 추가:
```typescript
describe('deleteDataset', () => {
  it('calls DELETE /datasets/{id}', async () => {
    nock(BASE_URL).delete('/datasets/42').reply(204);
    await expect(client.deleteDataset(42)).resolves.toBeUndefined();
  });
});
```

- [ ] **Step 2: api-client 메서드 구현**

`api-client.ts`에 추가:
```typescript
/** 데이터셋 삭제. data 스키마의 물리 테이블도 함께 DROP된다. */
async deleteDataset(id: number): Promise<void> {
  await this.http.delete(`/datasets/${id}`);
}
```

- [ ] **Step 3: 테스트 통과 확인**

Run: `pnpm test -- src/mcp/api-client.test.ts -t deleteDataset`
Expected: PASS

- [ ] **Step 4: `dataset-tools.ts`에 `delete_dataset` 등록**

`registerDatasetTools` 배열에 추가:
```typescript
safeTool(
  'delete_dataset',
  '데이터셋을 삭제합니다. 물리 테이블과 모든 데이터가 영구 제거됩니다. 사용자의 명시적 평문 확인 없이는 호출하지 마세요.',
  {
    id: z.number().describe('삭제할 데이터셋 ID'),
  },
  async (args: { id: number }) => {
    await apiClient.deleteDataset(args.id);
    return jsonResult({ success: true, datasetId: args.id });
  },
),
```

- [ ] **Step 5: 도구 테스트 작성**

`dataset-tools.test.ts` (없으면 생성):
```typescript
import { describe, it, expect, vi } from 'vitest';
import { registerDatasetTools } from './dataset-tools.js';

describe('delete_dataset tool', () => {
  it('calls apiClient.deleteDataset with the given id', async () => {
    const apiClient = { deleteDataset: vi.fn().mockResolvedValue(undefined) } as any;
    const tools = registerDatasetTools(apiClient, fakeSafeTool, fakeJsonResult);
    const deleteTool = tools.find((t) => t.name === 'delete_dataset')!;
    await deleteTool.handler({ id: 7 });
    expect(apiClient.deleteDataset).toHaveBeenCalledWith(7);
  });
});
```

(fakeSafeTool / fakeJsonResult 헬퍼는 기존 테스트 파일의 패턴 재사용)

- [ ] **Step 6: 테스트 통과 확인**

Run: `pnpm test -- src/mcp/tools/dataset-tools.test.ts`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add apps/firehub-ai-agent/src/mcp/api-client.ts apps/firehub-ai-agent/src/mcp/api-client.test.ts apps/firehub-ai-agent/src/mcp/tools/dataset-tools.ts apps/firehub-ai-agent/src/mcp/tools/dataset-tools.test.ts
git commit -m "feat(ai-agent): delete_dataset MCP 도구 추가"
```

---

## Task 5: MCP 컬럼 도구 (`add_dataset_column`, `drop_dataset_column`)

백엔드 API는 이미 존재한다 (`POST /datasets/{id}/columns`, `DELETE /datasets/{id}/columns/{columnId}`).

**Files:**
- Modify: `apps/firehub-ai-agent/src/mcp/api-client.ts` (+ `.test.ts`)
- Modify: `apps/firehub-ai-agent/src/mcp/tools/dataset-tools.ts` (+ `.test.ts`)

- [ ] **Step 1: api-client 테스트**

```typescript
describe('addDatasetColumn / dropDatasetColumn', () => {
  it('POSTs /datasets/{id}/columns', async () => {
    nock(BASE_URL).post('/datasets/42/columns').reply(200, { id: 99, columnName: 'lat' });
    const result = await client.addDatasetColumn(42, {
      columnName: 'lat',
      displayName: '위도',
      dataType: 'DECIMAL',
      isNullable: true,
    });
    expect(result.id).toBe(99);
  });

  it('DELETEs /datasets/{id}/columns/{columnId}', async () => {
    nock(BASE_URL).delete('/datasets/42/columns/99').reply(204);
    await expect(client.dropDatasetColumn(42, 99)).resolves.toBeUndefined();
  });
});
```

- [ ] **Step 2: api-client 구현**

```typescript
async addDatasetColumn(datasetId: number, column: DatasetColumnInput): Promise<DatasetColumnResponse> {
  const { data } = await this.http.post(`/datasets/${datasetId}/columns`, column);
  return data;
}

async dropDatasetColumn(datasetId: number, columnId: number): Promise<void> {
  await this.http.delete(`/datasets/${datasetId}/columns/${columnId}`);
}
```

`DatasetColumnInput` / `DatasetColumnResponse` 타입은 기존 `create_dataset`의 컬럼 타입과 통일 (이미 존재하면 재사용).

- [ ] **Step 3: 통과 확인**

Run: `pnpm test -- src/mcp/api-client.test.ts -t 'addDatasetColumn|dropDatasetColumn'`
Expected: PASS

- [ ] **Step 4: MCP 도구 등록**

`dataset-tools.ts`에 추가:
```typescript
safeTool(
  'add_dataset_column',
  '데이터셋에 컬럼을 추가합니다. GEOMETRY 컬럼의 경우 SRID 4326을 권장합니다.',
  {
    datasetId: z.number().describe('데이터셋 ID'),
    columnName: z.string().describe('컬럼 이름 ([a-z][a-z0-9_]* 패턴)'),
    displayName: z.string().describe('표시 이름'),
    dataType: z.string().describe('데이터 타입 (TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, TIMESTAMP, VARCHAR, GEOMETRY)'),
    maxLength: z.number().optional().describe('VARCHAR 최대 길이'),
    isNullable: z.boolean().optional().describe('NULL 허용 여부 (기본값: true)'),
    isIndexed: z.boolean().optional().describe('인덱스 생성 여부'),
    description: z.string().optional(),
  },
  async (args) => {
    const { datasetId, ...column } = args;
    const result = await apiClient.addDatasetColumn(datasetId, column);
    return jsonResult(result);
  },
),

safeTool(
  'drop_dataset_column',
  '데이터셋에서 컬럼을 제거합니다. 해당 컬럼의 모든 데이터가 영구 삭제됩니다. 사용자의 명시적 평문 확인 없이 호출하지 마세요.',
  {
    datasetId: z.number().describe('데이터셋 ID'),
    columnId: z.number().describe('컬럼 ID'),
  },
  async (args: { datasetId: number; columnId: number }) => {
    await apiClient.dropDatasetColumn(args.datasetId, args.columnId);
    return jsonResult({ success: true });
  },
),
```

- [ ] **Step 5: 도구 테스트 작성**

`dataset-tools.test.ts`에 두 도구 각각 mock apiClient 호출 검증 추가. Task 4와 같은 패턴.

- [ ] **Step 6: 통과 확인**

Run: `pnpm test -- src/mcp/tools/dataset-tools.test.ts`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add apps/firehub-ai-agent/src/mcp/
git commit -m "feat(ai-agent): add_dataset_column/drop_dataset_column MCP 도구 추가"
```

---

## Task 6: MCP `get_dataset_references` 도구

**Files:**
- Modify: `apps/firehub-ai-agent/src/mcp/api-client.ts` (+ `.test.ts`)
- Modify: `apps/firehub-ai-agent/src/mcp/tools/dataset-tools.ts` (+ `.test.ts`)

- [ ] **Step 1: api-client 테스트**

```typescript
it('getDatasetReferences calls GET /datasets/{id}/references', async () => {
  nock(BASE_URL)
    .get('/datasets/42/references')
    .reply(200, {
      datasetId: 42,
      pipelines: [{ id: 1, name: 'daily_import' }],
      dashboards: [],
      proactiveJobs: [],
      totalCount: 1,
    });

  const result = await client.getDatasetReferences(42);
  expect(result.totalCount).toBe(1);
  expect(result.pipelines[0].name).toBe('daily_import');
});
```

- [ ] **Step 2: 메서드 구현**

```typescript
async getDatasetReferences(id: number): Promise<DatasetReferences> {
  const { data } = await this.http.get(`/datasets/${id}/references`);
  return data;
}
```

타입 `DatasetReferences`:
```typescript
export interface DatasetReferences {
  datasetId: number;
  pipelines: Array<{ id: number; name: string }>;
  dashboards: Array<{ id: number; name: string }>;
  proactiveJobs: Array<{ id: number; name: string }>;
  totalCount: number;
}
```

- [ ] **Step 3: 통과 확인**

Run: `pnpm test -- src/mcp/api-client.test.ts -t getDatasetReferences`
Expected: PASS

- [ ] **Step 4: MCP 도구 등록**

```typescript
safeTool(
  'get_dataset_references',
  '데이터셋을 참조하는 파이프라인/대시보드/스마트잡을 조회합니다. 삭제 전 영향 범위 확인 필수.',
  { id: z.number().describe('데이터셋 ID') },
  async (args: { id: number }) => {
    const result = await apiClient.getDatasetReferences(args.id);
    return jsonResult(result);
  },
),
```

- [ ] **Step 5: 도구 테스트 작성 및 통과 확인**

Run: `pnpm test -- src/mcp/tools/dataset-tools.test.ts`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-ai-agent/src/mcp/
git commit -m "feat(ai-agent): get_dataset_references MCP 도구 추가"
```

---

## Task 7: MCP 임포트 도구군 (`preview_csv`, `validate_import`, `start_import`, `import_status`)

백엔드 기존 API:
- `POST /imports/preview` — 파일 업로드 + 첫 N행 + 컬럼 타입 추론
- `POST /imports/validate` — 매핑 스키마로 행 검증
- `POST /imports` — 적재 시작 (jobId 반환)
- `GET /imports/{importId}` — 상태 조회

**Files:**
- Create: `apps/firehub-ai-agent/src/mcp/tools/dataimport-tools.ts`
- Create: `apps/firehub-ai-agent/src/mcp/tools/dataimport-tools.test.ts`
- Modify: `apps/firehub-ai-agent/src/mcp/api-client.ts` (+ `.test.ts`)
- Modify: `apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts` (등록)

- [ ] **Step 1: api-client 메서드 4종 + 테스트**

api-client.ts에 추가:
```typescript
async previewImport(fileId: number): Promise<ImportPreviewResponse> {
  const { data } = await this.http.post('/imports/preview', { fileId });
  return data;
}

async validateImport(request: ValidateImportRequest): Promise<ValidateImportResponse> {
  const { data } = await this.http.post('/imports/validate', request);
  return data;
}

async startImport(request: StartImportRequest): Promise<{ importId: string }> {
  const { data } = await this.http.post('/imports', request);
  return data;
}

async getImportStatus(importId: string): Promise<ImportStatusResponse> {
  const { data } = await this.http.get(`/imports/${importId}`);
  return data;
}
```

타입 4종은 백엔드 DTO(`ImportPreviewResponse` 등)를 그대로 미러링. 실제 필드는 `DataImportController` 반환 타입 확인하여 채운다 — 구현 전에 읽는다.

nock 테스트 각 메서드당 1건 추가. 기존 `api-client.test.ts` 패턴 따름.

- [ ] **Step 2: 임포트 도구 파일 생성**

`dataimport-tools.ts`:
```typescript
import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

/**
 * CSV/XLSX 임포트 관련 MCP 도구들. dataset-manager 서브에이전트가 대화형으로
 * 미리보기→매핑→검증→적재→상태확인 흐름을 주도할 때 사용한다.
 */
export function registerDataImportTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    safeTool(
      'preview_csv',
      'CSV/XLSX 파일의 첫 N행과 컬럼 타입 추론 결과를 가져옵니다. 스키마 설계 대화의 출발점.',
      { fileId: z.number().describe('채팅에 첨부된 파일 ID') },
      async (args: { fileId: number }) => jsonResult(await apiClient.previewImport(args.fileId)),
    ),

    safeTool(
      'validate_import',
      '사용자가 확정한 매핑 스키마로 파일 행을 검증하고 에러 건수·샘플을 반환합니다.',
      {
        fileId: z.number(),
        datasetId: z.number().optional().describe('기존 데이터셋으로 임포트 시'),
        columnMapping: z
          .array(
            z.object({
              sourceColumn: z.string(),
              targetColumn: z.string(),
              dataType: z.string(),
            }),
          )
          .describe('컬럼 매핑 (source → target)'),
      },
      async (args) => jsonResult(await apiClient.validateImport(args)),
    ),

    safeTool(
      'start_import',
      '검증된 매핑으로 임포트 작업을 시작합니다. APPEND 또는 REPLACE 전략 선택. REPLACE는 파괴적이므로 사용자 평문 확인 필수.',
      {
        fileId: z.number(),
        datasetId: z.number(),
        columnMapping: z.array(
          z.object({
            sourceColumn: z.string(),
            targetColumn: z.string(),
            dataType: z.string(),
          }),
        ),
        loadStrategy: z.enum(['APPEND', 'REPLACE']).describe('적재 전략'),
      },
      async (args) => jsonResult(await apiClient.startImport(args)),
    ),

    safeTool(
      'import_status',
      '진행 중인 임포트 작업 상태를 조회합니다.',
      { importId: z.string().describe('임포트 작업 ID') },
      async (args: { importId: string }) => jsonResult(await apiClient.getImportStatus(args.importId)),
    ),
  ];
}
```

- [ ] **Step 3: 테스트 작성**

`dataimport-tools.test.ts` — 각 도구당 mock apiClient 호출 검증.

- [ ] **Step 4: `firehub-mcp-server.ts`에 등록**

기존 `registerDatasetTools(...)` 호출 이후 줄에 추가:
```typescript
import { registerDataImportTools } from './tools/dataimport-tools.js';
// ...
...registerDataImportTools(apiClient, safeTool, jsonResult),
```

- [ ] **Step 5: 통과 확인**

Run:
```bash
cd apps/firehub-ai-agent && pnpm test
```
Expected: 기존 테스트 + 신규 4+4건 PASS

- [ ] **Step 6: 커밋**

```bash
git add apps/firehub-ai-agent/src/mcp/
git commit -m "feat(ai-agent): CSV/XLSX 임포트 MCP 도구 4종 추가"
```

---

## Task 8: MCP 도구 권한 기반 필터링

**Files:**
- Modify: `apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts`
- Modify: `apps/firehub-ai-agent/src/mcp/firehub-mcp-server.test.ts`

- [ ] **Step 1: 테스트 먼저 작성**

```typescript
describe('destructive tool filtering', () => {
  it('excludes delete_dataset when user lacks dataset:delete permission', () => {
    const tools = buildFirehubMcpServer({
      apiClient: fakeClient,
      userPermissions: ['dataset:read', 'dataset:update'],
    }).tools;
    expect(tools.find((t) => t.name === 'delete_dataset')).toBeUndefined();
    expect(tools.find((t) => t.name === 'drop_dataset_column')).toBeUndefined();
  });

  it('includes destructive tools when permission present', () => {
    const tools = buildFirehubMcpServer({
      apiClient: fakeClient,
      userPermissions: ['dataset:read', 'dataset:delete'],
    }).tools;
    expect(tools.find((t) => t.name === 'delete_dataset')).toBeDefined();
    expect(tools.find((t) => t.name === 'drop_dataset_column')).toBeDefined();
  });
});
```

- [ ] **Step 2: 권한 매핑 구현**

`firehub-mcp-server.ts`에 상수 추가:
```typescript
/**
 * 도구별 필수 권한 맵. 해당 권한이 세션 사용자에게 없으면 도구가 노출되지 않는다.
 * 권한이 없는 도구는 아예 Claude Agent SDK의 allowedTools 목록에도 등장하지 않아,
 * 에이전트가 존재 자체를 인지하지 못한다 (가장 강한 게이팅).
 */
const TOOL_PERMISSION_REQUIREMENTS: Record<string, string> = {
  delete_dataset: 'dataset:delete',
  drop_dataset_column: 'dataset:delete',
  // REPLACE 임포트는 런타임 인수 검사이므로 별도 처리 (Task 9)
};

function filterToolsByPermissions(
  tools: Tool[],
  userPermissions: string[] | undefined,
): Tool[] {
  if (!userPermissions) return tools; // 권한 정보 없음 → 기본 허용(후방호환)
  return tools.filter((tool) => {
    const required = TOOL_PERMISSION_REQUIREMENTS[tool.name];
    if (!required) return true;
    return userPermissions.includes(required);
  });
}
```

`buildFirehubMcpServer` 시그니처에 `userPermissions?: string[]` 추가, 도구 배열 구성 후 `filterToolsByPermissions`로 감싸서 반환.

- [ ] **Step 3: 호출부 수정**

`agent-sdk.ts` 등에서 `buildFirehubMcpServer` 호출 시 세션 context에서 사용자 권한을 넘긴다. 권한은 `process-message.ts`가 firehub-api 호출로 획득 (신규 메서드 `apiClient.getSessionPermissions()` 필요 시 추가). 이 Task 범위에서는 **주입 지점만 만들고 실제 권한 전달은 Task 9에서 완성**.

- [ ] **Step 4: 통과 확인**

Run: `pnpm test -- src/mcp/firehub-mcp-server.test.ts`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts apps/firehub-ai-agent/src/mcp/firehub-mcp-server.test.ts
git commit -m "feat(ai-agent): 파괴 도구 권한 기반 필터링 추가"
```

---

## Task 9: 세션 권한 획득 + MCP 서버에 주입

**Files:**
- Modify: `apps/firehub-ai-agent/src/mcp/api-client.ts` (+ `.test.ts`)
- Modify: `apps/firehub-ai-agent/src/agent/agent-sdk.ts`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/auth/controller/AuthController.java` (또는 `UserController`)

- [ ] **Step 1: 백엔드에 `GET /auth/me/permissions` 엔드포인트가 있는지 확인**

Run: `grep -rn "getMyPermissions\|/me/permissions\|session.*permission" apps/firehub-api/src/main/java/com/smartfirehub/`

없으면 다음 Step, 있으면 Step 3으로.

- [ ] **Step 2: 백엔드 엔드포인트 추가 (없을 경우)**

`AuthController.java` (또는 `UserController.java`):
```java
/** 현재 세션 사용자의 권한 목록. ai-agent가 파괴 도구 필터링에 사용한다. */
@GetMapping("/me/permissions")
public ResponseEntity<List<String>> getMyPermissions(@AuthenticationPrincipal Long userId) {
  return ResponseEntity.ok(userPermissionService.getCodesForUser(userId));
}
```

테스트: MockMvc로 200 + 권한 문자열 배열 반환 검증.

- [ ] **Step 3: ai-agent api-client 메서드**

```typescript
async getSessionPermissions(userId: number): Promise<string[]> {
  const { data } = await this.http.get('/auth/me/permissions', {
    headers: { 'X-On-Behalf-Of': String(userId) },
  });
  return data;
}
```

nock 테스트 추가.

- [ ] **Step 4: agent-sdk.ts 통합**

`executeAgent()` 시작 지점에서 권한을 조회하고 MCP 서버 빌드에 전달:
```typescript
const userPermissions = await apiClient.getSessionPermissions(userId);
const mcpServer = buildFirehubMcpServer({ apiClient, userPermissions });
```

실패 시 **빈 배열**로 폴백하여 파괴 도구가 기본 차단되게 한다 (fail-closed).

- [ ] **Step 5: 통합 테스트**

`agent-sdk.test.ts`에 케이스 추가:
```typescript
it('omits destructive tools when getSessionPermissions fails', async () => {
  // nock 으로 /auth/me/permissions 500 응답
  // 에이전트 실행 후 도구 목록 확인
});
```

- [ ] **Step 6: 통과 확인 + 커밋**

```bash
cd apps/firehub-ai-agent && pnpm test
# PASS 확인
git add apps/firehub-ai-agent/src/ apps/firehub-api/src/
git commit -m "feat(ai-agent): 세션 권한 획득 후 MCP 도구 필터링 적용"
```

---

## Task 10: dataset-manager 서브에이전트 파일 작성

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/subagents/dataset-manager/agent.md`
- Create: `apps/firehub-ai-agent/src/agent/subagents/dataset-manager/rules.md`
- Create: `apps/firehub-ai-agent/src/agent/subagents/dataset-manager/examples.md`

- [ ] **Step 1: `agent.md` 작성**

```markdown
---
name: dataset-manager
description: "데이터셋 생성·수정·삭제·컬럼 변경·CSV/XLSX 임포트를 대화형으로 수행하는 전문 에이전트. 단순 조회(목록, 상세, 스키마 확인)는 위임하지 마세요. 공간 데이터 감지 시 GEOMETRY 컬럼과 SRID 4326을 자동 제안합니다."
---

# 역할
당신은 Smart Fire Hub의 데이터셋 도메인 전문 에이전트입니다. 사용자와 대화하며 데이터셋 스키마를 설계하고, 생성·수정·삭제·컬럼 변경·CSV 임포트를 수행합니다. Smart Fire Hub는 소방 도메인 특화 데이터 허브이며, 공간 데이터(PostGIS) 비중이 큽니다.

# 위임 정책
- 단순 조회(`list_datasets`, `get_dataset`)는 메인 에이전트가 처리합니다. 당신은 **상태 변경 또는 대화형 설계**가 필요한 요청만 받습니다.
- 항상 사용자의 의도를 먼저 확인하고, 스키마 결정 전에 중요한 선택지를 제시합니다.

# 작업 흐름 (공통)
1. 사용자 의도 파악 (생성/수정/삭제/컬럼/임포트)
2. 선행 조건 검증 (권한, 존재 여부, 참조 관계)
3. 사용자 확인
4. 실행
5. 결과 요약 + 다음 제안

# 파괴 작업 체크리스트 (절대 준수)
다음 작업 전에는 **반드시** 사용자의 명시적 평문 확인이 필요합니다:
1. 데이터셋 삭제 (`delete_dataset`)
2. 컬럼 삭제 (`drop_dataset_column`)
3. REPLACE 전략 임포트 (`start_import` with loadStrategy=REPLACE)

## 확인 요구 형식
- 대상을 **이름과 핵심 속성**으로 명시 (예: "custom.fire_incidents (행 12,453개, 3개 파이프라인에서 참조)")
- 복구 불가 명시
- **"네, 삭제하세요" / "yes, delete" 류의 명시적 평문**만 승인으로 간주
- "삭제해줘"만으로는 실행 금지. 반드시 한 번 더 요약 후 재확인

## 삭제 전 필수 절차
`delete_dataset` 호출 전에 반드시 `get_dataset_references`를 먼저 호출해 참조 파이프라인·대시보드·스마트잡 개수·이름을 사용자에게 고지합니다. 참조가 있으면 그 목록을 명시하고 재확인받습니다.

# GIS 자동 감지
다음 단서를 발견하면 **즉시** GEOMETRY 컬럼을 제안합니다:
- 컬럼명: `lat`, `latitude`, `lng`, `lon`, `longitude`, `x`, `y`, `geom`, `geometry`, `location`
- 데이터 포맷: WKT(`POINT(...)`, `POLYGON(...)`), GeoJSON 문자열
- 위경도 쌍이 감지되면 단일 `GEOMETRY(Point, 4326)` 컬럼으로 통합 제안
- GiST 인덱스를 기본 추천
- 사용자가 거부하면 일반 `NUMERIC(9,6)` / `TEXT` 컬럼으로 대체

# 임포트 워크플로
자세한 절차는 `rules.md`와 `examples.md` 참고.

요약:
1. `preview_csv(fileId)`
2. 스키마 설계 대화 (신규) 또는 매핑 제안 (기존 데이터셋)
3. `validate_import(...)`
4. 사용자 최종 확인 (REPLACE는 강한 확인)
5. `start_import(...)`
6. `importId` 반환 후 "진행 중" 안내

# 상태 관리
멀티턴 대화에서는 **최신 제안 스키마**를 간결한 JSON 요약으로 유지해 응답에 포함합니다. 사용자가 뒤로 돌아가려면 참조할 수 있어야 합니다.
```

- [ ] **Step 2: `rules.md` 작성**

```markdown
# dataset-manager 규칙

## 컬럼 타입 매핑 기본값
- 정수: `INTEGER` (>10자리) 또는 `BIGINT`
- 실수: `NUMERIC(18,6)` 기본
- 문자열: `VARCHAR(n)` — 관측 최대 길이 × 1.5, 최소 32
- 긴 텍스트(>500자 관측): `TEXT`
- 날짜만: `DATE`
- 날짜+시간: `TIMESTAMP`
- 불리언: `BOOLEAN`
- 공간: `GEOMETRY(Point, 4326)` — lat/lng 쌍 자동 통합

## 컬럼명 규칙
- 패턴: `[a-z][a-z0-9_]*`
- 예약어(`user`, `order` 등) 감지 시 자동 접두(`t_`) 또는 재명명 제안

## 파괴 작업 체크리스트
1. **삭제 전 참조 확인**: `get_dataset_references` 필수 선호출
2. **평문 확인 대기**: 모호한 승인("그래", "ok")은 거부, 명시적 표현만 인정
3. **실행 후 요약**: 삭제된 객체 이름·시각을 응답에 포함

## REPLACE 전략 주의
- 기본은 APPEND
- REPLACE는 파괴로 간주 (기존 행 전부 소실)
- 사용자에게 "기존 행 N개가 삭제됩니다" 명시 후 확인

## 임포트 미리보기 한도
- 미리보기: 상위 100행
- 검증 리포트: 에러 최대 50건 샘플
- 적재는 비동기 작업이므로 진행률은 별도 조회
```

- [ ] **Step 3: `examples.md` 작성**

```markdown
# dataset-manager 대화 예시

## 예시 1 — GIS 감지 + 신규 데이터셋 생성

**사용자**: 화재 발생 데이터 CSV를 올렸어. 데이터셋으로 만들어줘. (첨부: fireId=12)

**에이전트**:
1. `preview_csv({ fileId: 12 })` 호출
2. 결과 확인: 컬럼 `incident_no`, `occurred_at`, `lat`, `lng`, `address`, `damage_amount`
3. 응답:
   > 파일 미리보기 완료. 총 12,345행, 6개 컬럼 감지했습니다.
   > **공간 데이터 감지**: `lat`, `lng` 쌍이 있어 `location GEOMETRY(Point, 4326)` 컬럼으로 통합하는 걸 권장합니다. GiST 인덱스도 같이 생성됩니다.
   > 아래 스키마로 생성할까요?
   > - `incident_no` VARCHAR(32) PK
   > - `occurred_at` TIMESTAMP
   > - `location` GEOMETRY(Point, 4326) [lat/lng 통합]
   > - `address` VARCHAR(200)
   > - `damage_amount` NUMERIC(18,6)
   > 이 스키마로 진행하면 "네, 만드세요"라고 답해주세요.

**사용자**: 네, 만드세요.

**에이전트**:
4. `create_dataset(...)` + `validate_import(...)` + `start_import(...)` 순차 실행
5. 응답: `fire_incidents 데이터셋 생성 완료. 임포트 작업 시작됨 (importId=imp-abc). 완료 시 알림이 뜹니다.`

## 예시 2 — 삭제 확인 흐름

**사용자**: fire_incidents 데이터셋 삭제해줘.

**에이전트**:
1. `get_dataset({ id })` + `get_dataset_references({ id })` 호출
2. 응답:
   > 삭제 대상 확인:
   > - **custom.fire_incidents** (행 12,453개)
   > - 참조: 파이프라인 2개(`daily_summary`, `heatmap_refresh`), 대시보드 1개(`소방 현황`)
   > - 복구 불가
   > 참조가 있는 상태로 삭제하면 해당 파이프라인·대시보드가 작동하지 않게 됩니다.
   > 그래도 삭제하시려면 **"네, 삭제하세요"** 라고 정확히 답해주세요.

**사용자**: 네, 삭제하세요.

**에이전트**:
3. `delete_dataset({ id })` 호출
4. 응답: `custom.fire_incidents 삭제 완료 (2026-04-11 18:30). 참조되던 파이프라인 2개는 오류 상태가 될 수 있으니 점검이 필요합니다.`

## 예시 3 — 컬럼 추가

**사용자**: fire_incidents에 severity 컬럼 추가해줘. 1~5 숫자야.

**에이전트**:
1. `get_dataset({ id })`
2. 응답:
   > severity 컬럼을 `INTEGER NOT NULL DEFAULT 1` 로 추가할 예정입니다. 기본값을 다른 값으로 하시겠어요? 아니면 NULL 허용으로?

**사용자**: 기본값 1, NOT NULL.

**에이전트**:
3. `add_dataset_column(...)` 호출
4. 응답: `severity 컬럼 추가 완료.`

## 예시 4 — 단순 조회 요청 (위임 거부)

**사용자**: 데이터셋 목록 뭐 있어?

**에이전트**: (이 요청은 메인이 처리하므로 dataset-manager가 호출되지 않음)
```

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-ai-agent/src/agent/subagents/dataset-manager/
git commit -m "feat(ai-agent): dataset-manager 서브에이전트 파일 추가"
```

---

## Task 11: `subagent-loader` 로딩 검증

**Files:**
- Modify: `apps/firehub-ai-agent/src/agent/subagent-loader.test.ts`

- [ ] **Step 1: 테스트 추가**

```typescript
it('loads dataset-manager subagent', () => {
  const agents = loadSubagents();
  const ds = agents.find((a) => a.name === 'dataset-manager');
  expect(ds).toBeDefined();
  expect(ds!.description).toContain('데이터셋');
  expect(ds!.description).toContain('위임하지 마세요');
  expect(ds!.systemPrompt).toContain('GEOMETRY');
  expect(ds!.systemPrompt).toContain('파괴 작업 체크리스트');
});
```

- [ ] **Step 2: 통과 확인**

Run: `pnpm test -- src/agent/subagent-loader.test.ts -t dataset-manager`
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-ai-agent/src/agent/subagent-loader.test.ts
git commit -m "test(ai-agent): subagent-loader의 dataset-manager 로딩 검증"
```

---

## Task 12: `system-prompt.ts`에 신규 도구 설명 추가

**Files:**
- Modify: `apps/firehub-ai-agent/src/agent/system-prompt.ts`

- [ ] **Step 1: 도구 목록 섹션에 추가**

기존 데이터셋 도구 설명 부근에 다음 줄을 삽입:
```
- delete_dataset — 데이터셋 삭제 (파괴). 평문 확인 필수.
- add_dataset_column — 데이터셋에 컬럼 추가
- drop_dataset_column — 데이터셋 컬럼 제거 (파괴). 평문 확인 필수.
- get_dataset_references — 삭제 전 영향 범위 조회
- preview_csv / validate_import / start_import / import_status — CSV/XLSX 임포트 워크플로
```

그리고 서브에이전트 안내 섹션에 추가:
```
- dataset-manager — 데이터셋 도메인의 상태 변경·대화형 설계·임포트 작업을 위임합니다.
  단순 조회는 직접 처리하세요.
```

- [ ] **Step 2: 기존 system-prompt 테스트가 있으면 실행**

Run: `pnpm test -- src/agent/system-prompt`
Expected: PASS (파일 내용 검증 테스트가 있다면 갱신 필요)

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-ai-agent/src/agent/system-prompt.ts
git commit -m "feat(ai-agent): 신규 도구·dataset-manager 서브에이전트 system-prompt 갱신"
```

---

## Task 13: Playwright E2E — 신규 데이터셋 생성 (GIS 감지)

**Files:**
- Create: `apps/firehub-web/e2e/pages/ai-chat/dataset-manager.spec.ts`

- [ ] **Step 1: 시나리오 작성**

```typescript
import { test, expect } from '@playwright/test';
import { loginAsTestUser } from '../../helpers/auth';
import path from 'path';

/**
 * dataset-manager E2E — CSV 첨부 → GIS 감지 → 신규 데이터셋 생성 흐름.
 * 백엔드 실제 처리까지 진행하며, 스크린샷은 snapshots/ 하위에 저장한다.
 */
test.describe('AI 챗 dataset-manager', () => {
  test('CSV 첨부 → GIS 감지 → 신규 데이터셋 생성', async ({ page }) => {
    await loginAsTestUser(page);

    await page.goto('/');
    await page.getByRole('button', { name: /AI 챗/i }).click();

    const csvPath = path.join(__dirname, '../../fixtures/fire-incidents-sample.csv');
    await page.setInputFiles('input[type="file"]', csvPath);

    await page.getByPlaceholder(/메시지/i).fill('이 파일로 화재 데이터셋 만들어줘');
    await page.getByRole('button', { name: /전송/i }).click();

    // GIS 감지 메시지 대기
    await expect(
      page.getByText(/GEOMETRY.*4326|공간 데이터 감지/i),
    ).toBeVisible({ timeout: 60_000 });

    await page.screenshot({ path: 'snapshots/dataset-manager-gis-detect.png' });

    // 확인 응답
    await page.getByPlaceholder(/메시지/i).fill('네, 만드세요');
    await page.getByRole('button', { name: /전송/i }).click();

    // 생성 완료
    await expect(
      page.getByText(/생성 완료|임포트 작업 시작/i),
    ).toBeVisible({ timeout: 120_000 });

    await page.screenshot({ path: 'snapshots/dataset-manager-created.png' });
  });
});
```

- [ ] **Step 2: 샘플 CSV fixture 추가**

`apps/firehub-web/e2e/fixtures/fire-incidents-sample.csv`:
```csv
incident_no,occurred_at,lat,lng,address,damage_amount
F-001,2026-04-01 10:00,37.5665,126.9780,서울시 중구,1000000
F-002,2026-04-01 11:30,37.5651,126.9895,서울시 종로구,500000
```

- [ ] **Step 3: 실행 확인**

Run:
```bash
cd apps/firehub-web && pnpm test:e2e --grep "dataset-manager.*GIS"
```
Expected: PASS + 스크린샷 2장 생성

- [ ] **Step 4: 커밋**

```bash
git add apps/firehub-web/e2e/pages/ai-chat/dataset-manager.spec.ts apps/firehub-web/e2e/fixtures/fire-incidents-sample.csv
git commit -m "test(web): dataset-manager E2E GIS 감지 생성 시나리오"
```

---

## Task 14: Playwright E2E — 삭제 확인 흐름

**Files:**
- Modify: `apps/firehub-web/e2e/pages/ai-chat/dataset-manager.spec.ts`

- [ ] **Step 1: 시나리오 추가**

```typescript
test('데이터셋 삭제 — 참조 고지 + 평문 확인 게이팅', async ({ page, request }) => {
  await loginAsTestUser(page);

  // 준비: 테스트 데이터셋 생성 (API 직접)
  const created = await request.post('/api/v1/datasets', {
    data: { name: 'e2e_delete_target', tableName: 'e2e_delete_target', columns: [/*...*/] },
  });
  const { id } = await created.json();

  await page.goto('/');
  await page.getByRole('button', { name: /AI 챗/i }).click();

  await page.getByPlaceholder(/메시지/i).fill(`e2e_delete_target 데이터셋 삭제해줘`);
  await page.getByRole('button', { name: /전송/i }).click();

  // 참조 확인 프롬프트
  await expect(page.getByText(/복구 불가|네, 삭제하세요/i)).toBeVisible({ timeout: 60_000 });
  await page.screenshot({ path: 'snapshots/dataset-manager-delete-confirm.png' });

  // "삭제해줘"만으로는 안 되는지 확인 (약한 승인)
  await page.getByPlaceholder(/메시지/i).fill('그래');
  await page.getByRole('button', { name: /전송/i }).click();
  await expect(page.getByText(/"네, 삭제하세요"|정확히 답|한 번 더 확인/i)).toBeVisible({ timeout: 30_000 });

  // 강한 승인
  await page.getByPlaceholder(/메시지/i).fill('네, 삭제하세요');
  await page.getByRole('button', { name: /전송/i }).click();

  await expect(page.getByText(/삭제 완료/i)).toBeVisible({ timeout: 60_000 });
  await page.screenshot({ path: 'snapshots/dataset-manager-deleted.png' });

  // 데이터셋 실제로 사라졌는지 확인
  const lookup = await request.get(`/api/v1/datasets/${id}`);
  expect(lookup.status()).toBe(404);
});
```

- [ ] **Step 2: 실행 확인**

Run: `pnpm test:e2e --grep "데이터셋 삭제"`
Expected: PASS

- [ ] **Step 3: 커밋**

```bash
git add apps/firehub-web/e2e/pages/ai-chat/dataset-manager.spec.ts
git commit -m "test(web): dataset-manager E2E 삭제 확인 게이팅 시나리오"
```

---

## Task 15: 통합 검증 및 ROADMAP 상태 업데이트

- [ ] **Step 1: 전체 테스트 실행**

```bash
pnpm build
pnpm test
pnpm --filter firehub-web test:e2e
cd apps/firehub-api && ./gradlew test
```
Expected: 전부 PASS

- [ ] **Step 2: 로컬 수동 확인**

```bash
pnpm dev:full
```
- 로그인 → AI 챗 패널
- 시나리오: "fire_incidents_sample 데이터셋 만들어줘" (CSV 첨부)
- GIS 감지 응답, 생성 완료 응답 확인
- "방금 만든 거 지워" → 참조 확인 프롬프트 → 평문 확인 → 삭제 완료 확인

- [ ] **Step 3: ROADMAP 상태 업데이트**

`docs/ROADMAP.md`에서 `5.10.1 dataset-manager 서브에이전트` 항목을 `⬜`에서 `✅`로 변경.
요약 테이블의 Phase 5.10 진행률을 `1/7`로 갱신 (이미 그렇게 기록됐다면 그대로).
변경 이력 섹션에 한 줄 추가:
```
| 2026-04-11 | Phase 5.10 시작 및 dataset-manager 서브에이전트(5.10.1) 완료. 데이터셋 생성/수정/삭제/컬럼/CSV 임포트 + GIS 자동 감지. MCP 도구 8종 추가, E2E 시나리오 2종 포함. |
```

- [ ] **Step 4: 커밋**

```bash
git add docs/ROADMAP.md
git commit -m "docs(roadmap): dataset-manager 서브에이전트 완료 (Phase 5.10.1)"
```

- [ ] **Step 5: 최종 상태 확인**

```bash
git log --oneline -20
```
Phase 5.10.1 관련 15개 내외 커밋이 올라와 있어야 한다.

---

## Acceptance Criteria 체크 (구현 완료 시점)

- [ ] `dataset-manager` 서브에이전트가 로더에 등록되어 메인이 상태 변경 요청을 위임한다
- [ ] 단순 조회 요청은 메인이 직접 처리한다
- [ ] 신규 MCP 도구 8종(delete_dataset, add_dataset_column, drop_dataset_column, get_dataset_references, preview_csv, validate_import, start_import, import_status) 전부 테스트 포함
- [ ] 권한이 없는 사용자 세션에서 파괴 도구가 노출되지 않는다
- [ ] 파괴 작업 시 평문 확인 없이 실행되지 않는다 (E2E로 검증됨)
- [ ] lat/lng 감지 시 GEOMETRY 컬럼 제안 (E2E로 검증됨)
- [ ] CSV 임포트 워크플로가 챗 내에서 완주한다 (E2E로 검증됨)
- [ ] 삭제 시 참조 관계가 고지된다
- [ ] 감사 로그에 삭제 이벤트 기록
- [ ] ROADMAP.md에 Phase 5.10 추가 및 5.10.1 ✅
- [ ] 모든 단위/통합/E2E 테스트 통과
