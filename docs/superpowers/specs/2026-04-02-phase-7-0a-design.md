# Phase 7-0a: 이메일/채팅 수신자 지정 + 실행 결과 보기 — 설계 스펙

> **작성일**: 2026-04-02
> **의존**: Phase 6-2 (프로액티브 AI)
> **범위**: Backend + Frontend

---

## 1. 목표

1. 스마트 작업의 각 전달 채널(채팅, 이메일)에 수신자를 개별 지정할 수 있다.
2. 스마트 작업 상세 페이지를 신설하여 실행 이력과 결과를 확인할 수 있다.

---

## 2. 현재 상태 (As-Is)

| 항목 | 현재 동작 |
|------|----------|
| 채팅 전달 | 작업 생성자에게만 `proactive_message` 생성 + SSE 알림 |
| 이메일 전달 | 작업 생성자의 이메일로만 발송 (`userRepository.findById(job.userId()).map(u -> u.email())`) |
| config 구조 | `{ channels: ['CHAT', 'EMAIL'], targets: 'ALL' \| 'SELECTED' }` |
| 실행 이력 API | `GET /api/v1/proactive/jobs/{id}/executions` — 존재하지만 프론트엔드 미연결 |
| 스마트 작업 UI | `ProactiveJobsTab.tsx` 내 테이블 + 생성/편집 다이얼로그, 상세 페이지 없음 |
| "결과 보기" | 실행 시작 토스트에 버튼 존재하지만 동작 미구현 |

---

## 3. 설계

### 3.1 데이터 모델

#### config JSONB 구조 변경

**Before:**
```json
{
  "channels": ["CHAT", "EMAIL"],
  "targets": "ALL"
}
```

**After:**
```json
{
  "channels": [
    {
      "type": "CHAT",
      "recipientUserIds": [1, 2, 3]
    },
    {
      "type": "EMAIL",
      "recipientUserIds": [1],
      "recipientEmails": ["ceo@partner.co.kr"]
    }
  ]
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `channels[].type` | `string` | 채널 타입: `"CHAT"` 또는 `"EMAIL"` |
| `channels[].recipientUserIds` | `number[]` | 등록 사용자 ID 목록 (채팅/이메일 공통) |
| `channels[].recipientEmails` | `string[]` | 외부 이메일 주소 목록 (이메일 채널 전용) |

**규칙:**
- `recipientUserIds`가 빈 배열이면 작업 생성자에게만 전달 (기존 동작 유지)
- `recipientEmails`는 이메일 채널에만 존재. RFC 5322 형식 검증
- 채팅 채널의 수신자는 등록 사용자만 가능 (시스템에 계정이 있어야 알림 수신)
- 이메일 채널은 등록 사용자 + 외부 이메일 모두 가능

**하위 호환성:**
- 기존 `{ channels: ['CHAT', 'EMAIL'], targets: '...' }` 형식의 config는 DB 마이그레이션 불필요
- `ProactiveConfigParser`에서 두 형식 모두 파싱 가능하도록 처리
- 기존 형식(channels가 문자열 배열)은 `recipientUserIds: []`(생성자에게만 전달)로 해석
- 기존 `targets` 필드는 채널별 수신자로 대체되어 새 형식에서는 사용하지 않음. 구 형식 파싱 시에만 참조

---

### 3.2 백엔드 변경

#### 3.2.1 ChatDeliveryChannel 수정

**현재**: 작업 생성자(`job.userId()`)에게만 `proactive_message` 1건 생성.

**변경**: config의 `recipientUserIds`에 지정된 각 사용자에게 `proactive_message`를 개별 생성하고, 각 사용자에게 SSE `NotificationEvent`를 발행.

```
deliver(job, executionId, result):
  userIds = config.channels[CHAT].recipientUserIds
  if userIds is empty:
    userIds = [job.userId()]  // 기존 동작 유지
  
  for each userId in userIds:
    create proactive_message(userId, executionId, ...)
    broadcast NotificationEvent(userId, ...)
```

#### 3.2.2 EmailDeliveryChannel 수정

**현재**: `userRepository.findById(job.userId()).map(u -> u.email())`로 생성자 이메일만 사용.

**변경**: config의 `recipientUserIds` + `recipientEmails`로 수신자 목록 구성.

```
deliver(job, executionId, result):
  emails = []
  
  // 등록 사용자 이메일 조회
  userIds = config.channels[EMAIL].recipientUserIds
  for each userId in userIds:
    emails.add(userRepository.findById(userId).email)
  
  // 외부 이메일 추가
  emails.addAll(config.channels[EMAIL].recipientEmails)
  
  // 미지정 시 생성자 이메일
  if emails is empty:
    emails = [userRepository.findById(job.userId()).email]
  
  // 각 수신자에게 개별 발송 (To 헤더에 1명씩)
  for each email in emails:
    sendEmail(email, result)
```

#### 3.2.3 Config 파싱 유틸리티

`ProactiveConfigParser` 유틸리티 클래스 신규:
- `parseChannels(Map<String, Object> config)`: 신/구 두 형식 모두 파싱
- `getChannelConfig(config, channelType)`: 특정 채널의 설정 추출
- `getRecipientUserIds(channelConfig)`: 사용자 ID 목록 반환
- `getRecipientEmails(channelConfig)`: 이메일 목록 반환

#### 3.2.4 이메일 주소 검증

`CreateProactiveJobRequest` / `UpdateProactiveJobRequest`에서 config 내 `recipientEmails` 배열의 각 항목에 RFC 5322 이메일 형식 검증 적용. 잘못된 형식이면 400 Bad Request 반환.

---

### 3.3 프론트엔드 변경

#### 3.3.1 라우팅 추가

```
/ai-insights/jobs              → ProactiveJobListPage (목록, 신규)
/ai-insights/jobs/new          → ProactiveJobDetailPage (생성 모드)
/ai-insights/jobs/:id          → ProactiveJobDetailPage (상세/편집)
/ai-insights/jobs/:id?tab=executions → 실행 이력 탭 직접 진입
```

기존 `ProactiveJobsTab.tsx`(관리자 탭)는 새 목록 페이지로 대체. AI 인사이트 섹션의 독립 페이지로 승격.

#### 3.3.2 ProactiveJobListPage (목록)

기존 `ProactiveJobsTab.tsx` 테이블을 독립 페이지로 이동. 상세 페이지가 생기므로 컬럼 간소화:

| 컬럼 | 내용 |
|------|------|
| 작업명 | 작업 이름 + 채널 뱃지 요약 (💬 3 · 📧 2) |
| 실행 주기 | Cron 자연어 표현 (매일 09:00) |
| 마지막 실행 | 상태 뱃지 + 상대시간 (✓ 완료 · 2시간 전) |
| 활성 | Switch 토글 |

- 행 클릭 → `/ai-insights/jobs/:id` 네비게이션
- "새 작업" 버튼 → `/ai-insights/jobs/new`
- 행 액션 버튼 제거 (상세 페이지에서 처리)

#### 3.3.3 ProactiveJobDetailPage (상세)

파이프라인 `PipelineEditorPage` 패턴 참조. 2탭 구조:

**헤더:**
- ← 뒤로가기 + 작업명 + 활성 뱃지
- 액션 버튼: "▶ 지금 실행" / "✎ 편집" / "🗑 삭제"
- 편집 모드에서는: "취소" / "저장"

**개요 탭** (읽기/편집 모드 전환):

읽기 모드:
- 기본 정보 카드: 작업명, 템플릿, 생성일
- 실행 주기 카드: 스케줄, 타임존, 다음 실행 시간
- 프롬프트 전문
- 전달 채널 + 수신자 요약 카드
- 마지막 실행 상태 카드

편집 모드 ("✎ 편집" 클릭 시):
- 기본 설정: 작업명, 템플릿 선택, 프롬프트 textarea, 활성 토글
- 실행 주기: Cron 프리셋 + 타임존 셀렉터 + 다음 실행 미리보기
- 전달 채널: 채널별 수신자 관리 (아래 3.3.4 참조)

생성 모드 (`/ai-insights/jobs/new`): 편집 모드와 동일한 폼, "생성" 버튼

**실행 이력 탭:**

상단(고정 ~200px, 자체 스크롤) + 하단(나머지, 자체 스크롤) 2분할:

상단 — 실행 목록 테이블:
| 컬럼 | 내용 |
|------|------|
| 실행 시간 | 절대시간 + 상대시간 |
| 상태 | 뱃지 (완료/실패/실행 중) |
| 소요 시간 | 초 단위 |
| 전달 | 채널별 수신자 수 뱃지 (FAILED 시 에러 메시지) |

- 행 클릭 → 하단에 해당 실행 결과 표시
- 선택된 행은 좌측 파란 보더 + 배경 하이라이트
- "더 보기" 버튼으로 페이지네이션 (기존 API `limit`/`offset` 활용)

하단 — 선택된 실행 결과:
- 기존 `AINotificationPanel`의 Detail 뷰 렌더링 로직 재사용
- ProactiveResult의 섹션별 렌더링 (cards → 지표 카드 그리드, text → 마크다운, list → 목록, chart → 차트 이미지)
- RUNNING 상태: 스피너 + 5초 간격 자동 폴링
- FAILED 상태: 에러 메시지 표시
- 실행 없음: "아직 실행 이력이 없습니다" 빈 상태

#### 3.3.4 채널별 수신자 입력 UI

전달 채널을 **수직 카드 배열**로 표시. 각 채널 체크 시 아래에 수신자 영역이 펼쳐짐.

**채팅 채널:**
- 체크박스 + 💬 아이콘 + "채팅" + 설명
- 펼침 영역: 등록 사용자 검색/선택 (UserCombobox)
- 태그 형태 표시: 이니셜 아바타 + 이름 + ✕ 삭제
- 등록 사용자만 선택 가능

**이메일 채널:**
- 체크박스 + 📧 아이콘 + "이메일" + 설명
- 펼침 영역: 등록 사용자 검색 + 외부 이메일 직접 입력
- 등록 사용자: 이니셜 아바타 + 이름 태그
- 외부 이메일: @ 아이콘 + 이메일 주소 태그
- Enter로 외부 이메일 추가 (RFC 5322 실시간 검증)

**공통:**
- 미지정 시 "본인에게만 전달" 안내 텍스트
- 채널 해제 시 수신자 영역 접힘 (입력값 유지)
- 카드 우측에 수신자 수 뱃지 표시

#### 3.3.5 UserCombobox 컴포넌트

기존 `DatasetCombobox.tsx` 패턴 참조하여 `UserCombobox.tsx` 신규 생성.

- Radix Command + Popover 패턴
- `GET /api/v1/users?search={query}` 호출 (기존 API 활용)
- 멀티셀렉트 모드 (선택된 사용자는 Check 아이콘 표시)
- 선택된 사용자를 태그 목록으로 외부 표시 (컴포넌트 외부에서 렌더링)
- 디바운스 검색 (300ms)

#### 3.3.6 API 클라이언트 추가

`proactive.ts`에 추가:
```typescript
getJobExecutions(jobId: number, params: { limit?: number; offset?: number }): 
  Promise<ProactiveJobExecutionResponse[]>
```

`useProactiveMessages.ts`에 추가:
```typescript
useJobExecutions(jobId: number, params: { limit?: number; offset?: number })
```

#### 3.3.7 토스트 "결과 보기" 연결

실행 시작 토스트의 "결과 보기" 버튼 클릭 시:
```typescript
navigate(`/ai-insights/jobs/${jobId}?tab=executions`)
```

---

### 3.4 네비게이션 흐름 요약

```
목록 페이지
  ├─ 행 클릭 → /ai-insights/jobs/:id (개요 탭)
  ├─ "새 작업" → /ai-insights/jobs/new (생성 모드)
  └─ ▶ 실행 버튼 → 토스트 "결과 보기" → /ai-insights/jobs/:id?tab=executions

상세 페이지
  ├─ 개요 탭 (읽기) → "편집" 클릭 → 개요 탭 (편집)
  ├─ 실행 이력 탭 → 행 클릭 → 하단 결과 표시
  └─ ← 뒤로가기 → 목록 페이지
```

---

## 4. 변경 파일 목록

### Backend (firehub-api)

| 파일 | 변경 내용 |
|------|----------|
| `proactive/service/delivery/ChatDeliveryChannel.java` | 수신자별 proactive_message 생성 + SSE 알림 |
| `proactive/service/delivery/EmailDeliveryChannel.java` | config에서 수신자 목록 조회 후 개별 발송 |
| `proactive/util/ProactiveConfigParser.java` | **신규** — config 파싱 유틸리티 (신/구 형식 호환) |
| `proactive/dto/CreateProactiveJobRequest.java` | config 내 이메일 형식 검증 추가 |
| `proactive/dto/UpdateProactiveJobRequest.java` | config 내 이메일 형식 검증 추가 |

### Frontend (firehub-web)

| 파일 | 변경 내용 |
|------|----------|
| `src/App.tsx` | 라우트 추가: `/ai-insights/jobs`, `/ai-insights/jobs/new`, `/ai-insights/jobs/:id` |
| `src/pages/ai-insights/ProactiveJobListPage.tsx` | **신규** — 목록 페이지 (기존 ProactiveJobsTab 대체) |
| `src/pages/ai-insights/ProactiveJobDetailPage.tsx` | **신규** — 상세 페이지 (개요 + 실행 이력 2탭) |
| `src/pages/ai-insights/tabs/JobOverviewTab.tsx` | **신규** — 개요 탭 (읽기/편집 모드) |
| `src/pages/ai-insights/tabs/JobExecutionsTab.tsx` | **신규** — 실행 이력 탭 (목록 + 결과) |
| `src/components/UserCombobox.tsx` | **신규** — 사용자 검색/선택 컴포넌트 |
| `src/api/proactive.ts` | `getJobExecutions()` 추가 |
| `src/hooks/queries/useProactiveMessages.ts` | `useJobExecutions()` 훅 추가 |
| `src/pages/admin/ProactiveJobsTab.tsx` | 삭제 또는 새 목록 페이지로 리다이렉트 |

---

## 5. 검증 기준

### 수신자 지정
- [ ] 채팅 수신자 3명 지정 → 3명 모두 알림 패널에 리포트 수신
- [ ] 이메일 수신자 2명(등록1 + 외부1) 지정 → 2명 모두 이메일 수신
- [ ] 수신자 미지정 시 작업 생성자에게만 전달 (기존 동작 유지)
- [ ] 잘못된 이메일 형식 입력 시 실시간 검증 에러 + 서버 400 응답
- [ ] 기존 config 형식(구 형식) 작업이 정상 동작 (하위 호환)

### 상세 페이지
- [ ] 목록 행 클릭 → 상세 페이지 (개요 탭) 정상 네비게이션
- [ ] 개요 탭 읽기 모드: 모든 정보 정상 표시
- [ ] "편집" → 편집 모드 전환, 채널별 수신자 수정 가능
- [ ] "저장" → 변경사항 반영, 읽기 모드로 복귀
- [ ] `/ai-insights/jobs/new` → 새 작업 생성 → 생성 후 상세 페이지로 이동

### 실행 이력
- [ ] 실행 이력 탭: 목록 테이블 정상 표시 (시간, 상태, 소요, 전달)
- [ ] 행 클릭 → 하단에 리포트 결과 렌더링 (카드/텍스트/차트 섹션)
- [ ] COMPLETED/FAILED/RUNNING 상태 각각 정상 표시
- [ ] RUNNING 상태 → 5초 폴링 → 완료 시 자동 갱신
- [ ] 토스트 "결과 보기" → 실행 이력 탭으로 이동, 해당 실행 선택

### 기본 품질
- [ ] `pnpm build` 성공
- [ ] `pnpm typecheck` 성공
- [ ] 기존 백엔드 테스트 통과
- [ ] 신규 백엔드 테스트: ProactiveConfigParser, ChatDeliveryChannel 수신자 분배, EmailDeliveryChannel 수신자 분배
