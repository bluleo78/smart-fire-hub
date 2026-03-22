# AI 상태 칩 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI 어시스턴트 진입점을 사이드바 버튼에서 메인 콘텐츠 상단 중앙의 플로팅 상태 칩으로 교체한다.

**Architecture:** 새로운 `AIStatusChip` + `AIStatusChipDropdown` 컴포넌트를 생성하고, 기존 사이드바 AI 버튼과 플로팅 모드를 제거한다. 기존 AIProvider의 상태 관리 인터페이스(`toggleAI`, `setMode`, `openAI`, `closeAI`)는 그대로 활용하며, 칩이 이를 조합하여 모드 로테이션을 구현한다.

**Tech Stack:** React 19 + TypeScript, Tailwind CSS v4, shadcn/ui, Lucide Icons

**Spec:** `docs/superpowers/specs/2026-03-22-ai-status-chip-design.md`

---

## 파일 구조 개요

### 새로 생성
| 파일 | 역할 |
|------|------|
| `apps/firehub-web/src/components/ai/AIStatusChip.tsx` | 상태 칩 본체 — 상태 표시 + 클릭 로테이션 + 호버 드롭다운 트리거 |
| `apps/firehub-web/src/components/ai/AIStatusChipDropdown.tsx` | 호버 드롭다운 — 세션 정보, 빠른 입력, 모드 전환, 세션 관리 |

### 수정
| 파일 | 변경 |
|------|------|
| `apps/firehub-web/src/types/ai.ts` | `AIMode`에서 `'floating'` 제거 |
| `apps/firehub-web/src/components/ai/AIProvider.tsx` | 플로팅 모드 관련 로직 제거 |
| `apps/firehub-web/src/components/ai/AIChatPanel.tsx` | 모드 전환 버튼에서 플로팅 제거 |
| `apps/firehub-web/src/components/layout/AppLayout.tsx` | AINavButton 제거, AIStatusChip 추가, AIFloating 렌더링 제거 |

### 삭제
| 파일 | 사유 |
|------|------|
| `apps/firehub-web/src/components/ai/AIFloating.tsx` | 플로팅 모드 제거 |
| `apps/firehub-web/src/components/ai/AIToggleButton.tsx` | 이미 미사용 |

---

## Task 1: 플로팅 모드 제거 + 타입 정리

레거시 코드를 먼저 정리하여 깨끗한 기반 위에 새 컴포넌트를 구축한다.

**Files:**
- Modify: `apps/firehub-web/src/types/ai.ts`
- Modify: `apps/firehub-web/src/components/ai/AIProvider.tsx`
- Modify: `apps/firehub-web/src/components/ai/AIChatPanel.tsx`
- Modify: `apps/firehub-web/src/components/layout/AppLayout.tsx`
- Delete: `apps/firehub-web/src/components/ai/AIFloating.tsx`
- Delete: `apps/firehub-web/src/components/ai/AIToggleButton.tsx`

- [ ] **Step 1: `AIMode` 타입에서 `'floating'` 제거**

`apps/firehub-web/src/types/ai.ts`에서:
```typescript
// Before
export type AIMode = 'side' | 'floating' | 'fullscreen';

// After
export type AIMode = 'side' | 'fullscreen';
```

- [ ] **Step 2: `AIProvider.tsx`에서 플로팅 관련 로직 제거**

`getStoredMode()` 함수에서 `'floating'`을 유효 모드로 인식하지 않도록 수정. localStorage에 `'floating'`이 저장되어 있으면 `'side'`로 폴백하도록 처리.

```typescript
// getStoredMode에서 floating 처리
const stored = localStorage.getItem('ai-mode');
if (stored === 'side' || stored === 'fullscreen') return stored;
return 'side'; // floating이었던 경우 side로 폴백
```

- [ ] **Step 3: `AIChatPanel.tsx` 헤더에서 플로팅 모드 전환 버튼 제거**

모드 전환 버튼 영역에서 `MessageCircle` (floating) 아이콘 버튼을 제거. `PanelRight` (side)와 `Monitor` (fullscreen)만 남긴다.

- [ ] **Step 4: `AppLayout.tsx`에서 AINavButton 제거**

사이드바 내 `AINavButton` 컴포넌트 정의(lines 203-242)와 렌더링 부분(lines 346-350, AI Nav Button + Separator)을 제거한다.

- [ ] **Step 5: `AppLayout.tsx`에서 AIFloating 렌더링 제거**

`AIFloating`의 lazy import, Suspense 래핑, 조건부 렌더링(lines 462-466)을 제거한다.

- [ ] **Step 6: `AIFloating.tsx`, `AIToggleButton.tsx` 파일 삭제**

```bash
rm apps/firehub-web/src/components/ai/AIFloating.tsx
rm apps/firehub-web/src/components/ai/AIToggleButton.tsx
```

- [ ] **Step 7: 타입체크 + 빌드 확인**

```bash
cd apps/firehub-web && pnpm typecheck && pnpm build
```

Expected: 에러 없이 통과. `'floating'` 참조가 남아있으면 타입 에러로 잡힘.

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "refactor(web): 플로팅 모드 제거 + 사이드바 AI 버튼 제거"
```

---

## Task 2: AIStatusChip 기본 구현 (상태 표시 + 클릭 로테이션)

칩 본체를 구현한다. 상태별 표시(아이콘, 색상, 텍스트)와 클릭 시 모드 로테이션.

**Files:**
- Create: `apps/firehub-web/src/components/ai/AIStatusChip.tsx`
- Modify: `apps/firehub-web/src/components/layout/AppLayout.tsx`

- [ ] **Step 1: `AIStatusChip.tsx` 생성 — 기본 칩 렌더링**

AIProvider context에서 상태를 읽어 칩을 렌더링하는 컴포넌트. 상태 판단 로직:

```typescript
type ChipState = 'idle' | 'streaming' | 'thinking' | 'error' | 'side' | 'fullscreen';

function getChipState(ctx): ChipState {
  if (에러 상태) return 'error';
  if (ctx.isStreaming) return 'streaming';
  if (ctx.isThinking) return 'thinking';
  if (ctx.isOpen && ctx.mode === 'fullscreen') return 'fullscreen';
  if (ctx.isOpen && ctx.mode === 'side') return 'side';
  return 'idle';
}
```

상태별 스타일 매핑:
- `idle`: 반투명 보라 배경, 녹색 상태 점
- `streaming`: 밝은 보라, 펄스 아이콘 + 프로그레스 바
- `thinking`: 노란색 계열, ⚡ 아이콘 + 프로그레스 바
- `error`: 빨간색, ! 아이콘
- `side`: 보라 테두리 강조, 분할 사각형 SVG 아이콘
- `fullscreen`: 보라 배경 채움, 채움 사각형 SVG 아이콘

칩 내용:
- 세션이 없으면: "AI 어시스턴트"
- 세션이 있으면: "{세션명} · {N}건" (세션명 최대 10자 말줄임)
- streaming: "응답 생성 중"
- thinking: "데이터 조회 중" (또는 현재 도구명)

- [ ] **Step 2: 클릭 핸들러 — 모드 로테이션 구현**

```typescript
const handleClick = () => {
  if (!isOpen) {
    setMode('side');
    openAI();
  } else if (mode === 'side') {
    setMode('fullscreen');
  } else {
    // fullscreen → close
    closeAI();
  }
};
```

- [ ] **Step 3: CSS 애니메이션 — 펄스, 프로그레스 바, 트랜지션**

Tailwind의 `@keyframes`와 `animate-` 클래스 활용:
- `animate-pulse-icon`: opacity 1 ↔ 0.4, 1.5s
- `animate-slide`: translateX 슬라이딩, 1s
- 칩 전체: `transition-all duration-200 ease-in-out`

Tailwind v4에서는 `@theme` 또는 인라인 `style` 속성으로 커스텀 애니메이션 적용.

- [ ] **Step 4: `AppLayout.tsx`에 `AIStatusChip` 배치**

메인 콘텐츠 영역 내부, `<main>` 또는 콘텐츠 래퍼의 상단에 칩을 배치:

```tsx
{/* Main content area */}
<div className="relative flex-1 flex flex-col">
  {/* AI Status Chip - 상단 중앙 */}
  <AIStatusChip />

  {/* 기존 콘텐츠 */}
  {showFullscreen ? (
    <Suspense><AIFullScreen /></Suspense>
  ) : (
    <div className="flex flex-1 overflow-hidden">
      <main>...</main>
      {/* side panel */}
    </div>
  )}
</div>
```

칩 위치 스타일: `absolute top-2 left-1/2 -translate-x-1/2 z-20`
(사이드바 너비를 제외한 메인 콘텐츠 영역의 중앙)

- [ ] **Step 5: 타입체크 + 빌드 확인**

```bash
cd apps/firehub-web && pnpm typecheck && pnpm build
```

- [ ] **Step 6: dev 서버에서 수동 확인**

```bash
pnpm dev
```

확인 사항:
- 칩이 메인 콘텐츠 상단 중앙에 표시
- 클릭 시 닫힘→사이드→풀스크린→닫힘 로테이션
- 상태별 아이콘/색상 변화 (AI에 메시지를 보내서 streaming 상태 확인)
- ⌘K 단축키 기존대로 동작

- [ ] **Step 7: Playwright 스크린샷 검증**

칩 표시 상태를 스크린샷으로 캡처하여 `snapshots/`에 저장.

- [ ] **Step 8: 커밋**

```bash
git add apps/firehub-web/src/components/ai/AIStatusChip.tsx apps/firehub-web/src/components/layout/AppLayout.tsx
git commit -m "feat(web): AI 상태 칩 기본 구현 — 상태 표시 + 클릭 로테이션"
```

---

## Task 3: AIStatusChipDropdown 구현 (호버 드롭다운)

호버 시 나타나는 상태 카드 + 액션 패널.

**Files:**
- Create: `apps/firehub-web/src/components/ai/AIStatusChipDropdown.tsx`
- Modify: `apps/firehub-web/src/components/ai/AIStatusChip.tsx`

- [ ] **Step 1: `AIStatusChipDropdown.tsx` 생성 — 레이아웃 구조**

드롭다운 패널 컴포넌트. Props:

```typescript
interface AIStatusChipDropdownProps {
  isAIOpen: boolean;
  mode: AIMode;
  onModeChange: (mode: AIMode) => void;
  onOpen: () => void;
  onClose: () => void;
  onNewSession: () => void;
  onSendMessage: (content: string) => void;
  messages: AIMessage[];
  isStreaming: boolean;
  isThinking: boolean;
  contextTokens: number | null;
  currentSessionId: string | null;
}
```

섹션 구성:
1. **헤더**: ✦ AI 어시스턴트 + 상태 표시 (🟢 대기 중 / 응답 중 / 도구 실행 중)
2. **세션 정보**: 세션명, 대화 수, 마지막 활동, 토큰 사용량 프로그레스 바
3. **빠른 입력** (닫힘 상태만): input + 전송 버튼. 전송 시 `onSendMessage` + `onOpen` 호출
4. **마지막 응답 미리보기** (열림 상태만): `messages`에서 마지막 assistant 메시지 첫 줄
5. **모드 전환**: 닫힘 상태 → 2x2 그리드 (사이드/풀스크린/새세션/세션목록). 열림 상태 → 3칸 모드 선택 (닫기/사이드/풀스크린) + 2칸 (새세션/세션목록). 현재 모드 하이라이트.
6. **푸터**: ⌘K 단축키 힌트

스타일:
- 폭: 320px
- 배경: `bg-[#1e1e36]` (다크 테마 기준, 라이트 테마는 `bg-popover` 활용)
- 테두리: `border border-primary/30`
- 라운딩: `rounded-xl` (14px)
- 그림자: `shadow-2xl`
- `backdrop-blur-xl`

- [ ] **Step 2: `AIStatusChip.tsx`에 호버 로직 추가**

호버 열기/닫기 규칙 구현:

```typescript
const [showDropdown, setShowDropdown] = useState(false);
const hoverTimerRef = useRef<ReturnType<typeof setTimeout>>();
const closeTimerRef = useRef<ReturnType<typeof setTimeout>>();
const inputFocusedRef = useRef(false);

const handleMouseEnter = () => {
  clearTimeout(closeTimerRef.current);
  hoverTimerRef.current = setTimeout(() => setShowDropdown(true), 200);
};

const handleMouseLeave = () => {
  clearTimeout(hoverTimerRef.current);
  if (!inputFocusedRef.current) {
    closeTimerRef.current = setTimeout(() => setShowDropdown(false), 300);
  }
};
```

칩 + 드롭다운을 하나의 컨테이너로 감싸서 마우스 이동 시 닫히지 않도록:
```tsx
<div onMouseEnter={handleMouseEnter} onMouseLeave={handleMouseLeave}>
  <ChipButton ... />
  {showDropdown && <AIStatusChipDropdown ... />}
</div>
```

- [ ] **Step 3: 빠른 입력 기능 구현**

드롭다운 내 input에서:
- 포커스 시 `inputFocusedRef.current = true` → 마우스 벗어나도 드롭다운 유지
- 블러 시 `inputFocusedRef.current = false` → 정상 닫기 로직 복구
- Enter 또는 전송 버튼 클릭 시: `onSendMessage(value)` → `onOpen()` (사이드 패널 오픈) → 드롭다운 닫기
- ESC 시: 드롭다운 닫기

- [ ] **Step 4: 드롭다운 열기/닫기 애니메이션**

```css
/* 열기 */
opacity: 0 → 1, translateY: -4px → 0, duration: 150ms ease-out

/* 닫기 */
opacity: 1 → 0, duration: 100ms ease-in
```

Tailwind로 구현하거나, `data-state="open|closed"` + CSS transition 활용.

- [ ] **Step 5: 접근성 속성 추가**

칩:
- `role="button"`
- `aria-label="AI 어시스턴트 - 현재 상태: {상태}"`
- `aria-haspopup="true"`
- `aria-expanded={showDropdown}`
- `tabIndex={0}`
- `onKeyDown`: Enter → 클릭, Escape → 드롭다운 닫기

드롭다운:
- `role="menu"`
- `aria-label="AI 상태 및 제어"`

상태 변경 알림:
- 칩 근처에 `aria-live="polite"` 영역 추가 → 상태 변경 시 스크린리더 알림

- [ ] **Step 6: 타입체크 + 빌드 확인**

```bash
cd apps/firehub-web && pnpm typecheck && pnpm build
```

- [ ] **Step 7: dev 서버에서 수동 확인**

```bash
pnpm dev
```

확인 사항:
- 칩 호버 시 드롭다운 표시 (200ms 딜레이)
- 마우스가 칩→드롭다운으로 이동해도 닫히지 않음
- 마우스가 완전히 벗어나면 300ms 후 닫힘
- 빠른 입력에 텍스트 입력 후 Enter → 사이드 패널 오픈 + 메시지 전송
- 모드 직접 선택 동작
- 새 세션 버튼 동작
- ESC로 드롭다운 닫기

- [ ] **Step 8: Playwright 스크린샷 검증**

호버 드롭다운 상태를 스크린샷으로 캡처.

- [ ] **Step 9: 커밋**

```bash
git add apps/firehub-web/src/components/ai/AIStatusChip.tsx apps/firehub-web/src/components/ai/AIStatusChipDropdown.tsx
git commit -m "feat(web): AI 상태 칩 호버 드롭다운 구현"
```

---

## Task 4: 통합 검증 + 정리

모든 기능이 올바르게 동작하는지 종합 검증.

**Files:**
- Verify: 전체 `apps/firehub-web/`

- [ ] **Step 1: 전체 빌드 + 타입체크 + 린트**

```bash
pnpm typecheck && pnpm lint && pnpm build
```

Expected: 모든 검사 통과.

- [ ] **Step 2: 기능 검증 체크리스트**

dev 서버에서 수동 확인:

- [ ] 칩이 메인 콘텐츠 상단 중앙에 표시됨
- [ ] 칩 클릭 시 모드 로테이션 동작 (닫힘→사이드→풀스크린→닫힘)
- [ ] ⌘K로 사이드 패널 토글 동작
- [ ] 호버 시 드롭다운 표시 (200ms 딜레이)
- [ ] 드롭다운에서 빠른 입력 전송 시 사이드 패널 오픈 + 메시지 전송
- [ ] 드롭다운에서 모드 직접 선택 동작
- [ ] 드롭다운에서 새 세션 동작
- [ ] AI 응답 중 칩 상태 변화 (펄스 + 프로그레스)
- [ ] 사이드바 AI 버튼 제거 확인
- [ ] 플로팅 모드 완전 제거 확인
- [ ] 사이드바 접힘/펼침 시 칩 위치 정상

- [ ] **Step 3: Playwright 스크린샷 — 전체 상태**

각 상태별 스크린샷 캡처하여 `snapshots/`에 저장:
1. 닫힘 상태 (칩만 보임)
2. 호버 상태 (드롭다운 열림)
3. 사이드 패널 열림 (칩 상태 변화)
4. 풀스크린 모드 (칩 상태 변화)

- [ ] **Step 4: 미사용 import 정리**

`AIFloating`, `AIToggleButton` 관련 import가 남아있지 않은지 확인:
```bash
cd apps/firehub-web && grep -r "AIFloating\|AIToggleButton\|ai-floating-pos" src/
```
Expected: 결과 없음.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "verify(web): AI 상태 칩 통합 검증 완료"
```
