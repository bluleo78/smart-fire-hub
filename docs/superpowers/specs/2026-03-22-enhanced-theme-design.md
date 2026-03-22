# Enhanced Theme Design Spec

## Overview

Smart Fire Hub 전체 앱에 세련된 디자인 시스템을 적용한다. Refined Light + Dark Theme 듀얼 테마를 지원하며, 7가지 디자인 개선을 모든 페이지에 적용한다.

## Design Decisions

### Theme System
- **next-themes** 기반 Light/Dark 전환 (인프라 이미 존재)
- 기본 테마: **시스템 설정 따름** (prefers-color-scheme)
- 테마 토글 UI: 사이드바 하단 또는 헤더에 배치
- 테마 선택은 localStorage에 저장

### Color System

#### Shared (Both Themes)
- Primary accent: Indigo 계열
- Domain colors:
  - Pipeline: Cyan/Teal
  - Dataset: Indigo
  - Dashboard: Purple
- Category colors:
  - 행정: Blue
  - 운영: Green/Emerald
  - 통계: Purple/Violet
- Status: green(success), red(destructive), amber(warning) — 기존 유지

#### Light Theme Tokens (변경사항)
```
--background: oklch(0.985 0.002 264)  /* 오프화이트 + 인디고 틴트 */
--card: oklch(1 0 0)                   /* 순백 카드 */
--primary: oklch(0.45 0.2 264)         /* 인디고 #4f46e5 */
--primary-foreground: oklch(0.985 0 0)
--border: oklch(0.95 0 0)              /* 더 연한 보더 */
--sidebar-primary: oklch(0.45 0.2 264) /* 인디고 */
```

#### Dark Theme Tokens (변경사항)
```
--background: oklch(0.13 0.015 280)    /* 딥 네이비 #0c0c18 */
--card: oklch(1 0 0 / 3%)             /* 반투명 카드 */
--primary: oklch(0.65 0.2 264)         /* 밝은 인디고 #818cf8 */
--border: oklch(1 0 0 / 5%)           /* 미묘한 보더 */
--sidebar: oklch(0.14 0.02 280)        /* 사이드바 딥 네이비 */
--sidebar-primary: oklch(0.65 0.2 264) /* 인디고 */
```

### 7 Improvements

#### 1. Micro Interactions
- 카드 호버: `translateY(-2px)` + 그림자 강화 (transition: 0.2s)
- 사이드바 아이콘 호버: `scale(1.08)` + 색상 변경
- AI 칩 호버: glow 강화(다크) / 섀도우 강화(라이트) + `translateY(-1px)`
- 데이터 행 호버: 배경 틴트 변경
- 적용 위치: 전역 CSS + 개별 컴포넌트

#### 2. Background Gradient
- 라이트: `radial-gradient(ellipse at top center, #ffffff, #f5f5fa)`
- 다크: `radial-gradient(ellipse at top center, #161630, #0c0c18)`
- 적용: `body` 또는 메인 콘텐츠 영역의 `bg-background`에 그라데이션 오버레이

#### 3. Glassmorphism
- AI 칩: `backdrop-filter: blur(12px)` + 반투명 배경
- 다크 사이드바: `backdrop-filter: blur(8px)`
- 검색바 (다크): 반투명 배경
- 라이트: 소프트 섀도우로 대체 (backdrop-filter 불필요)

#### 4. Typography
- Inter 폰트 추가 (`@fontsource/inter` 또는 Google Fonts)
- 숫자 표시: `font-variant-numeric: tabular-nums`
- 섹션 라벨: `letter-spacing: 0.03em` ~ `0.05em`
- 통계 값: `font-weight: 800`

#### 5. Domain Colors
- 새 CSS 변수 추가:
  ```
  --pipeline: oklch(...) /* cyan/teal */
  --dataset: oklch(...)  /* indigo */
  --dashboard: oklch(...) /* purple */
  --category-admin: oklch(...) /* blue */
  --category-ops: oklch(...)   /* green */
  --category-stats: oklch(...) /* purple */
  ```
- 각 도메인의 아이콘 배경, 통계 카드 값, 배지 색상에 적용

#### 6. Data Visualization
- **스파크라인**: 홈 대시보드 통계 카드에 미니 bar chart (최근 7일 추이)
  - 백엔드 API 필요: 일별 실행/임포트/변경 카운트
- **Freshness bar**: 데이터셋 행에 신선도 표시 (마지막 갱신 기준)
  - 7일 이내: green, 14일 이내: amber, 이후: red
  - 기존 데이터에서 계산 가능 (lastImportedAt 또는 updatedAt)

#### 7. Sidebar Enhancement
- 활성 네비 아이콘: 좌측 3px 인디고 바 인디케이터
  - 다크: 그라데이션 + glow 효과
  - 라이트: 실선 인디고
- 로고: 미묘한 pulse 애니메이션 (`box-shadow` 변화, 3s cycle)
- 하단: 유저 아바타 + 온라인 상태 점 (green dot)
- 테마 토글 버튼: 아바타 위에 sun/moon 아이콘

### Theme Toggle UI
- 위치: 사이드바 하단, 유저 아바타 위
- 아이콘: Sun (라이트) / Moon (다크)
- 클릭 시 전환, 부드러운 transition
- next-themes의 `useTheme()` 훅 사용

## Affected Files

### CSS / Theme
- `apps/firehub-web/src/index.css` — 테마 토큰 전면 수정
- `docs/design-system/01-design-tokens.md` — 문서 업데이트
- `docs/design-system/11-dark-mode.md` — 문서 업데이트

### Layout
- `apps/firehub-web/src/components/layout/AppLayout.tsx` — 사이드바 인디케이터, 테마 토글, 배경 그라데이션
- `apps/firehub-web/src/components/layout/UserNav.tsx` — 아바타 + 온라인 점

### AI
- `apps/firehub-web/src/components/ai/AIStatusChip.tsx` — glassmorphism, 호버 효과

### Home Dashboard
- `apps/firehub-web/src/pages/HomePage.tsx` — 통계 카드 리디자인, 스파크라인, freshness bar

### Dataset
- 데이터셋 목록/상세 — 도메인 컬러, freshness bar, 카테고리 배지 컬러

### Pipeline
- 파이프라인 목록/에디터 — 도메인 컬러, 호버 효과

### Analytics
- 쿼리/차트/대시보드 — 도메인 컬러, 카드 스타일

### Admin
- 사용자/역할/감사로그/설정 — 카드 스타일, 호버 효과

### Backend (Optional)
- 스파크라인 데이터 API: `DashboardController` — 일별 카운트 엔드포인트 추가

## Verification Criteria

1. **테마 토글**: Light ↔ Dark 전환이 모든 페이지에서 즉시 반영됨
2. **색상 일관성**: 인디고 액센트가 버튼, 링크, 활성 상태, 배지에 통일 적용됨
3. **도메인 컬러**: 파이프라인/데이터셋/대시보드가 각각 시안/인디고/퍼플로 구분됨
4. **호버 효과**: 카드, 행, 아이콘 호버 시 부드러운 전환 (0.15~0.2s)
5. **타입체크**: `pnpm typecheck` 통과
6. **빌드**: `pnpm build` 성공
7. **Playwright 스크린샷**: 주요 페이지(홈, 데이터셋, 파이프라인) 양 테마 캡처 비교
8. **접근성**: 다크 모드에서 WCAG AA 대비율 충족 (텍스트 4.5:1 이상)

## Out of Scope
- 모바일 반응형 레이아웃 변경 (기존 유지)
- 애니메이션 라이브러리 추가 (CSS transition만 사용)
- 페이지 구조/라우팅 변경
