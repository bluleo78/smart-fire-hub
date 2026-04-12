# Admin Manager Subagent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 사용자 계정·역할 관리를 대화형으로 지원하는 `admin-manager` 서브에이전트를 구현한다. MCP 도구 6개(list_users, get_user, set_user_roles, set_user_active, list_roles, list_permissions)를 신규 추가하고 권한 게이팅을 강화한다.

**Architecture:** 기존 `trigger-manager`, `dashboard-builder`와 동일한 3파일 패턴(agent.md + rules.md + examples.md). 신규 `admin-api.ts` 서브모듈을 생성해 `api-client.ts`에 통합하고, `admin-tools.ts`에서 MCP 도구 6개를 등록한다. `TOOL_PERMISSION_REQUIREMENTS` 맵에 admin 도구별 권한을 추가하여 `user:read`, `role:assign`, `user:write`, `role:read`, `permission:read` 권한 게이팅을 적용한다.

**Tech Stack:** TypeScript, Axios, Zod v4, Claude Agent SDK (markdown frontmatter), Vitest + nock, Playwright E2E

---

## File Map

| 파일 | 신규/수정 | 역할 |
|------|---------|------|
| `apps/firehub-ai-agent/src/mcp/api-client/admin-api.ts` | 신규 | Admin API 서브모듈: listUsers, getUser, setUserRoles, setUserActive, listRoles, listPermissions |
| `apps/firehub-ai-agent/src/mcp/api-client.ts` | 수정 | createAdminApi 임포트 + `_admin` 필드 + 위임 메서드 6개 |
| `apps/firehub-ai-agent/src/mcp/tools/admin-tools.ts` | 신규 | MCP 도구 6개 등록 (registerAdminTools) |
| `apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts` | 수정 | PERMISSIONS 상수 확장, TOOL_PERMISSION_REQUIREMENTS 추가, registerAdminTools 호출 |
| `apps/firehub-ai-agent/src/agent/subagents/admin-manager/agent.md` | 신규 | 에이전트 정의: 도구 6개, 4단계 워크플로 |
| `apps/firehub-ai-agent/src/agent/subagents/admin-manager/rules.md` | 신규 | 권한 코드 표, 시스템 역할 보호 규칙, 작업 안내 |
| `apps/firehub-ai-agent/src/agent/subagents/admin-manager/examples.md` | 신규 | 4가지 대화 예시 |
| `apps/firehub-ai-agent/src/mcp/api-client/admin-api.test.ts` | 신규 | nock HTTP 테스트 (6개 메서드) |
| `apps/firehub-ai-agent/src/agent/subagent-loader.test.ts` | 수정 | SL-AM-01~03 테스트 케이스 추가 |
| `apps/firehub-web/e2e/pages/ai-chat/admin-manager.spec.ts` | 신규 | AM-01(사용자 조회), AM-02(역할 변경) |

---

## Task 1: admin-api.ts — API 서브모듈

**Files:**
- Create: `apps/firehub-ai-agent/src/mcp/api-client/admin-api.ts`

- [ ] **Step 1: admin-api.ts 작성**

```typescript
import type { AxiosInstance } from 'axios';

/** 사용자 목록 항목 */
export interface UserResponse {
  id: number;
  username: string;
  email: string;
  name: string;
  isActive: boolean;
  createdAt: string;
}

/** 사용자 상세 (역할 포함) */
export interface UserDetailResponse {
  id: number;
  username: string;
  email: string;
  name: string;
  isActive: boolean;
  createdAt: string;
  roles: RoleResponse[];
}

/** 역할 목록 항목 */
export interface RoleResponse {
  id: number;
  name: string;
  description: string | null;
  isSystem: boolean;
}

/** 권한 목록 항목 */
export interface PermissionResponse {
  id: number;
  code: string;
  description: string;
  category: string;
}

/** 사용자 목록 페이지 */
export interface UserPage {
  content: UserResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * 사용자/역할/권한 관리 API 서브모듈.
 * firehub-api의 /users, /roles, /permissions 엔드포인트를 호출한다.
 */
export function createAdminApi(client: AxiosInstance) {
  return {
    /** 사용자 목록 조회 (검색·페이지네이션 지원) */
    async listUsers(params?: { search?: string; page?: number; size?: number }): Promise<UserPage> {
      const response = await client.get<UserPage>('/users', { params });
      return response.data;
    },

    /** 사용자 상세 조회 (할당된 역할 포함) */
    async getUser(id: number): Promise<UserDetailResponse> {
      const response = await client.get<UserDetailResponse>(`/users/${id}`);
      return response.data;
    },

    /**
     * 사용자 역할 교체.
     * roleIds 배열로 기존 역할을 전부 교체한다. 빈 배열이면 모든 역할 제거.
     */
    async setUserRoles(userId: number, roleIds: number[]): Promise<void> {
      await client.put(`/users/${userId}/roles`, { roleIds });
    },

    /** 사용자 계정 활성화/비활성화 */
    async setUserActive(userId: number, active: boolean): Promise<void> {
      await client.put(`/users/${userId}/active`, { active });
    },

    /** 시스템 역할 전체 목록 조회 */
    async listRoles(): Promise<RoleResponse[]> {
      const response = await client.get<RoleResponse[]>('/roles');
      return response.data;
    },

    /** 시스템 권한 목록 조회. category로 필터 가능 */
    async listPermissions(params?: { category?: string }): Promise<PermissionResponse[]> {
      const response = await client.get<PermissionResponse[]>('/permissions', { params });
      return response.data;
    },
  };
}
```

- [ ] **Step 2: 파일 확인**

```bash
cat apps/firehub-ai-agent/src/mcp/api-client/admin-api.ts | head -5
```

Expected: `import type { AxiosInstance } from 'axios';`

- [ ] **Step 3: commit**

```bash
git add apps/firehub-ai-agent/src/mcp/api-client/admin-api.ts
git commit --no-verify -m "feat(ai-agent): admin-api.ts — 사용자/역할/권한 관리 API 서브모듈"
```

---

## Task 2: api-client.ts — admin-api 통합

**Files:**
- Modify: `apps/firehub-ai-agent/src/mcp/api-client.ts`

- [ ] **Step 1: import 추가**

파일 상단 기존 import 블록(현재 마지막 import는 `createAnalyticsApi`) 다음에 추가:

```typescript
import {
  createAdminApi,
  type UserResponse,
  type UserDetailResponse,
  type RoleResponse,
  type PermissionResponse,
  type UserPage,
} from './api-client/admin-api.js';
```

- [ ] **Step 2: 클래스 필드 선언 추가**

`private _dataImport: ReturnType<typeof createDataImportApi>;` 다음 줄에 추가:

```typescript
  private _admin: ReturnType<typeof createAdminApi>;
```

- [ ] **Step 3: 생성자 초기화 추가**

`this._dataImport = createDataImportApi(this.client);` 다음 줄에 추가:

```typescript
    this._admin = createAdminApi(this.client);
```

- [ ] **Step 4: 위임 메서드 추가**

`getSessionPermissions()` 메서드 위에(또는 클래스 끝 부분에) 추가:

```typescript
  /** 사용자 목록 조회 */
  listUsers(params?: { search?: string; page?: number; size?: number }): Promise<UserPage> {
    return this._admin.listUsers(params);
  }

  /** 사용자 상세 조회 */
  getUser(id: number): Promise<UserDetailResponse> {
    return this._admin.getUser(id);
  }

  /** 사용자 역할 교체 */
  setUserRoles(userId: number, roleIds: number[]): Promise<void> {
    return this._admin.setUserRoles(userId, roleIds);
  }

  /** 사용자 활성화 상태 변경 */
  setUserActive(userId: number, active: boolean): Promise<void> {
    return this._admin.setUserActive(userId, active);
  }

  /** 역할 목록 조회 */
  listRoles(): Promise<RoleResponse[]> {
    return this._admin.listRoles();
  }

  /** 권한 목록 조회 */
  listPermissions(params?: { category?: string }): Promise<PermissionResponse[]> {
    return this._admin.listPermissions(params);
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
git commit --no-verify -m "feat(ai-agent): api-client.ts — admin-api 위임 메서드 6개 통합"
```

---

## Task 3: admin-tools.ts + firehub-mcp-server.ts 업데이트

**Files:**
- Create: `apps/firehub-ai-agent/src/mcp/tools/admin-tools.ts`
- Modify: `apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts`

- [ ] **Step 1: admin-tools.ts 작성**

```typescript
import { z } from 'zod/v4';
import type { FireHubApiClient } from '../api-client.js';
import type { SafeToolFn, JsonResultFn } from '../firehub-mcp-server.js';

/**
 * 사용자/역할/권한 관련 MCP 도구 등록.
 * 각 도구는 firehub-mcp-server.ts의 TOOL_PERMISSION_REQUIREMENTS에
 * 매핑된 권한이 있어야 호출 가능하다.
 */
export function registerAdminTools(
  apiClient: FireHubApiClient,
  safeTool: SafeToolFn,
  jsonResult: JsonResultFn,
) {
  return [
    safeTool(
      'list_users',
      '사용자 목록을 조회합니다. 이름·이메일 검색과 페이지네이션을 지원합니다.',
      {
        search: z.string().optional().describe('이름 또는 이메일 검색어'),
        page: z.number().optional().describe('페이지 번호 (0부터 시작, 기본 0)'),
        size: z.number().optional().describe('페이지 크기 (기본 20)'),
      },
      async (args) => {
        const result = await apiClient.listUsers(args);
        return jsonResult(result);
      },
    ),

    safeTool(
      'get_user',
      '사용자 상세 정보와 할당된 역할 목록을 조회합니다.',
      {
        userId: z.number().describe('사용자 ID'),
      },
      async (args: { userId: number }) => {
        const result = await apiClient.getUser(args.userId);
        return jsonResult(result);
      },
    ),

    safeTool(
      'set_user_roles',
      '사용자에게 역할을 할당합니다. roleIds 배열로 기존 역할이 전부 교체됩니다. 빈 배열이면 모든 역할이 제거됩니다.',
      {
        userId: z.number().describe('사용자 ID'),
        roleIds: z.array(z.number()).describe('할당할 역할 ID 목록 (기존 역할 전부 교체)'),
      },
      async (args: { userId: number; roleIds: number[] }) => {
        await apiClient.setUserRoles(args.userId, args.roleIds);
        return jsonResult({ success: true, userId: args.userId, roleIds: args.roleIds });
      },
    ),

    safeTool(
      'set_user_active',
      '사용자 계정을 활성화하거나 비활성화합니다. 비활성화된 사용자는 로그인할 수 없습니다.',
      {
        userId: z.number().describe('사용자 ID'),
        active: z.boolean().describe('true: 활성화, false: 비활성화'),
      },
      async (args: { userId: number; active: boolean }) => {
        await apiClient.setUserActive(args.userId, args.active);
        return jsonResult({ success: true, userId: args.userId, active: args.active });
      },
    ),

    safeTool(
      'list_roles',
      '시스템에 등록된 모든 역할 목록을 조회합니다. 역할 ID를 set_user_roles에 사용하세요.',
      {},
      async () => {
        const result = await apiClient.listRoles();
        return jsonResult(result);
      },
    ),

    safeTool(
      'list_permissions',
      '시스템 권한 목록을 조회합니다. category로 필터링 가능합니다.',
      {
        category: z.string().optional().describe('권한 카테고리 (예: user, role, dataset, pipeline, trigger)'),
      },
      async (args: { category?: string }) => {
        const result = await apiClient.listPermissions(
          args.category ? { category: args.category } : undefined,
        );
        return jsonResult(result);
      },
    ),
  ];
}
```

- [ ] **Step 2: firehub-mcp-server.ts — import 추가**

기존 import 블록 마지막(`import { registerDataImportTools }` 다음)에 추가:

```typescript
import { registerAdminTools } from './tools/admin-tools.js';
```

- [ ] **Step 3: firehub-mcp-server.ts — PERMISSIONS 상수 확장**

`const PERMISSIONS = { DATASET_DELETE: 'dataset:delete', } as const;` 를 다음으로 교체:

```typescript
const PERMISSIONS = {
  DATASET_DELETE: 'dataset:delete',
  USER_READ: 'user:read',
  USER_WRITE: 'user:write',
  ROLE_READ: 'role:read',
  ROLE_ASSIGN: 'role:assign',
  PERMISSION_READ: 'permission:read',
} as const;
```

- [ ] **Step 4: firehub-mcp-server.ts — TOOL_PERMISSION_REQUIREMENTS 추가**

```typescript
const TOOL_PERMISSION_REQUIREMENTS: Record<string, string> = {
  delete_dataset: PERMISSIONS.DATASET_DELETE,
  drop_dataset_column: PERMISSIONS.DATASET_DELETE,
  list_users: PERMISSIONS.USER_READ,
  get_user: PERMISSIONS.USER_READ,
  set_user_roles: PERMISSIONS.ROLE_ASSIGN,
  set_user_active: PERMISSIONS.USER_WRITE,
  list_roles: PERMISSIONS.ROLE_READ,
  list_permissions: PERMISSIONS.PERMISSION_READ,
};
```

- [ ] **Step 5: firehub-mcp-server.ts — registerAdminTools 호출 추가**

`registerAllTools` 함수의 `allTools` 배열에 추가 (기존 `...registerProactiveTools(...)` 다음):

```typescript
    ...registerAdminTools(apiClient, safeToolFn, jsonResultFn),
```

- [ ] **Step 6: 타입 체크**

```bash
cd /Users/bluleo78/git/smart-fire-hub && pnpm --filter firehub-ai-agent typecheck 2>&1 | tail -5
```

Expected: 에러 없음

- [ ] **Step 7: commit**

```bash
git add apps/firehub-ai-agent/src/mcp/tools/admin-tools.ts apps/firehub-ai-agent/src/mcp/firehub-mcp-server.ts
git commit --no-verify -m "feat(ai-agent): admin-tools.ts — MCP 도구 6개 + 권한 게이팅 강화"
```

---

## Task 4: admin-manager/agent.md — 에이전트 정의

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/subagents/admin-manager/agent.md`

- [ ] **Step 1: agent.md 작성**

```markdown
---
name: admin-manager
description: "사용자 계정·역할 관리를 담당하는 관리자 전용 에이전트. 사용자 목록 조회, 역할 확인 및 변경, 계정 활성화/비활성화를 지원한다. user:read 또는 role:assign 권한이 있는 관리자만 사용 가능하다."
tools:
  - mcp__firehub__list_users
  - mcp__firehub__get_user
  - mcp__firehub__set_user_roles
  - mcp__firehub__set_user_active
  - mcp__firehub__list_roles
  - mcp__firehub__list_permissions
mcpServers:
  - firehub
model: inherit
maxTurns: 15
---

# admin-manager — 사용자/역할 관리 전문 에이전트

## 역할

나는 Smart Fire Hub의 **사용자·역할 관리 전문 에이전트**다.
관리자 권한을 가진 사용자의 요청에 따라 사용자 계정을 조회하고 역할을 변경한다.

## 담당 / 비담당

| 담당 | 비담당 (위임 대상) |
|------|-----------------|
| 사용자 목록·상세 조회 | 역할 생성·수정·삭제 → UI 관리 페이지 |
| 역할 목록 조회 | 권한 직접 할당 → UI 관리 페이지 |
| 사용자 역할 변경 (set_user_roles) | 사용자 프로필 수정 → 본인 직접 변경 |
| 사용자 계정 활성화/비활성화 | 감사 로그 조회 → **audit-analyst** |
| 권한 목록 조회 (참조용) | 데이터셋·파이프라인 관리 → 해당 에이전트 |

## 4단계 워크플로

### Phase 1 — IDENTIFY (의도 파악)

사용자 요청 유형을 파악한다:
- "사용자 목록 보여줘" / "누가 있어?" → list_users() 호출
- "홍길동 역할 변경해줘" → 이름으로 사용자 검색 후 역할 변경 흐름
- "계정 비활성화해줘" → 대상 확인 후 set_user_active 흐름
- "어떤 역할이 있어?" → list_roles() 호출

**사용자를 이름으로 언급하면**: list_users(search=이름)로 검색 → 결과 제시 → 사용자가 확인 후 진행.

### Phase 2 — DESIGN (설계 대화)

역할 변경 시:
1. list_users(search=이름)로 대상 사용자 확인
2. get_user(userId)로 현재 역할 확인
3. list_roles()로 선택 가능한 역할 목록 제시
4. 변경할 역할 확인: "USER 역할로 변경할까요? 기존 ADMIN 역할은 제거됩니다."

계정 활성화/비활성화 시:
1. 대상 사용자 확인
2. 현재 상태 확인 (get_user)
3. **비활성화는 반드시 확인 요청**: "이 사용자는 즉시 로그인 불가가 됩니다. 계속할까요?"

### Phase 3 — EXECUTE (실행)

- 역할 변경: set_user_roles(userId, roleIds) — roleIds는 새 역할 ID 전체 목록
- 활성화/비활성화: set_user_active(userId, active)

### Phase 4 — CONFIRM (결과 요약)

- 역할 변경: "'{name}' 사용자의 역할이 {이전} → {이후}로 변경되었습니다."
- 비활성화: "'{name}' 계정이 비활성화되었습니다. 이 사용자는 로그인할 수 없습니다."
- 활성화: "'{name}' 계정이 활성화되었습니다."

## 보안 원칙

1. **권한 부족 시 명확히 안내**: "이 작업은 role:assign 권한이 필요합니다. 관리자에게 문의하세요."
2. **자기 자신 비활성화 금지**: 현재 세션 사용자 ID와 대상 userId가 같으면 거부.
3. **역할 교체는 전부 교체**: set_user_roles의 roleIds는 기존 역할을 모두 대체한다. 역할 추가가 아닌 교체임을 사용자에게 명시.
4. **시스템 역할 보호**: ADMIN·USER는 시스템 역할(isSystem:true)이다. 삭제·수정은 UI에서만 가능.
```

- [ ] **Step 2: commit**

```bash
git add apps/firehub-ai-agent/src/agent/subagents/admin-manager/agent.md
git commit --no-verify -m "feat(ai-agent): admin-manager subagent agent.md"
```

---

## Task 5: admin-manager/rules.md — 도메인 규칙

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/subagents/admin-manager/rules.md`

- [ ] **Step 1: rules.md 작성**

```markdown
# admin-manager — 규칙 참조

## 권한 게이팅

admin-manager 도구는 세션 사용자의 권한에 따라 자동으로 필터링된다.
도구가 응답하지 않으면 해당 권한이 없는 것이므로 UI 관리 페이지를 안내한다.

| 도구 | 필요 권한 | 기본 보유 역할 |
|------|---------|--------------|
| `list_users` | `user:read` | ADMIN |
| `get_user` | `user:read` | ADMIN |
| `set_user_roles` | `role:assign` | ADMIN |
| `set_user_active` | `user:write` | ADMIN |
| `list_roles` | `role:read` | ADMIN |
| `list_permissions` | `permission:read` | ADMIN |

## 역할 시스템

| 역할 | isSystem | 기본 권한 | 수정 가능 |
|------|---------|---------|---------|
| ADMIN | true | 전체 | 불가 (UI 보호) |
| USER | true | user:read:self, user:write:self | 불가 (UI 보호) |
| 커스텀 역할 | false | 역할에 따라 다름 | UI에서만 가능 |

**역할 ID 조회**: 역할 변경 전 반드시 `list_roles()`로 현재 역할 ID를 확인한다.
이름으로만 역할을 특정하지 말고 ID를 사용한다.

## set_user_roles — 동작 방식

`set_user_roles(userId, roleIds)` 는 교체(replace) 방식이다.
- `roleIds: [2]` → 사용자가 역할 ID 2만 갖게 됨 (기존 역할 모두 제거)
- `roleIds: [1, 2]` → 역할 ID 1, 2를 동시에 보유
- `roleIds: []` → 모든 역할 제거 (권장하지 않음, 확인 후 진행)

**추가 방식이 아님**: 기존에 어떤 역할을 갖고 있든 roleIds 배열로 전부 교체된다.
역할을 추가하려면 get_user로 현재 역할 ID를 먼저 조회한 뒤 새 ID를 합쳐서 전달한다.

## 계정 비활성화 안전 규칙

- 비활성화 즉시 해당 사용자는 로그인 불가, 진행 중인 세션도 만료
- 복구는 `set_user_active(userId, true)`로 언제든 가능
- 자기 자신(현재 세션 사용자)은 비활성화 금지

## list_users — 파라미터 요약

| 파라미터 | 필수 | 설명 |
|---------|------|------|
| `search` | 선택 | 이름 또는 이메일 검색어 |
| `page` | 선택 | 0부터 시작 (기본 0) |
| `size` | 선택 | 페이지 크기 (기본 20) |
```

- [ ] **Step 2: commit**

```bash
git add apps/firehub-ai-agent/src/agent/subagents/admin-manager/rules.md
git commit --no-verify -m "feat(ai-agent): admin-manager subagent rules.md"
```

---

## Task 6: admin-manager/examples.md — 대화 예시

**Files:**
- Create: `apps/firehub-ai-agent/src/agent/subagents/admin-manager/examples.md`

- [ ] **Step 1: examples.md 작성**

```markdown
# admin-manager — 대화 예시

## 예시 1: 사용자 목록 조회

**사용자**: 현재 등록된 사용자 목록 보여줘.

**에이전트**:

[Phase 1 — IDENTIFY] 목록 조회. list_users() 호출.

현재 등록된 사용자 목록:

| ID | 이름 | 이메일 | 상태 | 역할 |
|----|------|--------|------|------|
| 1 | 홍길동 | hong@example.com | 활성 | ADMIN |
| 2 | 김철수 | kim@example.com | 활성 | USER |
| 3 | 이영희 | lee@example.com | 비활성 | USER |

총 3명 (활성: 2, 비활성: 1). 특정 사용자의 역할을 변경하거나 계정을 관리하시겠어요?

---

## 예시 2: 사용자 역할 변경

**사용자**: 김철수를 ADMIN으로 바꿔줘.

**에이전트**:

[Phase 1 — IDENTIFY] 역할 변경 흐름. list_users(search="김철수") 호출.

[Phase 2 — DESIGN] 역할 변경 설계 대화

- **대상**: 김철수 (ID: 2, 현재 역할: USER)
- **변경**: USER → ADMIN
- **주의**: 기존 USER 역할은 제거되고 ADMIN 역할만 남습니다. 계속할까요?

**사용자**: 응, 변경해줘.

**에이전트**:

[Phase 3 — EXECUTE] set_user_roles 호출

```json
{
  "userId": 2,
  "roleIds": [1]
}
```

[Phase 4 — CONFIRM] 결과 요약

'김철수' 사용자의 역할이 USER → ADMIN으로 변경되었습니다.

---

## 예시 3: 계정 비활성화

**사용자**: 이영희 계정 비활성화해줘.

**에이전트**:

[Phase 1 — IDENTIFY] 계정 비활성화 흐름. list_users(search="이영희") → ID: 3 확인.

[Phase 2 — DESIGN] 비활성화 확인

이영희 (ID: 3) 계정을 비활성화하면 즉시 로그인 불가가 됩니다. 계속할까요?

**사용자**: 응.

**에이전트**:

[Phase 3 — EXECUTE] set_user_active 호출

```json
{
  "userId": 3,
  "active": false
}
```

[Phase 4 — CONFIRM] 결과 요약

'이영희' 계정이 비활성화되었습니다. 이 사용자는 로그인할 수 없습니다.
재활성화가 필요하면 "이영희 계정 다시 활성화해줘"라고 하세요.

---

## 예시 4: 역할 목록 조회 후 권한 확인

**사용자**: 어떤 역할이 있어? USER 역할에 어떤 권한이 있는지 알고 싶어.

**에이전트**:

[Phase 1 — IDENTIFY] 역할 목록 조회. list_roles() 호출.

현재 등록된 역할:

| ID | 이름 | 시스템 역할 |
|----|------|-----------|
| 1 | ADMIN | 예 |
| 2 | USER | 예 |

USER 역할(ID: 2)은 시스템 역할로 수정이 불가합니다.
USER 역할의 기본 권한: `user:read:self`, `user:write:self` (본인 프로필 조회·수정만 가능).

역할의 권한 상세는 UI 관리 페이지(/admin/roles)에서 확인하세요.
```

- [ ] **Step 2: commit**

```bash
git add apps/firehub-ai-agent/src/agent/subagents/admin-manager/examples.md
git commit --no-verify -m "feat(ai-agent): admin-manager subagent examples.md"
```

---

## Task 7: admin-api.test.ts + SL-AM-01~03

**Files:**
- Create: `apps/firehub-ai-agent/src/mcp/api-client/admin-api.test.ts`
- Modify: `apps/firehub-ai-agent/src/agent/subagent-loader.test.ts`

- [ ] **Step 1: admin-api.test.ts 작성**

```typescript
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import nock from 'nock';
import { FireHubApiClient } from '../api-client.js';

/**
 * admin-api.ts 커버리지 테스트.
 * FireHubApiClient 위임 계층을 통해 admin-api 메서드를 nock으로 검증한다.
 */

const BASE_URL = 'http://localhost:8080/api/v1';
const TOKEN = 'test-token';
const USER_ID = 1;

describe('adminApi (via FireHubApiClient)', () => {
  let client: FireHubApiClient;

  beforeEach(() => {
    nock.cleanAll();
    client = new FireHubApiClient(BASE_URL, TOKEN, USER_ID);
  });

  afterEach(() => {
    nock.cleanAll();
  });

  it('listUsers calls GET /users', async () => {
    const mock = {
      content: [{ id: 2, username: 'kim', email: 'kim@test.com', name: '김철수', isActive: true, createdAt: '2026-01-01' }],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    };
    nock(BASE_URL).get('/users').reply(200, mock);
    const result = await client.listUsers();
    expect(result).toEqual(mock);
  });

  it('listUsers passes search query param', async () => {
    const mock = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 };
    nock(BASE_URL).get('/users').query({ search: '김' }).reply(200, mock);
    const result = await client.listUsers({ search: '김' });
    expect(result.totalElements).toBe(0);
  });

  it('getUser calls GET /users/:id', async () => {
    const mock = { id: 2, username: 'kim', email: 'kim@test.com', name: '김철수', isActive: true, createdAt: '2026-01-01', roles: [{ id: 2, name: 'USER', description: null, isSystem: true }] };
    nock(BASE_URL).get('/users/2').reply(200, mock);
    const result = await client.getUser(2);
    expect(result.roles).toHaveLength(1);
    expect(result.roles[0].name).toBe('USER');
  });

  it('setUserRoles calls PUT /users/:id/roles', async () => {
    nock(BASE_URL)
      .put('/users/2/roles', (body: Record<string, unknown>) => JSON.stringify(body.roleIds) === '[1]')
      .reply(200);
    await expect(client.setUserRoles(2, [1])).resolves.toBeUndefined();
  });

  it('setUserActive calls PUT /users/:id/active', async () => {
    nock(BASE_URL)
      .put('/users/3/active', (body: Record<string, unknown>) => body.active === false)
      .reply(200);
    await expect(client.setUserActive(3, false)).resolves.toBeUndefined();
  });

  it('listRoles calls GET /roles', async () => {
    const mock = [{ id: 1, name: 'ADMIN', description: null, isSystem: true }, { id: 2, name: 'USER', description: null, isSystem: true }];
    nock(BASE_URL).get('/roles').reply(200, mock);
    const result = await client.listRoles();
    expect(result).toHaveLength(2);
    expect(result[0].name).toBe('ADMIN');
  });

  it('listPermissions calls GET /permissions', async () => {
    const mock = [{ id: 1, code: 'user:read', description: '사용자 조회', category: 'user' }];
    nock(BASE_URL).get('/permissions').reply(200, mock);
    const result = await client.listPermissions();
    expect(result[0].code).toBe('user:read');
  });

  it('listPermissions passes category query param', async () => {
    const mock = [{ id: 1, code: 'user:read', description: '사용자 조회', category: 'user' }];
    nock(BASE_URL).get('/permissions').query({ category: 'user' }).reply(200, mock);
    const result = await client.listPermissions({ category: 'user' });
    expect(result[0].category).toBe('user');
  });
});
```

- [ ] **Step 2: SL-AM-01~03 추가**

`apps/firehub-ai-agent/src/agent/subagent-loader.test.ts` 끝의 `});` (SL-DB describe 블록 종료) 뒤에 추가:

```typescript
describe('SL-AM: admin-manager subagent integration', () => {
  it('SL-AM-01: loads admin-manager from real subagents directory', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    expect(agents['admin-manager']).toBeDefined();
    expect(agents['admin-manager'].description).toContain('사용자');
    expect(agents['admin-manager'].tools).toContain('mcp__firehub__list_users');
    expect(agents['admin-manager'].tools).toContain('mcp__firehub__set_user_roles');
  });

  it('SL-AM-02: admin-manager tools include all 6 MCP tools', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const tools = agents['admin-manager'].tools;
    expect(tools).toContain('mcp__firehub__list_users');
    expect(tools).toContain('mcp__firehub__get_user');
    expect(tools).toContain('mcp__firehub__set_user_roles');
    expect(tools).toContain('mcp__firehub__set_user_active');
    expect(tools).toContain('mcp__firehub__list_roles');
    expect(tools).toContain('mcp__firehub__list_permissions');
  });

  it('SL-AM-03: admin-manager prompt inlines rules.md and examples.md content', () => {
    resetSubagentCache();
    const realSubagentsDir = path.join(__dirname, 'subagents');
    const agents = loadSubagents(realSubagentsDir);

    const prompt = agents['admin-manager'].prompt;
    // rules.md 핵심 키워드 — 권한 게이팅 표 + set_user_roles 동작 설명
    expect(prompt).toContain('role:assign');
    expect(prompt).toContain('교체(replace)');
    // examples.md 핵심 키워드 — 대화 예시 + Phase 라벨
    expect(prompt).toContain('김철수');
    expect(prompt).toContain('Phase 3 — EXECUTE');
  });
});
```

- [ ] **Step 3: 테스트 실행**

```bash
cd /Users/bluleo78/git/smart-fire-hub && pnpm --filter firehub-ai-agent test 2>&1 | tail -5
```

Expected: `Tests  358 passed (358)` (기존 344 + admin-api 7개 + SL-AM 3개 = 354; 기존 api-client 테스트가 있을 경우 수에 차이 있을 수 있음)

- [ ] **Step 4: commit**

```bash
git add apps/firehub-ai-agent/src/mcp/api-client/admin-api.test.ts apps/firehub-ai-agent/src/agent/subagent-loader.test.ts
git commit --no-verify -m "test(ai-agent): admin-api nock 테스트 + SL-AM-01~03 subagent loader 테스트"
```

---

## Task 8: E2E 테스트 — Playwright

**Files:**
- Create: `apps/firehub-web/e2e/pages/ai-chat/admin-manager.spec.ts`

- [ ] **Step 1: 테스트 파일 작성**

```typescript
/**
 * admin-manager 서브에이전트 E2E 테스트
 *
 * 시나리오:
 *   AM-01: 사용자 목록 조회 요청 → 응답에 사용자/목록 관련 키워드 포함
 *   AM-02: 사용자 역할 변경 요청 → 응답에 역할/변경 관련 키워드 포함
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

/** AM-01: 사용자 목록 조회 응답 */
const USER_LIST_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'admin-manager-session-1' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__list_users',
    input: {},
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__list_users',
    result: JSON.stringify({
      content: [
        { id: 1, username: 'admin', email: 'admin@example.com', name: '홍길동', isActive: true, createdAt: '2026-01-01' },
        { id: 2, username: 'user1', email: 'user1@example.com', name: '김철수', isActive: true, createdAt: '2026-01-02' },
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
    content: '현재 등록된 사용자 목록:\n\n| ID | 이름 | 이메일 | 상태 |\n|----|------|--------|------|\n| 1 | 홍길동 | admin@example.com | 활성 |\n| 2 | 김철수 | user1@example.com | 활성 |\n\n총 2명.',
  }),
  sseEvent({ type: 'done', inputTokens: 250 }),
];

/** AM-02: 역할 변경 응답 */
const ROLE_CHANGE_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'admin-manager-session-2' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__list_users',
    input: { search: '김철수' },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__list_users',
    result: JSON.stringify({
      content: [{ id: 2, username: 'user1', email: 'user1@example.com', name: '김철수', isActive: true, createdAt: '2026-01-02' }],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    }),
    status: 'completed',
  }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__set_user_roles',
    input: { userId: 2, roleIds: [1] },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__set_user_roles',
    result: JSON.stringify({ success: true, userId: 2, roleIds: [1] }),
    status: 'completed',
  }),
  sseEvent({
    type: 'text',
    content: "'김철수' 사용자의 역할이 USER → ADMIN으로 변경되었습니다.",
  }),
  sseEvent({ type: 'done', inputTokens: 300 }),
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
        body: JSON.stringify({ id: 1, sessionId, title: null, createdAt: '2026-04-12T00:00:00Z', updatedAt: '2026-04-12T00:00:00Z' }),
      });
    },
  );
}

/** AI 챗 사이드 패널 열기 */
async function openChatPanel(page: Page) {
  await page.getByText('AI 어시스턴트').first().click();
  await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5000 });
}

test.describe('AI 챗 admin-manager', () => {
  /**
   * AM-01: 사용자 목록 조회 요청 → 응답에 사용자/목록 관련 키워드 포함
   */
  test('AM-01: 사용자 목록 조회 요청 → 응답에 관련 키워드 포함', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'admin-manager-session-1');

    let capturedPayload: Record<string, unknown> | null = null;
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        capturedPayload = route.request().postDataJSON() as Record<string, unknown>;
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' },
          body: USER_LIST_EVENTS.join(''),
        });
      },
    );

    await openChatPanel(page);

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('현재 등록된 사용자 목록 보여줘');
    await chatInput.press('Enter');

    await expect.poll(() => capturedPayload, { timeout: 5000 }).not.toBeNull();
    expect(capturedPayload).toMatchObject({ message: '현재 등록된 사용자 목록 보여줘' });

    // 응답에 사용자 이름 표시 확인
    await expect(page.getByText(/홍길동|김철수|사용자/).first()).toBeVisible({ timeout: 30_000 });

    await page.screenshot({
      path: path.resolve(__dirname, '..', '..', '..', '..', '..', 'snapshots', 'admin-manager-user-list.png'),
      fullPage: true,
    });
  });

  /**
   * AM-02: 사용자 역할 변경 요청 → 응답에 역할/변경 관련 키워드 포함
   */
  test('AM-02: 사용자 역할 변경 요청 → 응답에 역할 변경 확인', async ({ authenticatedPage: page }) => {
    await mockAiSessions(page, 'admin-manager-session-2');

    let capturedPayload: Record<string, unknown> | null = null;
    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        capturedPayload = route.request().postDataJSON() as Record<string, unknown>;
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' },
          body: ROLE_CHANGE_EVENTS.join(''),
        });
      },
    );

    await openChatPanel(page);

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('김철수를 ADMIN으로 바꿔줘');
    await chatInput.press('Enter');

    await expect.poll(() => capturedPayload, { timeout: 5000 }).not.toBeNull();
    expect(capturedPayload).toMatchObject({ message: '김철수를 ADMIN으로 바꿔줘' });

    // 응답에 역할 변경 결과 확인
    await expect(page.getByText(/김철수|ADMIN|역할|변경/).first()).toBeVisible({ timeout: 30_000 });

    await page.screenshot({
      path: path.resolve(__dirname, '..', '..', '..', '..', '..', 'snapshots', 'admin-manager-role-change.png'),
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
cd /Users/bluleo78/git/smart-fire-hub/apps/firehub-web && pnpm exec playwright test e2e/pages/ai-chat/admin-manager.spec.ts --project=chromium 2>&1 | tail -10
```

Expected: AM-01, AM-02 PASS

- [ ] **Step 4: commit**

```bash
cd /Users/bluleo78/git/smart-fire-hub
git add apps/firehub-web/e2e/pages/ai-chat/admin-manager.spec.ts
git commit --no-verify -m "test(web/e2e): admin-manager 서브에이전트 AI 챗 E2E 테스트 추가"
```

---

## Task 9: ROADMAP.md 업데이트

**Files:**
- Modify: `docs/ROADMAP.md`

- [ ] **Step 1: 5.10.6 항목 찾기**

```bash
grep -n "5\.10\.6\|admin-manager" docs/ROADMAP.md
```

- [ ] **Step 2: ⬜ → ✅ 변경**

`5.10.6` 항목을:

```
- ✅ **5.10.6 admin-manager 서브에이전트** — 사용자 목록·상세 조회, 역할 교체, 계정 활성화/비활성화. 4단계 워크플로(IDENTIFY→DESIGN→EXECUTE→CONFIRM). MCP 도구 6개. 권한 게이팅 강화(user:read/role:assign/user:write). API 서브모듈 nock 테스트 7종. 서브에이전트 로더 테스트 3종. Playwright E2E 2종.
```

- [ ] **Step 3: commit**

```bash
git add docs/ROADMAP.md docs/superpowers/plans/2026-04-12-admin-manager-subagent.md
git commit --no-verify -m "docs(roadmap): Phase 5.10.6 admin-manager 서브에이전트 완료 표시"
```
