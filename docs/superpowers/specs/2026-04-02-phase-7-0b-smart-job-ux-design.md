# Phase 7-0b: 스마트 작업 UX 개선 설계

**날짜**: 2026-04-02
**범위**: Frontend only (firehub-web)
**의존**: Phase 6-2 완료

## 요약

스마트 작업(Proactive Job)의 사용성을 개선하는 4가지 기능:
1. 작업 복제
2. Cron 프리셋 확대 + 다음 실행 시간 표시
3. 타임존 셀렉터
4. 상세 에러 메시지

---

## 1. 작업 복제

### 동작 방식
- **즉시 복제 + 편집 모드**: 복제 클릭 → 서버에 즉시 저장(비활성 상태) → 새 작업 편집 페이지로 이동
- 이름: `"${원본이름} (복사본)"`
- `enabled: false`로 생성하여 의도치 않은 실행 방지

### 버튼 위치
- **목록 페이지**: 각 행의 액션 영역에 복제 아이콘 버튼 추가 (기존 실행 버튼 옆)
- **상세 페이지**: 헤더 액션 버튼 그룹에 "복제" 버튼 추가 (편집/삭제 옆)

### 구현
- 기존 `createJob` API 활용 (별도 백엔드 변경 불필요)
- 프론트에서 원본 job 데이터를 복사하여 `createJob` 호출:
  ```ts
  const cloneData = {
    name: `${job.name} (복사본)`,
    prompt: job.prompt,
    templateId: job.templateId,
    cronExpression: job.cronExpression,
    timezone: job.timezone,
    config: job.config,
    enabled: false,
  };
  ```
- 생성 성공 시 `navigate(`/ai-insights/proactive/${newId}?edit=true`)` 로 이동

### 검증 기준
- 목록/상세 페이지에서 복제 버튼 클릭 시 새 작업 생성 확인
- 복제된 작업이 비활성 상태인지 확인
- 복제된 작업의 이름이 "(복사본)" 접미사를 가지는지 확인
- 편집 모드로 진입하는지 확인

---

## 2. Cron 프리셋 확대

### 프리셋 목록 (기존 5개 + 신규 4개)

| 순서 | 라벨 | Cron 표현식 | 신규 |
|------|------|------------|------|
| 1 | 매시간 | `0 * * * *` | |
| 2 | 매 30분 | `*/30 * * * *` | ✨ |
| 3 | 매일 오전 8시 | `0 8 * * *` | |
| 4 | 매일 오전 9시 | `0 9 * * *` | |
| 5 | 매일 오후 6시 | `0 18 * * *` | ✨ |
| 6 | 매주 월요일 오전 9시 | `0 9 * * 1` | |
| 7 | 매주 금요일 오전 9시 | `0 9 * * 5` | ✨ |
| 8 | 매월 1일 오전 9시 | `0 9 1 * *` | ✨ |
| 9 | 직접 입력 | `__custom__` | |

### cron-label.ts 업데이트
- `LABELS` 맵에 신규 4개 프리셋의 라벨 추가
- 목록 페이지 등에서 자동으로 한글 라벨 표시

### 검증 기준
- 편집 폼에서 9개 프리셋이 모두 표시되는지 확인
- 각 프리셋 선택 시 올바른 cron 표현식이 설정되는지 확인
- 목록 페이지에서 신규 프리셋의 한글 라벨이 표시되는지 확인

---

## 3. 타임존 셀렉터

### 구현
- 기존 텍스트 input → 검색 가능한 Combobox 드롭다운으로 교체
- 기존 shadcn/ui `Select` 또는 `Combobox` 패턴 활용

### 타임존 목록

| 값 | 표시 | UTC 오프셋 |
|----|------|-----------|
| Asia/Seoul | Asia/Seoul (KST) | UTC+9 |
| Asia/Tokyo | Asia/Tokyo (JST) | UTC+9 |
| Asia/Shanghai | Asia/Shanghai (CST) | UTC+8 |
| Asia/Singapore | Asia/Singapore (SGT) | UTC+8 |
| America/New_York | America/New_York (EST) | UTC-5 |
| America/Chicago | America/Chicago (CST) | UTC-6 |
| America/Denver | America/Denver (MST) | UTC-7 |
| America/Los_Angeles | America/Los_Angeles (PST) | UTC-8 |
| Europe/London | Europe/London (GMT) | UTC+0 |
| Europe/Paris | Europe/Paris (CET) | UTC+1 |
| Europe/Berlin | Europe/Berlin (CET) | UTC+1 |
| Australia/Sydney | Australia/Sydney (AEST) | UTC+10 |
| Pacific/Auckland | Pacific/Auckland (NZST) | UTC+12 |
| UTC | UTC | UTC+0 |

### 검증 기준
- 타임존 셀렉터가 드롭다운으로 표시되는지 확인
- 기본값이 "Asia/Seoul"인지 확인
- 선택한 타임존이 저장/로드 시 정상 반영되는지 확인

---

## 4. 다음 실행 시간 표시

### 표시 위치

**목록 페이지 (ProactiveJobListPage)**:
- 테이블에 "다음 실행" 컬럼 추가
- API의 `nextExecuteAt` 필드 사용
- 표시 형식: 상대 시간 (`내일 09:00`, `4월 7일 09:00`)
- 비활성 작업은 `-` 표시

**편집 폼 (JobOverviewTab)**:
- 스케줄 섹션 하단에 인포 박스로 표시
- 표시 형식: `📅 다음 실행: 2026-04-03 (목) 09:00 KST`
- `cron-parser` 라이브러리로 프론트에서 계산 (이미 package.json에 포함)
- 스케줄 또는 타임존 변경 시 실시간 업데이트

### 읽기 전용 뷰:
- 기존 스케줄 카드에 다음 실행 시간 추가 표시

### 검증 기준
- 목록 페이지에 다음 실행 컬럼이 표시되는지 확인
- 편집 폼에서 스케줄/타임존 변경 시 다음 실행 시간이 실시간 업데이트되는지 확인
- 비활성 작업의 다음 실행 시간이 `-`로 표시되는지 확인
- cron-parser 계산 결과가 정확한지 확인

---

## 5. 상세 에러 메시지

### 에러 유형 분류 (프론트엔드 패턴 매칭)

| 유형 | 아이콘 | 매칭 키워드 | 가이드 메시지 |
|------|--------|------------|-------------|
| AI 모델 오류 | 🔴 | `rate limit`, `token`, `claude`, `api`, `overloaded` | 잠시 후 수동 실행을 시도하거나, 스케줄 간격을 늘려보세요. |
| 데이터 접근 실패 | 🟠 | `connection`, `timeout`, `database`, `query` | 데이터 연결 상태를 확인해주세요. |
| 채널 전달 실패 | 🟡 | `email`, `smtp`, `delivery`, `channel` | 채널 설정(이메일/SMTP)을 확인해주세요. |
| 기타 | ⚪ | (매칭 없음) | 관리자에게 문의해주세요. |

### 구현
- `classifyError(errorMessage: string)` 유틸리티 함수 생성
- `errorMessage`를 소문자 변환 후 키워드 매칭
- 실행 이력 탭의 에러 표시 영역을 분류 카드로 교체:
  - 에러 유형 + 아이콘
  - 원본 에러 메시지
  - 해결 가이드

### 검증 기준
- 각 에러 유형별 키워드가 올바르게 분류되는지 확인
- 매칭되지 않는 에러가 "기타"로 분류되는지 확인
- 에러 카드에 유형/메시지/가이드가 모두 표시되는지 확인

---

## 영향받는 파일

| 파일 | 변경 내용 |
|------|----------|
| `ProactiveJobListPage.tsx` | 복제 버튼, 다음 실행 컬럼 추가 |
| `ProactiveJobDetailPage.tsx` | 복제 버튼, 복제 mutation 추가 |
| `JobOverviewTab.tsx` | 프리셋 확대, 타임존 셀렉터, 다음 실행 인포 박스 |
| `JobExecutionsTab.tsx` | 에러 분류 카드 표시 |
| `cron-label.ts` | 신규 프리셋 라벨 추가 |
| `proactive-job.ts` (validation) | 타임존 enum 검증 추가 |
| 신규: `error-classifier.ts` | 에러 분류 유틸리티 |
| 신규: `timezone-data.ts` | 타임존 목록 상수 |
| `useProactiveMessages.ts` | 복제 mutation (useCloneProactiveJob) 추가 |

---

## 범위 외

- 백엔드 API 변경 없음 (기존 createJob API로 복제 구현)
- cron 표현식 커스텀 빌더 UI (직접 입력으로 대체)
- 에러 재시도 기능 (7-1 이후 검토)
