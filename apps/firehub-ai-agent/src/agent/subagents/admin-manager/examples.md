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
