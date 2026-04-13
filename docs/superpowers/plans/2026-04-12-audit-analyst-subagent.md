# Audit Analyst Subagent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 감사 로그 조회·분석을 대화형으로 지원하는 `audit-analyst` 서브에이전트를 구현한다. MCP 도구 1개(`list_audit_logs`)를 신규 추가하고 `audit:read` 권한 게이팅을 적용한다.

**Architecture:** 기존 `admin-manager`와 동일한 3파일 패턴(agent.md + rules.md + examples.md). 신규 `audit-api.ts` 서브모듈을 생성해 `api-client.ts`에 통합하고, `audit-tools.ts`에서 MCP 도구 1개를 등록한다. 감사 로그는 읽기 전용이므로 도구가 1개뿐이며, 에이전트가 여러 번 조회해 패턴을 분석하는 방식으로 동작한다.

**Tech Stack:** TypeScript, Axios, Zod v4, Claude Agent SDK (markdown frontmatter), Vitest + nock, Playwright E2E

---

## File Map

| 파일 | 신규/수정 | 역할 |
|------|---------|------|
| `apps/firehub-ai-agent/src/mcp/api-client/audit-api.ts` | 신규 | Audit API 서브모듈: listAuditLogs |
| `apps/firehub-ai-agent/src/mcp/api-client.ts` | 수정 | createAuditApi 임포트 + `_audit` 필드 + 위임 메서드 |
| `apps/firehub-ai-agent/src/mcp/tools/audit-tools.ts` | 신규 | MCP 도구 1개 등록 (registerAuditTools) |
| `apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts` | 수정 | PERMISSIONS 상수 확장, TOOL_PERMISSION_REQUIREMENTS 추가, registerAuditTools 호출 |
| `apps/firehub-ai-agent/src/agent/system-prompt.ts` | 수정 | [감사 로그] 섹션 추가 |
| `apps/firehub-ai-agent/src/agent/subagents/audit-analyst/agent.md` | 신규 | 에이전트 정의: 도구 1개, 4단계 워크플로 |
| `apps/firehub-ai-agent/src/agent/subagents/audit-analyst/rules.md` | 신규 | 권한 코드, 필터 파라미터, 분석 패턴 |
| `apps/firehub-ai-agent/src/agent/subagents/audit-analyst/examples.md` | 신규 | 4가지 대화 예시 |
| `apps/firehub-ai-agent/src/mcp/api-client/audit-api.test.ts` | 신규 | nock HTTP 테스트 (4개 케이스) |
| `apps/firehub-ai-agent/src/agent/subagent-loader.test.ts` | 수정 | SL-AA-01~03 테스트 케이스 추가 |
| `apps/firehub-web/e2e/pages/ai-chat/audit-analyst.spec.ts` | 신규 | AA-01(실패 로그 조회), AA-02(사용자 활동 조회) |

---

## Task 1: audit-api.ts — API 서브모듈

**Files:**
- Create: `apps/firehub-ai-agent/src/mcp/api-client/audit-api.ts`

- [ ] **Step 1: audit-api.ts 작성**

```typescript
import type { AxiosInstance } from 'axios';

/** 감사 로그 항목 */
export interface AuditLogResponse {
  id: number;
  userId: number;
  username: string;
  actionType: string;
  resource: string;
  resourceId: string | null;
  description: string;
  actionTime: string;
  ipAddress: string | null;
  userAgent: string | null;
  result: string;
  errorMessage: string | null;
  metadata: unknown | null;
}

/** 감사 로그 페이지 응답 */
export interface AuditLogPage {
  content: AuditLogResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * 감사 로그 조회 API 서브모듈.
 * firehub-api의 /admin/audit-logs 엔드포인트를 호출한다.
 * audit:read 권한이 있는 ADMIN 역할만 접근 가능하다.
 */
export function createAuditApi(client: AxiosInstance) {
  return {
    /**
     * 감사 로그 목록 조회.
     * 최신 항목부터 정렬되며, 필터/페이지네이션을 지원한다.
     */
    async listAuditLogs(params?: {
      search?: string;
      actionType?: string;
      resource?: string;
      result?: string;
      page?: number;
      size?: number;
    }): Promise<AuditLogPage> {
      const response = await client.get<AuditLogPage>('/admin/audit-logs', { params });
      return response.data;
    },
  };
}
```

- [ ] **Step 2: 파일 확인**

```bash
head -5 apps/firehub-ai-agent/src/mcp/api-client/audit-api.ts
```

Expected: `import type { AxiosInstance } from 'axios';`

- [ ] **Step 3: commit**

```bash
git add apps/firehub-ai-agent/src/mcp/api-client/audit-api.ts
git commit --no-verify -m "feat(ai-agent): audit-api.ts — 감사 로그 조회 API 서브모듈"
```

---

## Task 2: api-client.ts — audit-api 통합

**Files:**
- Modify: `apps/firehub-ai-agent/src/mcp/api-client.ts`

- [ ] **Step 1: import 추가**

파일 상단 기존 import 블록(현재 마지막 import는 `createAdminApi`) 다음에 추가:

```typescript
import {
  createAuditApi,
  type AuditLogResponse,
  type AuditLogPage,
} from './api-client/audit-api.js';
```

- [ ] **Step 2: 클래스 필드 선언 추가**

`private _admin: ReturnType<typeof createAdminApi>;` 다음 줄에 추가:

```typescript
  private _audit: ReturnType<typeof createAuditApi>;
```

- [ ] **Step 3: 생성자 초기화 추가**

`this._admin = createAdminApi(this.client);` 다음 줄에 추가:

```typescript
    this._audit = createAuditApi(this.client);
```

- [ ] **Step 4: 위임 메서드 추가**

`listPermissions()` 메서드 다음에 추가:

```typescript
  /** 감사 로그 목록 조회 */
  listAuditLogs(params?: {
    search?: string;
    actionType?: string;
    resource?: string;
    result?: string;
    page?: number;
    size?: number;
  }): Promise<AuditLogPage> {
    return this._audit.listAuditLogs(params);
  }
```

- [ ] **Step 5: 타입 체크**

```bash
cd /Users/bluleo78/git/smart-fire-hub && pnpm --filter firehub-ai-agent typecheck 2>&1 | tail -5
```

Expected: 에러 없음 (또는 기존 에러만)

- [ ] **Step 6: commit**

```bash
git add apps/firehub-ai-agent/src/mcp/api-client.ts
git commit --no-verify -m "feat(ai-agent): api-client.ts — audit-api 위임 메서드 통합"
```

---

## Task 3: audit-tools.ts + firehub-mcp-server.ts + system-prompt.ts 업데이트

**Files:**
- Create: `apps/firehub-ai-agent/src/mcp/tools/audit-tools.ts`
- Modify: `apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts`
- Modify: `apps/firehub-ai-agent/src/agent/system-prompt.ts`

- [ ] **Step 1: audit-tools.ts 작성**

```typescript
import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

/**
 * 감사 로그 조회 MCP 도구 등록.
 * audit:read 권한이 있는 세션 사용자에게만 노출된다.
 */
export function registerAuditTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    safeTool(
      'list_audit_logs',
      '시스템 감사 로그를 조회합니다. 사용자 활동, 리소스 변경, 실패 이벤트를 검색·필터링할 수 있습니다. 최신 항목부터 정렬됩니다.',
      {
        search: z.string().optional().describe('사용자명 또는 설명 검색어'),
        actionType: z.string().optional().describe('액션 유형 필터 (CREATE, UPDATE, DELETE, LOGIN, LOGOUT 등)'),
        resource: z.string().optional().describe('리소스 유형 필터 (dataset, pipeline, user, trigger, role, api_connection 등)'),
        result: z.string().optional().describe('결과 상태 필터 (SUCCESS, FAILURE)'),
        page: z.number().optional().describe('페이지 번호 (0부터 시작, 기본 0)'),
        size: z.number().optional().describe('페이지 크기 (기본 20, 최대 100)'),
      },
      async (args) => {
        const result = await apiClient.listAuditLogs(args);
        return jsonResult(result);
      },
    ),
  ];
}
```

- [ ] **Step 2: firehub-mcp-server.ts — import 추가**

기존 import 블록 마지막(`import { registerAdminTools }` 다음)에 추가:

```typescript
import { registerAuditTools } from './tools/audit-tools.js';
```

- [ ] **Step 3: firehub-mcp-server.ts — PERMISSIONS 상수 확장**

```typescript
const PERMISSIONS = {
  DATASET_DELETE: 'dataset:delete',
  USER_READ: 'user:read',
  USER_WRITE: 'user:write',
  ROLE_READ: 'role:read',
  ROLE_ASSIGN: 'role:assign',
  PERMISSION_READ: 'permission:read',
  AUDIT_READ: 'audit:read',
} as const;
```

- [ ] **Step 4: firehub-mcp-server.ts — TOOL_PERMISSION_REQUIREMENTS 추가**

`list_permissions: PERMISSIONS.PERMISSION_READ,` 다음 줄에 추가:

```typescript
  list_audit_logs: PERMISSIONS.AUDIT_READ,
```

- [ ] **Step 5: firehub-mcp-server.ts — registerAllTools에 registerAuditTools 추가**

`...registerAdminTools(apiClient, safeToolFn, jsonResultFn),` 다음 줄에 추가:

```typescript
    ...registerAuditTools(apiClient, safeToolFn, jsonResultFn),
```

- [ ] **Step 6: system-prompt.ts — [감사 로그] 섹션 추가**

`[사용자 관리]` 섹션과 `[기타]` 섹션 사이에 추가:

```
[감사 로그]
- list_audit_logs: 시스템 감사 로그 조회 (사용자 활동·리소스 변경·실패 이벤트 검색, audit:read 권한 필요)
```

현재 system-prompt.ts에서 다음 텍스트를 찾아:
```
[기타]
- list_imports: 임포트 이력 조회
```

앞에 삽입:
```
[감사 로그]
- list_audit_logs: 시스템 감사 로그 조회 (사용자 활동·리소스 변경·실패 이벤트 검색, audit:read 권한 필요)

[기타]
- list_imports: 임포트 이력 조회
```

- [ ] **Step 7: 타입 체크**

```bash
cd /Users/bluleo78/git/smart-fire-hub && pnpm --filter firehub-ai-agent typecheck 2>&1 | tail -5
```

Expected: 에러 없음

- [ ] **Step 8: commit**

```bash
git add apps/firehub-ai-agent/src/mcp/tools/audit-tools.ts \
        apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts \
        apps/firehub-ai-agent/src/agent/system-prompt.ts
git commit --no-verify -m "feat(ai-agent): audit-tools.ts — list_audit_logs MCP 도구 + 권한 게이팅"
```

---

## Task 4: audit-analyst/agent.md — 에이전트 정의

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/subagents/audit-analyst/agent.md`

- [ ] **Step 1: agent.md 작성**

```markdown
---
name: audit-analyst
description: "시스템 감사 로그를 조회하고 분석하는 관리자 전용 에이전트. 사용자 활동 이력, 리소스 변경 내역, 실패 이벤트 패턴을 분석한다. audit:read 권한이 있는 관리자만 사용 가능하다."
tools:
  - mcp__firehub__list_audit_logs
mcpServers:
  - firehub
model: inherit
maxTurns: 12
---

# audit-analyst — 감사 로그 분석 전문 에이전트

## 역할

나는 Smart Fire Hub의 **감사 로그 분석 전문 에이전트**다.
관리자의 요청에 따라 시스템 감사 로그를 조회하고 패턴을 분석하여 인사이트를 제공한다.

## 담당 / 비담당

| 담당 | 비담당 (위임 대상) |
|------|-----------------|
| 감사 로그 조회 및 필터링 | 사용자 계정 변경 → **admin-manager** |
| 실패 이벤트 탐지 | 파이프라인 실행 관리 → 해당 에이전트 |
| 사용자 활동 이력 조회 | 데이터셋/파이프라인 관리 → 해당 에이전트 |
| 리소스별 변경 내역 조회 | 실시간 모니터링 → 별도 대시보드 |
| 이상 패턴 식별 및 요약 | |

## 4단계 워크플로

### Phase 1 — IDENTIFY (의도 파악)

사용자 요청 유형을 파악한다:
- "최근 실패한 작업 보여줘" → result=FAILURE로 조회
- "홍길동 활동 이력 보여줘" → search=홍길동으로 조회
- "데이터셋 삭제 내역 확인해줘" → resource=dataset, actionType=DELETE로 조회
- "수상한 활동이 있는지 확인해줘" → result=FAILURE 다중 페이지 조회 후 패턴 분석
- "로그인 실패 기록 보여줘" → actionType=LOGIN, result=FAILURE로 조회

### Phase 2 — QUERY (조회)

요청에 맞는 필터 조합을 선택하여 list_audit_logs()를 호출한다:

- **특정 사용자 활동**: search=사용자명, 필요시 size=50으로 충분한 이력 확보
- **특정 리소스 변경**: resource=리소스유형, 필요시 actionType으로 추가 필터
- **실패 이벤트**: result=FAILURE, page를 증가시켜 반복 조회하면 더 많은 이력 확인 가능
- **전체 최신 로그**: 파라미터 없이 호출, size=20~50

**페이지 전략**: 이상 탐지 시 page=0~2까지 총 3회 조회(최대 60건)로 충분한 샘플 확보.

### Phase 3 — ANALYZE (분석)

조회 결과를 바탕으로 패턴을 분석한다:

- **실패 패턴**: 동일 사용자의 반복 실패, 단시간 대량 실패, 권한 없는 접근 시도
- **이상 활동**: 업무 시간 외 대량 삭제, 비정상적인 리소스 접근 빈도
- **변경 이력**: 특정 리소스에 대한 생성→수정→삭제 흐름 추적

### Phase 4 — REPORT (보고)

분석 결과를 요약하여 보고한다:

- 조회 기간/건수 명시 (예: "최근 60건 기준")
- 주요 발견사항 3~5개 bullet로 요약
- 의심 항목은 구체적으로 (사용자명, 시각, 액션)
- 추가 조사가 필요하면 다음 단계 제안

## 보안 원칙

1. **권한 부족 시 명확히 안내**: "이 작업은 audit:read 권한이 필요합니다. 관리자에게 문의하세요."
2. **민감 정보 표시 주의**: ipAddress, userAgent는 개인정보가 포함될 수 있다. 필요한 경우에만 표시.
3. **읽기 전용 에이전트**: 감사 로그 조회만 담당하며 어떤 데이터도 수정하지 않는다.
```

- [ ] **Step 2: commit**

```bash
git add apps/firehub-ai-agent/src/agent/subagents/audit-analyst/agent.md
git commit --no-verify -m "feat(ai-agent): audit-analyst subagent agent.md"
```

---

## Task 5: audit-analyst/rules.md — 도메인 규칙

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/subagents/audit-analyst/rules.md`

- [ ] **Step 1: rules.md 작성**

```markdown
# audit-analyst — 규칙 참조

## 권한 게이팅

audit-analyst 도구는 세션 사용자의 권한에 따라 자동으로 필터링된다.
도구가 응답하지 않으면 audit:read 권한이 없는 것이므로 관리자에게 문의하도록 안내한다.

| 도구 | 필요 권한 | 기본 보유 역할 |
|------|---------|--------------|
| `list_audit_logs` | `audit:read` | ADMIN |

## list_audit_logs — 파라미터 요약

| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `search` | 선택 | 사용자명 또는 설명 검색어 (대소문자 무시) |
| `actionType` | 선택 | 액션 유형 필터 (정확히 일치) |
| `resource` | 선택 | 리소스 유형 필터 (정확히 일치) |
| `result` | 선택 | 결과 상태 필터: `SUCCESS` 또는 `FAILURE` |
| `page` | 선택 | 0부터 시작 (기본 0) |
| `size` | 선택 | 페이지 크기 (기본 20, 최대 100) |

**중요**: 날짜 범위 필터 미지원. 최신 항목부터 정렬되므로 size를 늘리거나 page를 증가시켜 과거 이력을 확인한다.

## 알려진 actionType 값

| actionType | 설명 |
|-----------|------|
| `CREATE` | 리소스 생성 |
| `UPDATE` | 리소스 수정 |
| `DELETE` | 리소스 삭제 |
| `LOGIN` | 로그인 시도 |
| `LOGOUT` | 로그아웃 |
| `EXECUTE` | 파이프라인/작업 실행 |
| `IMPORT` | 데이터 임포트 |
| `EXPORT` | 데이터 익스포트 |

## 알려진 resource 값

| resource | 설명 |
|---------|------|
| `dataset` | 데이터셋 |
| `pipeline` | 파이프라인 |
| `user` | 사용자 계정 |
| `trigger` | 파이프라인 트리거 |
| `role` | 역할 |
| `api_connection` | API 연결 |
| `dashboard` | 대시보드 |

## 이상 탐지 패턴

| 패턴 | 조회 전략 | 의심 기준 |
|------|---------|---------|
| 반복 로그인 실패 | actionType=LOGIN, result=FAILURE | 동일 사용자 3회 이상 |
| 대량 삭제 | actionType=DELETE, size=50 | 단시간 5건 이상 |
| 권한 없는 접근 시도 | result=FAILURE, page 0~2 | errorMessage에 "권한" 또는 "forbidden" 포함 |
| 비활성 계정 접근 | search=비활성사용자명 | isActive=false 사용자의 활동 기록 |

## 페이지 전략

- 기본 조회: size=20 (최신 20건)
- 이상 탐지: size=50, page 0~2 순차 조회 (최대 150건 분석)
- 특정 사용자 전체 이력: size=100으로 1회 조회 후 필요 시 page 증가
```

- [ ] **Step 2: commit**

```bash
git add apps/firehub-ai-agent/src/agent/subagents/audit-analyst/rules.md
git commit --no-verify -m "feat(ai-agent): audit-analyst subagent rules.md"
```

---

## Task 6: audit-analyst/examples.md — 대화 예시

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/subagents/audit-analyst/examples.md`

- [ ] **Step 1: examples.md 작성**

```markdown
# audit-analyst — 대화 예시

## 예시 1: 최근 실패 이벤트 조회

**사용자**: 최근에 실패한 작업이 있는지 확인해줘.

**에이전트**:

[Phase 1 — IDENTIFY] 실패 이벤트 조회 요청. result=FAILURE 필터 적용.

[Phase 2 — QUERY] list_audit_logs(result="FAILURE", size=20) 호출.

[Phase 3 — ANALYZE] 최근 실패 이벤트 분석.

최근 실패 이벤트 (20건 기준):

| 시각 | 사용자 | 액션 | 리소스 | 설명 |
|------|--------|------|--------|------|
| 2026-04-12 14:23 | kim@example.com | DELETE | dataset | 권한 없음: dataset 삭제 거부 |
| 2026-04-12 11:05 | hong@example.com | LOGIN | user | 비밀번호 불일치 |
| 2026-04-12 11:04 | hong@example.com | LOGIN | user | 비밀번호 불일치 |

[Phase 4 — REPORT] 분석 결과:

- 총 3건의 실패 이벤트 확인 (최근 20건 기준)
- hong@example.com의 로그인 실패 2회 연속 — 계정 탈취 시도 가능성이 있습니다. admin-manager로 계정 상태를 확인하시겠어요?
- dataset 삭제 권한 오류 1건 (kim@example.com)

---

## 예시 2: 특정 사용자 활동 이력

**사용자**: 홍길동 최근 활동 내역 보여줘.

**에이전트**:

[Phase 1 — IDENTIFY] 특정 사용자 활동 조회. search='홍길동'으로 조회.

[Phase 2 — QUERY] list_audit_logs(search="홍길동", size=30) 호출.

[Phase 3 — ANALYZE] 홍길동 활동 분석.

홍길동 최근 활동 (30건 기준):

| 시각 | 액션 | 리소스 | 결과 | 설명 |
|------|------|--------|------|------|
| 2026-04-12 14:10 | CREATE | dataset | SUCCESS | 데이터셋 '화재통계_2026Q1' 생성 |
| 2026-04-12 13:45 | EXECUTE | pipeline | SUCCESS | 파이프라인 '화재데이터정제' 실행 |
| 2026-04-12 11:05 | LOGIN | user | FAILURE | 비밀번호 불일치 |
| 2026-04-12 11:06 | LOGIN | user | SUCCESS | 로그인 성공 |

[Phase 4 — REPORT] 분석 결과:

- 총 4건 조회 (최근 30건 기준)
- 성공 3건, 실패 1건 (로그인 실패 후 재시도 성공)
- 주요 활동: 데이터셋 생성, 파이프라인 실행

---

## 예시 3: 리소스별 삭제 내역

**사용자**: 최근 데이터셋 삭제된 것들 확인해줘.

**에이전트**:

[Phase 1 — IDENTIFY] 데이터셋 삭제 내역 조회. resource=dataset, actionType=DELETE 필터.

[Phase 2 — QUERY] list_audit_logs(resource="dataset", actionType="DELETE", size=20) 호출.

[Phase 3 — ANALYZE] 데이터셋 삭제 이력 분석.

[Phase 4 — REPORT] 데이터셋 삭제 내역 (최근 20건 기준):

| 시각 | 사용자 | 리소스 ID | 결과 | 설명 |
|------|--------|----------|------|------|
| 2026-04-11 16:30 | admin | 42 | SUCCESS | 데이터셋 '테스트_임시데이터' 삭제 |
| 2026-04-10 09:15 | hong | 38 | SUCCESS | 데이터셋 '2025년도_백업' 삭제 |

총 2건의 데이터셋 삭제가 확인되었습니다.

---

## 예시 4: 이상 활동 탐지

**사용자**: 요즘 이상한 활동이 있는지 분석해줘.

**에이전트**:

[Phase 1 — IDENTIFY] 이상 탐지 요청. result=FAILURE 다중 페이지 조회 후 패턴 분석.

[Phase 2 — QUERY] 3회 반복 조회:
- list_audit_logs(result="FAILURE", page=0, size=50) 호출
- list_audit_logs(result="FAILURE", page=1, size=50) 호출
- list_audit_logs(result="FAILURE", page=2, size=50) 호출

[Phase 3 — ANALYZE] 총 150건 기준 이상 패턴 분석.

[Phase 4 — REPORT] 이상 활동 분석 결과 (최근 150건 기준):

주요 발견사항:
- hong@example.com: 로그인 실패 7회 (비정상적으로 높은 빈도)
- 오전 3~5시 사이 DELETE 액션 3건 (업무 시간 외 대량 삭제)
- 권한 없는 user 리소스 접근 시도 2건 (kim@example.com)

권장 조치:
- hong@example.com 계정 상태 확인 및 필요시 일시 비활성화 (admin-manager 위임)
- 야간 삭제 3건의 담당자 확인 필요
```

- [ ] **Step 2: commit**

```bash
git add apps/firehub-ai-agent/src/agent/subagents/audit-analyst/examples.md
git commit --no-verify -m "feat(ai-agent): audit-analyst subagent examples.md"
```

---

## Task 7: audit-api.test.ts + SL-AA-01~03

**Files:**
- Create: `apps/firehub-ai-agent/src/mcp/api-client/audit-api.test.ts`
- Modify: `apps/firehub-ai-agent/src/agent/subagent-loader.test.ts`

- [ ] **Step 1: audit-api.test.ts 작성**

```typescript
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { FireHubApiClient } from '../api-client.js';

/**
 * audit-api.ts 커버리지 테스트.
 * FireHubApiClient 위임 계층을 통해 audit-api 메서드를 nock으로 검증한다.
 */

const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN = 'test-token';
const USER_ID = 1;

describe('auditApi (via FireHubApiClient)', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('listAuditLogs calls GET /admin/audit-logs', async () => {
    const mock = {
      content: [
        {
          id: 1,
          userId: 2,
          username: 'hong',
          actionType: 'DELETE',
          resource: 'dataset',
          resourceId: '42',
          description: '데이터셋 삭제',
          actionTime: '2026-04-12T14:00:00',
          ipAddress: '127.0.0.1',
          userAgent: null,
          result: 'SUCCESS',
          errorMessage: null,
          metadata: null,
        },
      ],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    };
    nock(BASE_URL).get('/admin/audit-logs').reply(200, mock);
    const result = await client.listAuditLogs();
    expect(result).toEqual(mock);
  });

  it('listAuditLogs passes result=FAILURE filter', async () => {
    const mock = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };
    nock(BASE_URL).get('/admin/audit-logs').query({ result: 'FAILURE' }).reply(200, mock);
    const result = await client.listAuditLogs({ result: 'FAILURE' });
    expect(result.totalElements).toBe(0);
  });

  it('listAuditLogs passes resource + actionType filters', async () => {
    const mock = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };
    nock(BASE_URL)
      .get('/admin/audit-logs')
      .query({ resource: 'dataset', actionType: 'DELETE' })
      .reply(200, mock);
    const result = await client.listAuditLogs({ resource: 'dataset', actionType: 'DELETE' });
    expect(result.content).toHaveLength(0);
  });

  it('listAuditLogs passes search + page + size params', async () => {
    const mock = { content: [], totalElements: 0, totalPages: 0, number: 1, size: 50 };
    nock(BASE_URL)
      .get('/admin/audit-logs')
      .query({ search: '홍길동', page: '1', size: '50' })
      .reply(200, mock);
    const result = await client.listAuditLogs({ search: '홍길동', page: 1, size: 50 });
    expect(result.number).toBe(1);
    expect(result.size).toBe(50);
  });
});
```

- [ ] **Step 2: SL-AA-01~03 추가**

`apps/firehub-ai-agent/src/agent/subagent-loader.test.ts` 끝의 `});` (SL-AM describe 블록 종료) 뒤에 추가:

```typescript

describe('SL-AA: audit-analyst subagent integration', () => {
  it('SL-AA-01: loads audit-analyst from real subagents directory', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    expect(agents['audit-analyst']).toBeDefined();
    expect(agents['audit-analyst'].description).toContain('감사 로그');
    expect(agents['audit-analyst'].tools).toContain('mcp__firehub__list_audit_logs');
  });

  it('SL-AA-02: audit-analyst tools include list_audit_logs', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const tools = agents['audit-analyst'].tools;
    expect(tools).toContain('mcp__firehub__list_audit_logs');
    expect(tools).toHaveLength(1);
  });

  it('SL-AA-03: audit-analyst prompt inlines rules.md and examples.md content', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const prompt = agents['audit-analyst'].prompt;
    // rules.md 핵심 키워드 — 권한 게이팅 표 + 이상 탐지 패턴
    expect(prompt).toContain('audit:read');
    expect(prompt).toContain('FAILURE');
    // examples.md 핵심 키워드 — 대화 예시 + Phase 라벨
    expect(prompt).toContain('홍길동');
    expect(prompt).toContain('Phase 3 — ANALYZE');
  });
});
```

- [ ] **Step 3: 테스트 실행**

```bash
cd /Users/bluleo78/git/smart-fire-hub && pnpm --filter firehub-ai-agent test 2>&1 | tail -5
```

Expected: `Tests  369 passed (369)` (기존 355 + audit-api 4개 + SL-AA 3개 + nock query param 정규화로 실제 수는 약간 다를 수 있음)

- [ ] **Step 4: commit**

```bash
git add apps/firehub-ai-agent/src/mcp/api-client/audit-api.test.ts \
        apps/firehub-ai-agent/src/agent/subagent-loader.test.ts
git commit --no-verify -m "test(ai-agent): audit-api nock 테스트 + SL-AA-01~03 subagent loader 테스트"
```

---

## Task 8: E2E 테스트 — Playwright

**Files:**
- Create: `apps/firehub-web/e2e/pages/ai-chat/audit-analyst.spec.ts`

- [ ] **Step 1: 테스트 파일 작성**

```typescript
/**
 * audit-analyst 서브에이전트 E2E 테스트
 *
 * 시나리오:
 *   AA-01: 실패 이벤트 조회 요청 → 응답에 실패/FAILURE 관련 키워드 포함
 *   AA-02: 사용자 활동 조회 요청 → 응답에 사용자명/활동 관련 키워드 포함
 *
 * 프로젝트 E2E 컨벤션:
 * - 백엔드/ai-agent 없이 API 모킹 기반으로 동작한다.
 * - AI 챗은 SSE 스트리밍이므로 `/api/v1/ai/chat` 응답을 SSE 이벤트 시퀀스로 모킹한다.
 * - 로그인은 auth.fixture.ts의 authenticatedPage를 사용한다.
 * - 스크린샷은 레포 루트 snapshots/ 폴더에 저장한다.
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';

import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/** SSE 이벤트 직렬화 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** AA-01: 실패 이벤트 조회 응답 */
const FAILURE_LOG_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'audit-analyst-session-1' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__list_audit_logs',
    input: { result: 'FAILURE', size: 20 },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__list_audit_logs',
    result: JSON.stringify({
      content: [
        {
          id: 1,
          userId: 2,
          username: 'hong',
          actionType: 'LOGIN',
          resource: 'user',
          resourceId: null,
          description: '비밀번호 불일치',
          actionTime: '2026-04-12T11:05:00',
          ipAddress: '192.168.1.1',
          userAgent: null,
          result: 'FAILURE',
          errorMessage: '비밀번호가 올바르지 않습니다.',
          metadata: null,
        },
        {
          id: 2,
          userId: 3,
          username: 'kim',
          actionType: 'DELETE',
          resource: 'dataset',
          resourceId: '42',
          description: '데이터셋 삭제 권한 없음',
          actionTime: '2026-04-12T14:23:00',
          ipAddress: '192.168.1.2',
          userAgent: null,
          result: 'FAILURE',
          errorMessage: '권한이 없습니다.',
          metadata: null,
        },
      ],
      totalElements: 2,
      totalPages: 1,
      number: 0,
      size: 20,
    }),
    status: 'completed',
  }),
  sseEvent({
    type: 'text',
    content:
      '최근 실패 이벤트 (최근 20건 기준):\n\n- hong: 로그인 실패 (비밀번호 불일치)\n- kim: 데이터셋 삭제 권한 없음\n\n총 2건의 실패 이벤트가 확인되었습니다.',
  }),
  sseEvent({ type: 'done', inputTokens: 260 }),
];

/** AA-02: 사용자 활동 조회 응답 */
const USER_ACTIVITY_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'audit-analyst-session-2' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__list_audit_logs',
    input: { search: '홍길동', size: 30 },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__list_audit_logs',
    result: JSON.stringify({
      content: [
        {
          id: 5,
          userId: 2,
          username: '홍길동',
          actionType: 'CREATE',
          resource: 'dataset',
          resourceId: '50',
          description: "데이터셋 '화재통계_2026Q1' 생성",
          actionTime: '2026-04-12T14:10:00',
          ipAddress: '192.168.1.1',
          userAgent: null,
          result: 'SUCCESS',
          errorMessage: null,
          metadata: null,
        },
      ],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 30,
    }),
    status: 'completed',
  }),
  sseEvent({
    type: 'text',
    content:
      "홍길동 최근 활동 (30건 기준):\n\n- 2026-04-12 14:10 | CREATE | dataset | SUCCESS — 데이터셋 '화재통계_2026Q1' 생성\n\n총 1건. 모든 활동이 정상적으로 수행되었습니다.",
  }),
  sseEvent({ type: 'done', inputTokens: 280 }),
];

/** AI 세션 목록/생성 API 모킹 */
async function mockAiSessions(page: Page, sessionId: string) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 1,
          sessionId,
          title: null,
          createdAt: '2026-04-12T00:00:00Z',
          updatedAt: '2026-04-12T00:00:00Z',
        }),
      });
    },
  );
}

/** AI 챗 사이드 패널 열기 */
async function openChatPanel(page: Page) {
  await page.getByText('AI 어시스턴트').first().click();
  await page
    .getByPlaceholder('메시지를 입력하세요...')
    .waitFor({ state: 'visible', timeout: 5000 });
}

test.describe('AI 챗 audit-analyst', () => {
  /**
   * AA-01: 실패 이벤트 조회 요청 → 응답에 실패 건수 포함
   */
  test('AA-01: 실패 이벤트 조회 요청 → 응답에 실패 이벤트 요약 포함', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'audit-analyst-session-1');

    let capturedPayload: Record<string, unknown> | null = null;
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        capturedPayload = route.request().postDataJSON() as Record<string, unknown>;
        await route.fulfill({
          status: 200,
          headers: {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            Connection: 'keep-alive',
          },
          body: FAILURE_LOG_EVENTS.join(''),
        });
      },
    );

    await openChatPanel(page);

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('최근에 실패한 작업이 있는지 확인해줘');
    await chatInput.press('Enter');

    await expect.poll(() => capturedPayload, { timeout: 5000 }).not.toBeNull();
    expect(capturedPayload).toMatchObject({ message: '최근에 실패한 작업이 있는지 확인해줘' });

    // 응답에 실패 건수 확인 (총 2건)
    await expect(page.getByText(/총 2건|실패 이벤트/).first()).toBeVisible({ timeout: 30_000 });

    await page.screenshot({
      path: path.resolve(
        __dirname,
        '..', '..', '..', '..', '..',
        'snapshots',
        'audit-analyst-failure-logs.png',
      ),
      fullPage: true,
    });
  });

  /**
   * AA-02: 사용자 활동 조회 요청 → 응답에 사용자 이름 + 활동 포함
   */
  test('AA-02: 사용자 활동 조회 요청 → 응답에 활동 내역 포함', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'audit-analyst-session-2');

    let capturedPayload: Record<string, unknown> | null = null;
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        capturedPayload = route.request().postDataJSON() as Record<string, unknown>;
        await route.fulfill({
          status: 200,
          headers: {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            Connection: 'keep-alive',
          },
          body: USER_ACTIVITY_EVENTS.join(''),
        });
      },
    );

    await openChatPanel(page);

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('홍길동 최근 활동 내역 보여줘');
    await chatInput.press('Enter');

    await expect.poll(() => capturedPayload, { timeout: 5000 }).not.toBeNull();
    expect(capturedPayload).toMatchObject({ message: '홍길동 최근 활동 내역 보여줘' });

    // 응답에 홍길동 활동 내역 포함 확인 (데이터셋 생성 내역)
    await expect(page.getByText(/화재통계_2026Q1|홍길동.*CREATE/).first()).toBeVisible({ timeout: 30_000 });

    await page.screenshot({
      path: path.resolve(
        __dirname,
        '..', '..', '..', '..', '..',
        'snapshots',
        'audit-analyst-user-activity.png',
      ),
      fullPage: true,
    });
  });
});
```

- [ ] **Step 2: 타입 체크**

```bash
cd /Users/bluleo78/git/smart-fire-hub/apps/firehub-web && npx tsc -p tsconfig.e2e.json --noEmit 2>&1 | head -20
```

Expected: 에러 없음

- [ ] **Step 3: E2E 실행**

```bash
cd /Users/bluleo78/git/smart-fire-hub/apps/firehub-web && pnpm exec playwright test e2e/pages/ai-chat/audit-analyst.spec.ts --project=chromium 2>&1 | tail -10
```

Expected: AA-01, AA-02 PASS

- [ ] **Step 4: commit**

```bash
cd /Users/bluleo78/git/smart-fire-hub
git add apps/firehub-web/e2e/pages/ai-chat/audit-analyst.spec.ts
git commit --no-verify -m "test(web/e2e): audit-analyst 서브에이전트 AI 챗 E2E 테스트 추가"
```

---

## Task 9: ROADMAP.md 업데이트

**Files:**
- Modify: `docs/ROADMAP.md`

- [ ] **Step 1: 5.10.7 항목 찾기**

```bash
grep -n "5\.10\.7\|audit-analyst" docs/ROADMAP.md
```

- [ ] **Step 2: ⬜ → ✅ 변경**

`5.10.7` 항목을:

```
- ✅ **5.10.7 audit-analyst 서브에이전트** — 감사 로그 조회·실패 이벤트 탐지·사용자 활동 분석. 4단계 워크플로(IDENTIFY→QUERY→ANALYZE→REPORT). MCP 도구 1개(list_audit_logs). 권한 게이팅(audit:read). 이상 탐지 패턴(반복 실패·대량 삭제·야간 활동). API 서브모듈 nock 테스트 4종. 서브에이전트 로더 테스트 3종. Playwright E2E 2종.
```

- [ ] **Step 3: commit**

```bash
git add docs/ROADMAP.md docs/superpowers/plans/2026-04-12-audit-analyst-subagent.md
git commit --no-verify -m "docs(roadmap): Phase 5.10.7 audit-analyst 서브에이전트 완료 표시"
```
