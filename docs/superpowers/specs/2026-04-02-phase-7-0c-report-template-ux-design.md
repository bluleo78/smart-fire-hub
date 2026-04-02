# Phase 7-0c: 리포트 템플릿 UX 개선 — 설계 문서

> **작성일**: 2026-04-02
> **상태**: 승인됨
> **의존**: Phase 6-2 (프로액티브 AI)
> **범위**: Frontend (firehub-web)

---

## 1. 목표

리포트 템플릿의 생성/편집/조회 경험을 개선한다.

- 빌트인 템플릿을 복제하여 커스터마이징할 수 있게 한다
- 9가지 섹션 타입별 가이드와 JSON 스니펫 삽입 기능을 제공한다
- plain textarea를 CodeMirror JSON 에디터로 교체하여 실시간 검증 + 구문 강조를 지원한다
- 섹션 구조를 시각적으로 미리볼 수 있게 한다

---

## 2. 주요 변경 사항

### 2.1 템플릿 상세 페이지 도입

현재 템플릿은 목록 페이지(`ReportTemplatesTab`)에서 카드로만 표시되고, 편집은 Dialog 모달에서 이루어진다. 이를 **전용 상세 페이지**로 확장한다.

**라우트:**
- `/ai-insights/templates/:id` — 기존 템플릿 상세/편집
- `/ai-insights/templates/new` — 새 템플릿 생성 (편집 모드로 진입)

**참조 패턴:** `ProactiveJobDetailPage` + 디자인 시스템 `05-page-patterns.md` Pattern B (Detail Page)

**읽기 모드:**
- 헤더: 뒤로가기 버튼 + 템플릿 이름 + 배지(커스텀/기본) + 액션 버튼(복제/편집/삭제)
  - 빌트인 템플릿: 편집/삭제 버튼 없음, 복제 버튼만 표시
- 메타 정보: 설명, 생성일, 수정일, 사용 중인 작업 수
- 좌측: JSON 구조 읽기전용 표시 (CodeMirror readonly 모드, 구문 강조 적용)
- 우측: 섹션 구조 미리보기 (타입별 색상 + 아이콘 카드)

**편집 모드:**
- 헤더: 뒤로가기 + 템플릿 이름 + "편집 중" 배지 + 취소/저장 버튼
- 이름/설명 필드 인라인 편집 가능
- 좌측: CodeMirror JSON 에디터 (편집 가능) + 삽입 툴바
- 우측: 가이드/미리보기 탭 전환 사이드패널

### 2.2 9가지 섹션 타입

각 섹션은 `type` 필드로 구분한다. 기존 섹션 스키마에 `type` 필드를 추가한다.

```typescript
interface TemplateSection {
  key: string;           // 고유 식별자 (예: "summary", "kpi")
  type: SectionType;     // 섹션 타입
  label: string;         // 표시 이름 (예: "주간 요약")
  description?: string;  // 섹션 설명 (AI에게 전달)
  required?: boolean;    // 필수 여부
}

type SectionType =
  | 'text'           // 마크다운 서술형 텍스트
  | 'cards'          // KPI 지표 카드
  | 'list'           // 항목 나열
  | 'table'          // 행/열 데이터
  | 'comparison'     // 기간 비교
  | 'alert'          // 경고/알림
  | 'timeline'       // 시간순 이벤트
  | 'chart'          // 차트 참조/설명
  | 'recommendation' // 권고사항
```

**각 타입별 정의:**

| 타입 | 아이콘 | 색상 | 설명 | AI 생성 시 출력 형태 |
|------|--------|------|------|---------------------|
| `text` | 📝 | blue | 마크다운 서술형 텍스트 | `{ content: "markdown string" }` |
| `cards` | 📊 | amber | 핵심 수치 카드 (KPI, 통계) | `{ items: [{ title, value, change?, trend? }] }` |
| `list` | 📋 | slate | 항목 나열 (이슈, 변경사항) | `{ items: [{ text, severity? }] }` |
| `table` | 📑 | indigo | 행/열 구조 데이터 | `{ headers: [...], rows: [[...]] }` |
| `comparison` | 🔄 | purple | 기간 비교 (전주/전월 대비) | `{ items: [{ label, previous, current, changeRate }] }` |
| `alert` | ⚠️ | red | 경고/알림 (임계값 초과 등) | `{ items: [{ level: "danger"|"warning"|"info", message }] }` |
| `timeline` | 🕐 | cyan | 시간순 이벤트 나열 | `{ events: [{ time, description, status? }] }` |
| `chart` | 📈 | green | 차트/그래프 설명 | `{ chartType, description, data? }` |
| `recommendation` | 💡 | emerald | AI 권고사항 | `{ items: [{ priority, action, expectedEffect? }] }` |

### 2.3 CodeMirror JSON 에디터

현재 plain `<textarea>`를 CodeMirror 6 JSON 에디터로 교체한다.

**신규 의존성:** `@codemirror/lang-json`

**기능:**
- JSON 구문 강조 (기존 CodeMirror 테마 `@codemirror/theme-one-dark` 재사용)
- 실시간 JSON 검증: 파싱 에러 시 에디터 하단에 에러 메시지 + 라인 하이라이트
- 읽기 모드에서는 `EditorView.editable.of(false)` + `EditorState.readOnly.of(true)`
- 편집 모드에서는 완전 편집 가능

**삽입 툴바:**
- 에디터 상단에 9개 섹션 타입 버튼 배치
- 클릭 시 현재 커서 위치(또는 `sections` 배열 마지막)에 해당 타입의 JSON 스니펫 삽입
- 삽입되는 스니펫 예시 (`text` 타입):
  ```json
  {
    "key": "new_text",
    "type": "text",
    "label": "새 텍스트 섹션",
    "description": "이 섹션에 대한 설명을 입력하세요"
  }
  ```

### 2.4 사이드패널 (가이드 / 미리보기 탭)

편집 모드에서 에디터 우측에 사이드패널을 표시한다. 두 개의 탭으로 전환 가능.

**가이드 탭:**
- 9가지 섹션 타입을 카드 목록으로 표시
- 각 카드: 아이콘 + 타입명 + 한줄 설명
- 카드 클릭 시 상세 정보 펼침: JSON 스니펫 예시 + 필드 설명
- 현재 JSON에서 사용 중인 타입은 "사용중 ×N" 표시

**미리보기 탭:**
- 현재 JSON을 파싱하여 섹션 구조를 카드 형태로 시각화
- 각 카드: 타입별 색상 왼쪽 보더 + 아이콘 + 라벨 + required 배지
- JSON 파싱 실패 시 "JSON을 수정해주세요" 안내 표시
- 섹션 수 표시

### 2.5 빌트인 템플릿 복제

- 빌트인 템플릿 상세 페이지에서 **"복제" 버튼** 표시
- 클릭 시:
  1. `POST /api/v1/proactive/templates` 호출 (name에 "(사본)" 추가, `builtin: false`)
  2. 성공 시 toast 알림 + 커스텀 템플릿 목록에 추가
  3. 새 복사본의 상세 페이지(읽기 모드)로 이동
- 커스텀 템플릿에도 복제 버튼 제공 (동일 로직)

### 2.6 새 템플릿 생성

- 목록 페이지에서 "템플릿 추가" 클릭 → `/ai-insights/templates/new`로 네비게이션
- 상세 페이지가 편집 모드로 바로 열림 (빈 템플릿)
- 기본 JSON 구조 제공:
  ```json
  {
    "sections": []
  }
  ```
- 저장 시 `POST /api/v1/proactive/templates` → 성공 시 해당 템플릿 상세 페이지(읽기 모드)로 리다이렉트

### 2.7 목록 페이지 변경

기존 `ReportTemplatesTab`에서 카드 클릭 시 상세 페이지로 이동하도록 변경한다.

- 카드 클릭 → `navigate(\`/ai-insights/templates/${template.id}\`)`
- 기존 Dialog 기반 생성/편집 로직 제거
- "템플릿 추가" 버튼 → `navigate('/ai-insights/templates/new')`

---

## 3. 영향받는 파일

### 신규 생성
| 파일 | 역할 |
|------|------|
| `src/pages/ai-insights/ReportTemplateDetailPage.tsx` | 템플릿 상세/편집 페이지 |
| `src/pages/ai-insights/components/TemplateJsonEditor.tsx` | CodeMirror JSON 에디터 + 삽입 툴바 |
| `src/pages/ai-insights/components/TemplateSidePanel.tsx` | 가이드/미리보기 사이드패널 |
| `src/pages/ai-insights/components/SectionPreview.tsx` | 섹션 구조 미리보기 카드 |
| `src/lib/template-section-types.ts` | 섹션 타입 정의 (타입, 아이콘, 색상, 스니펫, 설명) |

### 수정
| 파일 | 변경 내용 |
|------|-----------|
| `src/pages/admin/ReportTemplatesTab.tsx` | 카드 클릭 시 상세 페이지 이동, Dialog 제거 |
| `src/api/proactive.ts` | `TemplateSection`, `SectionType` 타입 추가 |
| `src/App.tsx` (또는 라우터 설정 파일) | `/ai-insights/templates/:id`, `/ai-insights/templates/new` 라우트 추가 |

### 의존성 추가
| 패키지 | 용도 |
|--------|------|
| `@codemirror/lang-json` | CodeMirror JSON 언어 지원 |

---

## 4. 설계 결정 기록

| 결정 | 선택 | 이유 |
|------|------|------|
| 섹션 타입 수 | 9가지 전부 | 풍부한 리포트 구성을 위해 |
| 편집 컨테이너 | 상세 페이지 + 편집 모드 | 기존 ProactiveJobDetailPage 패턴 일관성, URL 지원, 충분한 공간 |
| 에디터 레이아웃 | 사이드패널 + 삽입 버튼 (C) | 가이드 참조 + 빠른 삽입 모두 지원 |
| 미리보기 수준 | 구조 시각화 (A) | 7-0c 범위에 적합, 비주얼 빌더는 7-5에서 |
| 복제 플로우 | 즉시 복사본 생성 → 목록에 추가 | 사용자가 원할 때 편집 버튼으로 수정 |
| 새 템플릿 생성 | 상세 페이지 편집 모드로 직행 | 별도 모달 불필요, 일관성 |

---

## 5. 검증 기준

### 빌드/타입 검증
- [ ] `pnpm typecheck` 통과
- [ ] `pnpm build` 통과
- [ ] `pnpm lint` 통과

### 기능 검증
- [ ] 목록에서 템플릿 카드 클릭 → 상세 페이지 이동
- [ ] 읽기 모드: JSON 구문 강조 + 섹션 미리보기 표시
- [ ] 편집 버튼 → 편집 모드 전환, 취소 → 읽기 모드 복귀
- [ ] 편집 모드: 이름/설명 수정 + JSON 편집 + 저장 동작
- [ ] 삽입 버튼 클릭 → 해당 타입 JSON 스니펫 에디터에 삽입
- [ ] 가이드 탭: 9가지 타입 목록 + 클릭 시 상세 펼침
- [ ] 미리보기 탭: JSON 파싱 → 섹션 구조 카드 표시
- [ ] JSON 문법 오류 시 실시간 에러 메시지 표시
- [ ] 빌트인 템플릿 "복제" → 커스텀 복사본 생성
- [ ] "템플릿 추가" → `/templates/new` → 편집 모드 진입 → 저장 → 상세 페이지 리다이렉트
- [ ] Playwright 스크린샷으로 UI 확인

### 비기능 검증
- [ ] 빌트인 템플릿에 편집/삭제 버튼 미표시
- [ ] 존재하지 않는 템플릿 ID 접근 시 404 또는 목록으로 리다이렉트
- [ ] 다크/라이트 테마 모두 정상 렌더링

---

## 6. 범위 외 (Not In Scope)

- 섹션 드래그앤드롭 순서 변경 → Phase 7-5 (비주얼 빌더)
- 샘플 데이터 포함 실제 렌더링 미리보기 → Phase 7-5
- 백엔드 섹션 타입 스키마 검증 → 프론트엔드에서만 가이드
- AI 에이전트의 섹션 타입별 렌더링 개선 → 별도 작업
