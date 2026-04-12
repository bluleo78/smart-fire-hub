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
