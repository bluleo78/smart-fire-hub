# Smart Fire Hub — 디자인 시스템 가이드라인

> **버전**: 1.1.0
> **최종 검증**: 2026-04-08
> **참조 시스템**: [shadcn/ui](https://ui.shadcn.com) (new-york) + [Vercel Geist](https://vercel.com/geist)
> **대상 독자**: 개발자 + AI 에이전트 (Claude Code)
> **범위**: 가이드라인 + 코드 적용 (P0/P1 완료, P2/P3 잔여)

---

## 개요

이 디자인 시스템은 Smart Fire Hub 프론트엔드의 일관된 UI를 위한 가이드라인이다.
모든 규칙은 **구체적 Tailwind CSS 클래스**로 표현되어, 개발자와 AI 에이전트가 즉시 적용할 수 있다.

### 표기 규칙

- **현재(As-Is)**: 코드베이스에서 실제 사용 중인 패턴 (파일:라인 참조 포함)
- **권장(To-Be)**: Phase D-2에서 적용할 목표 패턴
- 모든 색상값은 OKLch 색공간 사용 (`oklch(L C H)`)

### 기술 스택

| 영역 | 기술 |
|------|------|
| UI 프레임워크 | React 19 + TypeScript |
| 스타일링 | Tailwind CSS v4 (CSS-based config, no tailwind.config.js) |
| 컴포넌트 라이브러리 | shadcn/ui (new-york style, neutral base, Lucide icons) |
| 테마 | next-themes (dark/light/system) |
| 폰트 | Inter + 시스템 폰트 스택 (Geist 도입 검토 중) |
| 아이콘 | Lucide React (단독 사용) |

---

## 목차

| # | 문서 | 설명 |
|---|------|------|
| 01 | [Design Tokens](./01-design-tokens.md) | 색상 토큰, 반경, 그림자, Z-Index 스케일 |
| 02 | [Typography](./02-typography.md) | 13단계 시맨틱 타이포그래피 스케일 |
| 03 | [Spacing & Layout](./03-spacing-layout.md) | 4px 기반 스페이싱, AppLayout 골격, 그리드 패턴 |
| 04 | [Components](./04-components.md) | shadcn/ui 24개 + 커스텀 6개 사용 가이드 |
| 05 | [Page Patterns](./05-page-patterns.md) | 5개 페이지 레이아웃 템플릿 (TSX 스켈레톤) |
| 06 | [Feedback States](./06-feedback-states.md) | Loading, Empty, Error, Toast 패턴 |
| 07 | [Iconography](./07-iconography.md) | Lucide 아이콘 사이즈, 색상, 간격 규칙 |
| 08 | [Animation & Motion](./08-animation-motion.md) | 트랜지션 타이밍, GPU 가속, Reduced Motion |
| 09 | [Form Patterns](./09-form-patterns.md) | 폼 구조, 유효성 검사, 에러 표시 |
| 10 | [Accessibility](./10-accessibility.md) | WCAG 2.2 AA, ARIA 패턴, 키보드 네비게이션 |
| 11 | [Dark Mode](./11-dark-mode.md) | 다크 모드 토큰, Surface Elevation, 갭 분석 |
| 12 | [Responsive](./12-responsive.md) | 브레이크포인트, 사이드바 반응형, Desktop-first |
| 13 | [Migration Backlog](./13-migration-backlog.md) | Phase D-2 작업 목록 (P0~P3 우선순위) |

---

## Quick Reference Card

새 페이지나 컴포넌트를 만들 때 이 표를 참조한다.

### Typography

| 용도 | 권장 Tailwind 클래스 | Size |
|------|---------------------|------|
| 페이지 타이틀 (H1) | `text-[28px] leading-[36px] font-semibold tracking-tight` | 28px |
| 섹션 제목 (H2) | `text-2xl leading-8 font-semibold tracking-tight` | 24px |
| 카드/다이얼로그 제목 (H3) | `text-xl leading-7 font-semibold` | 20px |
| 그룹 라벨 (H4) | `text-base leading-6 font-semibold` | 16px |
| 테이블 컬럼 헤더 | `text-sm leading-5 font-semibold` | 14px |
| 주요 본문 | `text-base leading-7` | 16px |
| 보조 본문 | `text-sm leading-6` | 14px |
| 캡션/힌트 | `text-[13px] leading-5` | 13px |
| UI 라벨 | `text-sm leading-5 font-medium` | 14px |
| 배지/태그 | `text-xs leading-4 font-medium` | 12px |
| 인라인 코드 | `text-sm font-mono` | 14px |
| 코드 블록/SQL | `text-[13px] leading-5 font-mono` | 13px |
| 숫자 데이터 | `text-sm font-mono` + `tabular-nums` | 14px |

### Spacing

| 용도 | Tailwind | px |
|------|----------|-----|
| 아이콘 패딩, 칩 간격 | `gap-1` / `p-1` | 4px |
| 컴팩트 패딩, 테이블 셀 | `gap-2` / `p-2` | 8px |
| 배지 패딩, 툴바 간격 | `gap-3` / `p-3` | 12px |
| **기본 컴포넌트 패딩** | `gap-4` / `p-4` | 16px |
| **카드/페이지 패딩** | `gap-6` / `p-6` | 24px |
| 섹션 구분, 폼 그룹 | `gap-8` / `p-8` | 32px |

### Layout

| 요소 | 값 |
|------|-----|
| 사이드바 (확장) | `lg:w-60` (240px) |
| 사이드바 (축소) | `lg:w-[52px]` (52px) |
| 헤더 높이 | `h-14` (56px) |
| 메인 콘텐츠 패딩 | `p-6` (24px) |
| AI 사이드 패널 | `w-80` (320px) |
| 페이지 섹션 간격 | `space-y-6` |
| 카드 그리드 갭 | `gap-4` |

### Color Tokens (주요)

| 용도 | Tailwind 클래스 |
|------|----------------|
| 페이지 배경 | `bg-background` |
| 기본 텍스트 | `text-foreground` |
| 보조 텍스트 | `text-muted-foreground` |
| 카드 배경 | `bg-card` |
| 테두리 | `border-border` |
| 기본 버튼 | `bg-primary text-primary-foreground` |
| 위험/삭제 | `bg-destructive text-destructive-foreground` |
| hover 배경 | `bg-accent` / `bg-muted/50` |
| Focus ring | `ring-ring/50` |

### Status Colors (구현 완료)

| 상태 | 배경 | 텍스트 | Badge variant |
|------|------|--------|---------------|
| 성공/활성 | `bg-success-subtle` | `text-success` | `variant="success"` |
| 경고/주의 | `bg-warning-subtle` | `text-warning` | `variant="warning"` |
| 정보/진행중 | `bg-info-subtle` | `text-info` | `variant="info"` |
| 오류/위험 | `bg-destructive/10` | `text-destructive` | `variant="destructive"` |
| AI 기능 | `bg-ai-accent-subtle` | `text-ai-accent` | — |
| 주의(강) | `bg-caution-subtle` | `text-caution` | — |

### Domain Colors

| 도메인 | 배경 | 텍스트 |
|--------|------|--------|
| 파이프라인 | `bg-pipeline` | `text-pipeline` |
| 데이터셋 | `bg-dataset` | `text-dataset` |
| 대시보드 | `bg-dashboard-accent` | `text-dashboard-accent` |

### Icons (Lucide)

| 컨텍스트 | 크기 | 아이콘-텍스트 간격 |
|---------|------|-----------------|
| 배지/태그 내 | `h-3 w-3` (12px) | `gap-1` (4px) |
| 기본 인라인 | `h-4 w-4` (16px) | `gap-2` (8px) |
| 사이드바/헤더 | `h-5 w-5` (20px) | `gap-3` (12px) |
| 빈 상태 | `h-6 w-6` (24px) | `gap-2` (8px) |

### Animation

| 인터랙션 | Tailwind |
|---------|----------|
| hover 색상 변화 | `transition-colors` (~150ms) |
| 요소 표시/숨김 | `transition-opacity` (~150ms) |
| 레이아웃 변경 | `transition-all duration-200` |
| 로딩 스피너 | `animate-spin` |

### Z-Index

| 레이어 | 값 | 용도 |
|--------|-----|------|
| content | `z-10` | 스티키 헤더, 캔버스 오버레이 |
| header | `z-30` | AppLayout 헤더 |
| overlay | `z-40` | 모바일 사이드바 오버레이 |
| modal | `z-50` | 다이얼로그, 팝오버, 툴팁, 사이드바 |

---

## 디자인 철학

### 무채색 기반 (Achromatic Palette)

현재 팔레트는 **의도적으로 무채색(achromatic)** 이다. 모든 기본 토큰은 chroma 0 (순수 회색 스케일)이며, 색상이 허용되는 영역은 3가지뿐이다:

1. **Destructive** — 빨간색 (삭제, 오류, 위험)
2. **Chart** — 5색 팔레트 (데이터 시각화)
3. **Favorite star** — 노란색 (즐겨찾기)

이 제약은 정보 밀도가 높은 데이터 플랫폼에서 시각적 노이즈를 줄이고 데이터에 집중하기 위한 것이다.

### shadcn/ui 우선

- UI 컴포넌트는 shadcn/ui 프리미티브를 **그대로 사용**한다 (커스터마이징 최소화)
- `src/components/ui/` 파일은 `npx shadcn` CLI로 생성된 것이므로 **수동 편집 금지**
- 프로젝트 고유 컴포넌트는 shadcn 프리미티브를 **조합**하여 만든다

### CSS Variables 기반 테마

- 모든 색상은 CSS 변수로 정의 → Light/Dark 자동 전환
- Tailwind v4의 `@theme inline`으로 변수를 유틸리티 클래스에 매핑
- **하드코딩 색상 금지** — 항상 시맨틱 토큰 사용 (`bg-background`, `text-foreground` 등)

---

## 관련 문서

| 문서 | 역할 |
|------|------|
| [ROADMAP](../ROADMAP.md) | Phase D-1 (가이드라인), Phase D-2 (코드 적용) |
| [firehub-web CLAUDE.md](../../apps/firehub-web/CLAUDE.md) | 프론트엔드 아키텍처, 패턴, 규칙 |
| [index.css](../../apps/firehub-web/src/index.css) | CSS 변수 원본 (Light/Dark 토큰) |
