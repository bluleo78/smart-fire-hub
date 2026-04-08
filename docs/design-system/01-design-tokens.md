# Design Tokens — Smart Fire Hub

> **범위**: `apps/firehub-web` 프론트엔드 디자인 시스템의 토큰 정의, 현황 감사, 권장 방향을 다룬다.
> **기준 파일**: `apps/firehub-web/src/index.css`, shadcn/ui 컴포넌트 라이브러리

---

## 목차

1. [Color Tokens — 색상 토큰](#1-color-tokens--색상-토큰)
   - 1-1. Light Theme (`:root`)
   - 1-2. Dark Theme (`.dark`)
   - 1-3. 디자인 철학: 의도적 무채색 팔레트
   - 1-4. 권장: Semantic Status Tokens (To-Be)
2. [Hard-coded Color Audit — 하드코딩 색상 감사](#2-hard-coded-color-audit--하드코딩-색상-감사)
3. [Border Radius Scale — 모서리 반경 스케일](#3-border-radius-scale--모서리-반경-스케일)
4. [Z-Index Scale — 레이어 순서 스케일](#4-z-index-scale--레이어-순서-스케일)
5. [Shadow Usage — 그림자 사용 패턴](#5-shadow-usage--그림자-사용-패턴)

---

## 1. Color Tokens — 색상 토큰

Smart Fire Hub의 색상 시스템은 CSS 커스텀 프로퍼티(CSS Custom Properties)로 정의되며, 모든 색상은 [OKLch](https://oklch.com/) 색공간을 사용한다. OKLch는 인지적으로 균일한(perceptually uniform) 색공간으로, 명도(L), 채도(C), 색상각(h) 세 축으로 색상을 표현한다.

### 1-1. Light Theme (`:root`)

라이트 모드의 기본 색상 토큰이다. `:root` 선택자에 정의되며 기본값으로 적용된다.

#### Core UI Tokens

| Token | OKLch 값 | 근사 색상 | 용도 |
|-------|----------|-----------|------|
| `--background` | `oklch(1 0 0)` | 흰색 | 페이지 전체 배경 |
| `--foreground` | `oklch(0.145 0 0)` | Near-black | 기본 본문 텍스트 |
| `--card` | `oklch(1 0 0)` | 흰색 | 카드 컴포넌트 배경 |
| `--card-foreground` | `oklch(0.145 0 0)` | Near-black | 카드 내 텍스트 |
| `--popover` | `oklch(1 0 0)` | 흰색 | 팝오버, 드롭다운 배경 |
| `--popover-foreground` | `oklch(0.145 0 0)` | Near-black | 팝오버 내 텍스트 |

#### Brand & Interaction Tokens

| Token | OKLch 값 | 근사 색상 | 용도 |
|-------|----------|-----------|------|
| `--primary` | `oklch(0.205 0 0)` | Near-black | 기본 브랜드 색상, CTA 버튼 배경 |
| `--primary-foreground` | `oklch(0.985 0 0)` | Near-white | Primary 위 텍스트 |
| `--secondary` | `oklch(0.97 0 0)` | 연한 회색 | 보조 버튼, 보조 배경 |
| `--secondary-foreground` | `oklch(0.205 0 0)` | Near-black | Secondary 위 텍스트 |
| `--muted` | `oklch(0.97 0 0)` | 연한 회색 | 음소거 배경, 비활성 영역 |
| `--muted-foreground` | `oklch(0.556 0 0)` | 중간 회색 | 플레이스홀더, 부가 설명 텍스트 |
| `--accent` | `oklch(0.97 0 0)` | 연한 회색 | hover·active 상태 배경 |
| `--accent-foreground` | `oklch(0.205 0 0)` | Near-black | Accent 위 텍스트 |
| `--destructive` | `oklch(0.577 0.245 27.325)` | 빨간색 | 위험·삭제 액션, 에러 상태 |

#### Structural Tokens

| Token | OKLch 값 | 근사 색상 | 용도 |
|-------|----------|-----------|------|
| `--border` | `oklch(0.922 0 0)` | 연한 회색 | 일반 테두리 |
| `--input` | `oklch(0.922 0 0)` | 연한 회색 | Input 컴포넌트 테두리 |
| `--ring` | `oklch(0.708 0 0)` | 중간-연한 회색 | 키보드 Focus ring |

#### Chart Tokens

| Token | OKLch 값 | 근사 색상 | 용도 |
|-------|----------|-----------|------|
| `--chart-1` | `oklch(0.646 0.222 41.116)` | 오렌지 | 차트 데이터 시리즈 1 |
| `--chart-2` | `oklch(0.6 0.118 184.704)` | 틸(Teal) | 차트 데이터 시리즈 2 |
| `--chart-3` | `oklch(0.398 0.07 227.392)` | 블루그레이 | 차트 데이터 시리즈 3 |
| `--chart-4` | `oklch(0.828 0.189 84.429)` | 옐로그린 | 차트 데이터 시리즈 4 |
| `--chart-5` | `oklch(0.769 0.188 70.08)` | 앰버(Amber) | 차트 데이터 시리즈 5 |

#### Sidebar Tokens

| Token | OKLch 값 | 근사 색상 | 용도 |
|-------|----------|-----------|------|
| `--sidebar` | `oklch(0.985 0 0)` | Near-white | 사이드바 배경 |
| `--sidebar-foreground` | `oklch(0.145 0 0)` | Near-black | 사이드바 텍스트 |
| `--sidebar-primary` | `oklch(0.205 0 0)` | Near-black | 사이드바 선택 항목 배경 |
| `--sidebar-primary-foreground` | `oklch(0.985 0 0)` | Near-white | 사이드바 선택 항목 텍스트 |
| `--sidebar-accent` | `oklch(0.97 0 0)` | 연한 회색 | 사이드바 hover 배경 |
| `--sidebar-accent-foreground` | `oklch(0.205 0 0)` | Near-black | 사이드바 hover 텍스트 |
| `--sidebar-border` | `oklch(0.922 0 0)` | 연한 회색 | 사이드바 테두리 |
| `--sidebar-ring` | `oklch(0.708 0 0)` | 중간-연한 회색 | 사이드바 focus ring |

---

### 1-2. Dark Theme (`.dark`)

`.dark` 클래스가 `<html>` 또는 최상위 엘리먼트에 적용될 때 오버라이드되는 토큰이다.

#### Core UI Tokens (Dark)

| Token | OKLch 값 | 근사 색상 |
|-------|----------|-----------|
| `--background` | `oklch(0.145 0 0)` | Near-black |
| `--foreground` | `oklch(0.985 0 0)` | Near-white |
| `--card` | `oklch(0.205 0 0)` | 매우 어두운 회색 |
| `--card-foreground` | `oklch(0.985 0 0)` | Near-white |
| `--popover` | `oklch(0.205 0 0)` | 매우 어두운 회색 |
| `--popover-foreground` | `oklch(0.985 0 0)` | Near-white |

#### Brand & Interaction Tokens (Dark)

| Token | OKLch 값 | 근사 색상 |
|-------|----------|-----------|
| `--primary` | `oklch(0.922 0 0)` | 연한 회색 |
| `--primary-foreground` | `oklch(0.205 0 0)` | 어두운 회색 |
| `--secondary` | `oklch(0.269 0 0)` | 어두운 회색 |
| `--secondary-foreground` | `oklch(0.985 0 0)` | Near-white |
| `--muted` | `oklch(0.269 0 0)` | 어두운 회색 |
| `--muted-foreground` | `oklch(0.708 0 0)` | 중간 회색 |
| `--accent` | `oklch(0.269 0 0)` | 어두운 회색 |
| `--accent-foreground` | `oklch(0.985 0 0)` | Near-white |
| `--destructive` | `oklch(0.704 0.191 22.216)` | 밝은 빨간색 |

#### Structural Tokens (Dark)

| Token | OKLch 값 | 비고 |
|-------|----------|------|
| `--border` | `oklch(1 0 0 / 10%)` | 흰색 10% 알파 — 반투명 테두리 |
| `--input` | `oklch(1 0 0 / 15%)` | 흰색 15% 알파 — Input 테두리 |
| `--ring` | `oklch(0.556 0 0)` | 중간 회색 |

#### Chart Tokens (Dark)

| Token | OKLch 값 | 근사 색상 |
|-------|----------|-----------|
| `--chart-1` | `oklch(0.488 0.243 264.376)` | 인디고/블루 |
| `--chart-2` | `oklch(0.696 0.17 162.48)` | 에메랄드 |
| `--chart-3` | `oklch(0.769 0.188 70.08)` | 앰버 |
| `--chart-4` | `oklch(0.627 0.265 303.9)` | 퍼플 |
| `--chart-5` | `oklch(0.645 0.246 16.439)` | 빨간-오렌지 |

> **참고**: 다크 모드에서는 차트 색상이 완전히 다른 색상으로 매핑된다. 라이트에서 오렌지였던 `--chart-1`이 다크에서 인디고로 바뀌는 등, 각 테마에서 최적의 가독성을 제공하도록 독립적으로 설계되어 있다.

#### Sidebar Tokens (Dark)

| Token | OKLch 값 | 근사 색상 |
|-------|----------|-----------|
| `--sidebar` | `oklch(0.205 0 0)` | 매우 어두운 회색 |
| `--sidebar-foreground` | `oklch(0.985 0 0)` | Near-white |
| `--sidebar-primary` | `oklch(0.488 0.243 264.376)` | 인디고/블루 (유색!) |
| `--sidebar-primary-foreground` | `oklch(0.985 0 0)` | Near-white |
| `--sidebar-accent` | `oklch(0.269 0 0)` | 어두운 회색 |
| `--sidebar-accent-foreground` | `oklch(0.985 0 0)` | Near-white |
| `--sidebar-border` | `oklch(1 0 0 / 10%)` | 흰색 10% 알파 |
| `--sidebar-ring` | `oklch(0.556 0 0)` | 중간 회색 |

> **참고**: 다크 모드에서 `--sidebar-primary`는 `oklch(0.488 0.243 264.376)` — 인디고 계열의 유색이다. 라이트 모드의 무채색 primary와 달리, 어두운 배경에서 사이드바 선택 항목을 명확하게 강조하기 위한 의도적인 예외이다.

---

### 1-3. 디자인 철학: 의도적 무채색 팔레트 (Intentional Achromatic Palette)

**현재(As-Is)** Smart Fire Hub의 색상 시스템은 의도적으로 **무채색(achromatic)** 을 기반으로 설계되어 있다.

핵심 관찰:

- **Destructive, Chart 토큰을 제외한 모든 토큰의 채도(Chroma)가 0**이다.
  - `--background`, `--foreground`, `--primary`, `--secondary`, `--muted`, `--accent`, `--border`, `--input`, `--ring` — 모두 `oklch(L 0 0)` 형태이다.
- OKLch에서 채도 0은 완전한 무채색(회색 계열)을 의미하며, 색상각(h)은 무의미하다.

**색상이 허용되는 세 가지 영역**:

| 영역 | 색상 | 근거 |
|------|------|------|
| `--destructive` | 빨간색 (`h≈27`) | 위험·삭제 액션의 보편적 신호 색상 |
| `--chart-1` ~ `--chart-5` | 다양한 유색 | 데이터 시각화에서 시리즈 구별을 위한 필수 색상 |
| Favorite star | 노란색(`fill-yellow-400`) | 즐겨찾기 상태를 나타내는 관용적 UI 패턴 |

**이 설계의 장점**:

- **브랜드 중립성**: 다양한 조직·고객이 사용하는 데이터 허브 플랫폼으로서, 특정 브랜드 색상에 종속되지 않는다.
- **콘텐츠 집중**: UI 크롬(chrome)이 무채색이므로, 차트·지도·데이터 등 실제 콘텐츠가 시각적으로 돋보인다.
- **접근성**: 무채색 계열은 색각 이상자에게 동일하게 인식된다.
- **다크 모드 일관성**: 채도가 없으므로 라이트/다크 테마 전환 시 색조 변화 없이 명도만 반전된다.

**현재의 한계**:

- `--success`, `--warning`, `--info` 등 **시맨틱 상태 토큰이 정의되어 있지 않다.**
- 결과적으로 성공·경고·정보 상태를 나타낼 때 컴포넌트 레벨에서 Tailwind 유틸리티 클래스(`bg-green-100`, `text-amber-600` 등)를 직접 사용하는 패턴이 광범위하게 퍼져 있다.
- 이는 다크 모드 대응 누락, 테마 전환 시 일관성 깨짐, 유지보수 어려움으로 이어진다.

---

### 1-4. Semantic Status Tokens (구현 완료)

Phase D-2에서 추가된 시맨틱 상태 토큰. `index.css`에 Light/Dark 모두 정의되어 있다.

#### 토큰 정의

```css
/* ===== Semantic Status Tokens ===== */

:root {
  /* Success */
  --success: oklch(0.523 0.165 149.5);          /* 녹색 */
  --success-foreground: oklch(0.985 0 0);        /* Near-white */
  --success-subtle: oklch(0.95 0.05 149.5);      /* 연한 녹색 배경 */

  /* Warning */
  --warning: oklch(0.84 0.16 84);               /* 앰버/황색 */
  --warning-foreground: oklch(0.2 0 0);          /* Near-black */
  --warning-subtle: oklch(0.97 0.04 84);         /* 연한 황색 배경 */

  /* Info */
  --info: oklch(0.55 0.15 240);                  /* 청색 */
  --info-foreground: oklch(0.985 0 0);           /* Near-white */
  --info-subtle: oklch(0.95 0.04 240);           /* 연한 청색 배경 */
}

.dark {
  /* Success (Dark) */
  --success: oklch(0.65 0.15 149.5);            /* 더 밝은 녹색 */
  --success-foreground: oklch(0.985 0 0);        /* Near-white */
  --success-subtle: oklch(0.2 0.04 149.5);       /* 어두운 녹색 배경 */

  /* Warning (Dark) */
  --warning: oklch(0.76 0.14 84);               /* 더 밝은 앰버 */
  --warning-foreground: oklch(0.985 0 0);        /* Near-white */
  --warning-subtle: oklch(0.2 0.04 84);          /* 어두운 황색 배경 */

  /* Info (Dark) */
  --info: oklch(0.7 0.13 240);                  /* 더 밝은 청색 */
  --info-foreground: oklch(0.985 0 0);           /* Near-white */
  --info-subtle: oklch(0.2 0.04 240);            /* 어두운 청색 배경 */
}
```

#### 토큰 사용 패턴

각 시맨틱 상태는 세 가지 토큰으로 구성된다:

| 토큰 패턴 | 역할 | 사용 예 |
|-----------|------|---------|
| `--{status}` | 진한 상태 색상 (아이콘, 배지 배경) | `bg-success text-success-foreground` |
| `--{status}-foreground` | 상태 배경 위 텍스트 | `<Badge variant="success">` |
| `--{status}-subtle` | 연한 상태 배경 (알림 박스, 배너) | `bg-success-subtle text-success` |

#### Tailwind CSS v4 매핑 (권장)

`tailwind.config.ts` 또는 `@theme` 블록에서 다음과 같이 매핑한다:

```css
@theme {
  --color-success: var(--success);
  --color-success-foreground: var(--success-foreground);
  --color-success-subtle: var(--success-subtle);
  --color-warning: var(--warning);
  --color-warning-foreground: var(--warning-foreground);
  --color-warning-subtle: var(--warning-subtle);
  --color-info: var(--info);
  --color-info-foreground: var(--info-foreground);
  --color-info-subtle: var(--info-subtle);
}
```

이후 `bg-success`, `text-warning`, `border-info-subtle` 등의 유틸리티 클래스를 사용할 수 있다.

### 1-5. Domain & Accent Tokens (구현 완료)

도메인 엔티티와 AI 관련 기능의 시각적 구분을 위한 시맨틱 토큰.

#### Domain Tokens

| Token | Light 값 | Dark 값 | 용도 |
|-------|----------|---------|------|
| `--pipeline` | `oklch(0.52 0.14 195)` | `oklch(0.75 0.12 195)` | 파이프라인 엔티티 색상 |
| `--pipeline-foreground` | `oklch(0.985 0 0)` | `oklch(0.985 0 0)` | Pipeline 위 텍스트 |
| `--dataset` | `oklch(0.45 0.2 264)` | `oklch(0.7 0.17 264)` | 데이터셋 엔티티 색상 |
| `--dataset-foreground` | `oklch(0.985 0 0)` | `oklch(0.985 0 0)` | Dataset 위 텍스트 |
| `--dashboard-accent` | `oklch(0.48 0.2 300)` | `oklch(0.7 0.18 300)` | 대시보드 강조 색상 |
| `--dashboard-accent-foreground` | `oklch(0.985 0 0)` | `oklch(0.985 0 0)` | Dashboard accent 위 텍스트 |

#### AI Accent Tokens

AI 관련 UI 요소(AI 분류 스텝, AI 상태 칩, AI 패널 등)에 사용하는 보라색 계열 토큰.

| Token | Light 값 | Dark 값 | 용도 |
|-------|----------|---------|------|
| `--ai-accent` | `oklch(0.52 0.18 293)` | `oklch(0.72 0.15 293)` | AI 기능 강조 색상 (보라색) |
| `--ai-accent-foreground` | `oklch(0.985 0 0)` | `oklch(0.985 0 0)` | AI accent 위 텍스트 |
| `--ai-accent-subtle` | `oklch(0.96 0.03 293)` | `oklch(0.2 0.04 293)` | AI 기능 연한 배경 |

사용 예:
```tsx
{/* AI 분류 스텝 헤더 */}
<div className="bg-ai-accent-subtle text-ai-accent border border-ai-accent/20">
  AI 분류 스텝
</div>

{/* AI 상태 칩 */}
<span className="text-ai-accent">응답 중</span>
```

#### Caution Tokens

orange 계열 주의/경고 색상. warning(amber)보다 강한 주의를 표현한다.

| Token | Light 값 | Dark 값 | 용도 |
|-------|----------|---------|------|
| `--caution` | `oklch(0.7 0.15 55)` | `oklch(0.78 0.13 55)` | 주의 색상 (오렌지) |
| `--caution-foreground` | `oklch(0.2 0 0)` | `oklch(0.985 0 0)` | Caution 위 텍스트 |
| `--caution-subtle` | `oklch(0.96 0.04 55)` | `oklch(0.2 0.04 55)` | 주의 연한 배경 |

사용 예:
```tsx
{/* 토큰 사용량 경고 */}
<span className="text-caution">토큰 50% 초과</span>

{/* 데이터 갱신 경과 표시 */}
<Badge className="bg-caution-subtle text-caution">5분 전</Badge>
```

#### Data Type Visualization Tokens

스키마 탐색기(Schema Explorer)에서 SQL 데이터 타입을 시각적으로 구분하기 위한 토큰. 7가지 데이터 타입 카테고리에 각각 고유 색상을 부여한다.

| Token | Light 값 | Dark 값 | 데이터 타입 |
|-------|----------|---------|------------|
| `--dtype-text` | `oklch(0.55 0.15 240)` | `oklch(0.7 0.13 240)` | TEXT, VARCHAR, CHAR |
| `--dtype-number` | `oklch(0.55 0.16 160)` | `oklch(0.7 0.14 160)` | INTEGER, BIGINT, NUMERIC, FLOAT 등 |
| `--dtype-date` | `oklch(0.65 0.15 80)` | `oklch(0.75 0.13 80)` | TIMESTAMP, DATE, TIME |
| `--dtype-boolean` | `oklch(0.55 0.18 300)` | `oklch(0.7 0.16 300)` | BOOLEAN |
| `--dtype-json` | `oklch(0.6 0.16 50)` | `oklch(0.72 0.14 50)` | JSON, JSONB |
| `--dtype-geometry` | `oklch(0.55 0.2 15)` | `oklch(0.7 0.18 15)` | GEOMETRY (PostGIS) |
| `--dtype-uuid` | `oklch(0.55 0.12 200)` | `oklch(0.7 0.1 200)` | UUID |

사용 예:
```tsx
{/* 스키마 탐색기 컬럼 타입 배지 */}
<span className="text-dtype-text">T</span>   {/* TEXT 계열 */}
<span className="text-dtype-number">#</span> {/* 숫자 계열 */}
<span className="text-dtype-date">D</span>   {/* 날짜/시간 */}
```

---

## 2. Hard-coded Color Audit — 하드코딩 색상 감사

> **상태**: ✅ 대부분 완료 (2026-04-08 감사 기준)
> 원래 43건 하드코딩 중 `bg-green-*`, `bg-red-*`, `bg-amber-*`, `bg-blue-*`, `border-*-*`, `text-gray-*` 패턴이 모두 시맨틱 토큰으로 교체됨.
> 아래 목록은 **원본 기록**으로 유지하며, 현재 잔여 항목은 섹션 2-6 참조.

**원래(As-Is)** 아래 파일들에서 시맨틱 토큰 대신 Tailwind 유틸리티 색상 클래스가 직접 사용되었다.
**결과** 시맨틱 상태 토큰 도입 및 교체 완료.

### 2-1. Green — 성공·활성 패턴

| 파일:라인 | 현재 클래스 | 사용 맥락 | 권장 교체 |
|-----------|------------|-----------|-----------|
| `DatasetListPage.tsx:298` | `bg-green-100 text-green-800` | Certified 배지 | `<Badge variant="success">` |
| `DatasetDetailPage.tsx:165` | `bg-green-100 text-green-800` | Certified 배지 | `<Badge variant="success">` |
| `ImportProgressView.tsx:57` | `bg-green-100 text-green-600` | done 상태 표시 | `bg-success-subtle text-success` |
| `ImportProgressView.tsx:149` | `border-green-200 bg-green-50` | 성공 결과 박스 | `border-success/30 bg-success-subtle` |
| `ImportProgressView.tsx:150` | `text-green-700` | 성공 결과 텍스트 | `text-success` |
| `ImportProgressView.tsx:159` | `text-green-700` | 성공 카운트 | `text-success` |
| `ImportValidationSection.tsx:53` | `text-green-600` | 검증 성공 메시지 | `text-success` |
| `SqlQueryEditor.tsx:172` | `bg-green-50 text-green-700` | 성공 메시지 박스 | `bg-success-subtle text-success` |
| `ColumnStats.tsx:189,229,311` | `bg-green-500` | 차트 막대 색상 | `bg-chart-2` 또는 `bg-success` |
| `LinkedPipelineStatus.tsx:23` | `bg-green-400` | 활성 상태 점(dot) | `bg-success` |
| `MessageBubble.tsx:108` | `text-green-600` | AI 도구 실행 성공 | `text-success` |
| `ApiCallPreview.tsx:42` | `text-green-600` | JSON 숫자 값 | `text-success` |

### 2-2. Red — 에러·비권장 패턴

| 파일:라인 | 현재 클래스 | 사용 맥락 | 권장 교체 |
|-----------|------------|-----------|-----------|
| `DatasetListPage.tsx:303` | `bg-red-100 text-red-800` | Deprecated 배지 | `<Badge variant="destructive">` |
| `DatasetDetailPage.tsx:170` | `bg-red-100 text-red-800` | Deprecated 배지 | `<Badge variant="destructive">` |
| `ImportProgressView.tsx:59` | `bg-red-100 text-red-600` | failed 상태 표시 | `bg-destructive/10 text-destructive` |
| `ImportProgressView.tsx:169` | `border-red-200 bg-red-50` | 에러 결과 박스 | `border-destructive/30 bg-destructive/5` |
| `ImportProgressView.tsx:170` | `text-red-700` | 에러 결과 텍스트 | `text-destructive` |
| `ImportProgressView.tsx:175` | `text-red-600` | 에러 메시지 | `text-destructive` |
| `ColumnStats.tsx:189,232,316` | `bg-red-500` | 차트 막대 색상 | `bg-chart-5` 또는 `bg-destructive` |

### 2-3. Amber/Yellow — 경고 패턴

| 파일:라인 | 현재 클래스 | 사용 맥락 | 권장 교체 |
|-----------|------------|-----------|-----------|
| `ImportModeSelector.tsx:51` | `border-amber-300 bg-amber-50 text-amber-800` | 경고 배너 | `border-warning/30 bg-warning-subtle text-warning-foreground` |
| `ImportMappingTable.tsx:42` | `border-amber-300 bg-amber-50 text-amber-800` | 경고 배너 | `border-warning/30 bg-warning-subtle text-warning-foreground` |
| `ColumnDialog.tsx:126-127` | `bg-amber-50 border-amber-200 text-amber-800` | 경고 메시지 박스 | `bg-warning-subtle border-warning/30 text-warning-foreground` |
| `DatasetMapTab.tsx:66` | `border-yellow-200 bg-yellow-50 text-yellow-800` | 경고 (다크 모드 대응 있음) | `border-warning/30 bg-warning-subtle text-warning-foreground` |
| `WebhookTriggerForm.tsx:57-59` | `bg-amber-50 border-amber-200 text-amber-600/800` | 경고 안내 | `bg-warning-subtle border-warning/30 text-warning` |
| `ApiTriggerForm.tsx:50-52` | `bg-amber-50 border-amber-200 text-amber-600/800` | 경고 안내 | `bg-warning-subtle border-warning/30 text-warning` |
| `DatasetColumnsTab.tsx:126` | `text-amber-600` | GIS 컬럼 표시 | `text-warning` |
| `ImportValidationSection.tsx:64-66` | `text-amber-600` | 검증 에러 표시 | `text-warning` |
| `MessageBubble.tsx:113` | `text-yellow-600` | AI 도구 실행 중 | `text-warning` |
| `DatasetListPage.tsx:286-287` | `fill-yellow-400 text-yellow-400` | 즐겨찾기 별 | 유지 (관용적 패턴) |
| `DatasetDetailPage.tsx:155-156` | `fill-yellow-400 text-yellow-400` | 즐겨찾기 별 | 유지 (관용적 패턴) |
| `ColumnStats.tsx:189,231` | `bg-yellow-500` | 차트 막대 색상 | `bg-chart-4` 또는 `bg-warning` |

> **참고**: 즐겨찾기 별(`fill-yellow-400`)은 보편적인 UI 관용 패턴이므로 교체 대상에서 제외한다.

### 2-4. Blue — 정보·활성 패턴

| 파일:라인 | 현재 클래스 | 사용 맥락 | 권장 교체 |
|-----------|------------|-----------|-----------|
| `ImportProgressView.tsx:58` | `bg-blue-100 text-blue-600` | active 상태 표시 | `bg-info-subtle text-info` |
| `ImportProgressView.tsx:90` | `bg-blue-500` | 프로그레스 바 | `bg-primary` 또는 `bg-info` |
| `ImportProgressView.tsx:114` | `text-blue-500` | 로딩 스피너 | `text-info` |
| `DatasetDataTab.tsx:254` | `hover:bg-blue-400 active:bg-blue-500` | 액션 버튼 호버 | `hover:bg-primary/80 active:bg-primary/90` |
| `QueryEditorPage.tsx:201` | `text-blue-500` | 테이블 아이콘 | `text-info` |
| `ColumnStats.tsx:269` | `bg-blue-500` | 차트 막대 색상 | `bg-chart-1` 또는 `bg-info` |
| `ApiCallPreview.tsx:39,63,102` | `text-blue-500/400/600` | JSON 문자열·키 값 | `text-info` |

### 2-5. Gray — 중립 패턴

| 파일:라인 | 현재 클래스 | 사용 맥락 | 권장 교체 |
|-----------|------------|-----------|-----------|
| `StepNode.tsx:169,218` | `text-gray-400` | 파이프라인 노드 버튼 | `text-muted-foreground` |
| `ExecutionStepPanel.tsx:145-168` | `bg-gray-900/500/700/300/400` | 실행 상태 점(dot) 5종 | `bg-foreground/N` 또는 상태별 시맨틱 색상 |
| `EditorHeader.tsx:93` | `text-gray-500` | 구분선 텍스트 | `text-muted-foreground` |
| `ApiCallPreview.tsx:36,49,53,69,74,84,88` | `text-gray-400/500` | JSON 포매팅 기호 | `text-muted-foreground` |

### 2-6. Tailwind 클래스 교체 현황 (2026-04-08)

위 목록(2-1~2-5)의 Tailwind 하드코딩 색상은 **대부분 시맨틱 토큰으로 교체 완료**. 잔여 항목:

| 파일 | 클래스 | 상태 | 비고 |
|------|--------|------|------|
| `DatasetListPage.tsx` | `fill-yellow-400 text-yellow-400` | 유지 | 즐겨찾기 별 (관용적 패턴) |
| `DatasetDetailPage.tsx` | `fill-yellow-400 text-yellow-400` | 유지 | 즐겨찾기 별 (관용적 패턴) |
| `schema-explorer-utils.ts` | ~~`text-blue-500` 등 7종~~ | ✅ 교체 완료 | `--dtype-*` 시맨틱 토큰으로 교체 |

### 2-7. Hex/RGB 인라인 스타일 잔여 (2026-04-08 감사)

Tailwind 클래스 외에 **인라인 스타일의 hex/rgb 하드코딩**이 잔존한다. 라이브러리 제약으로 Tailwind 클래스를 사용할 수 없는 영역이지만, 대부분 CSS 변수 `var(--*)` 사용은 가능하다.

| 영역 | 파일 | 건수 | 교체 | 비고 |
|------|------|------|------|------|
| DAG 노드 | `StepNode.tsx` | ~36건 | **P2** | 스텝 타입 color/bg (hex 16건), 실행 상태 (rgb 15건), 오버레이 (rgba 5건) |
| 차트 palette | `*ChartView.tsx` (5개) | ~40건 | P2 검토 | Recharts hex — `getComputedStyle` 변환 가능 |
| 지도 팝업 | `FeaturePopup.tsx` | ~12건 | P2 | Mapbox 팝업 CSS, 다크모드 미지원 |
| 코드 에디터 | `ScriptEditor.tsx` | 2건 | P3 | CodeMirror 현재 줄 하이라이트 rgba |
| 지도 레이어 | `GeoJsonLayer.tsx` | 4건 | 예외 | Mapbox GL paint 스펙 — CSS 변수 미지원 |
| 테마 프리뷰 | `UserNav.tsx` | 3건 | 예외 | 테마 셀렉터 미리보기 (의도적) |

> 상세 교체 계획은 [13-migration-backlog.md](./13-migration-backlog.md)의 "P2: Hex/RGB 하드코딩 마이그레이션" 참조.

---

## 3. Border Radius Scale — 모서리 반경 스케일

**현재(As-Is)** 기준 반경은 `--radius: 0.625rem` (약 10px)이며, 이를 기반으로 7개 레벨의 파생 토큰이 정의된다.

| Token | 계산식 | 근사값 (px) | 주요 사용처 |
|-------|--------|------------|-------------|
| `--radius-sm` | `calc(var(--radius) - 4px)` | ≈ 6px | Badge, 소형 chip, 태그 |
| `--radius-md` | `calc(var(--radius) - 2px)` | ≈ 8px | Input, Button |
| `--radius-lg` | `var(--radius)` | = 10px | 카드 기본, 일반 컨테이너 |
| `--radius-xl` | `calc(var(--radius) + 4px)` | ≈ 14px | Card 컴포넌트, Dialog |
| `--radius-2xl` | `calc(var(--radius) + 8px)` | ≈ 18px | 대형 컨테이너, 모달 |
| `--radius-3xl` | `calc(var(--radius) + 12px)` | ≈ 22px | 현재 미사용 (예약) |
| `--radius-4xl` | `calc(var(--radius) + 16px)` | ≈ 26px | 현재 미사용 (예약) |

**설계 특징**:

- 모든 레벨은 `--radius` 단일 변수에서 파생되므로, `--radius` 값 하나만 변경해도 전체 시스템의 둥글기가 일괄 조정된다.
- shadcn/ui의 `rounded-sm`, `rounded-md`, `rounded-lg`, `rounded-xl` 유틸리티는 이 CSS 변수를 참조한다.
- `--radius-3xl`, `--radius-4xl`은 정의만 되어 있고 현재 컴포넌트에서 사용되지 않는다. 필요 시 활용하거나, 불필요하다고 판단되면 Phase D-2에서 제거를 검토할 수 있다.

**권장(To-Be)**:

- `--radius` 기준값을 변경할 때는 Storybook(또는 동등한 UI 카탈로그)에서 모든 컴포넌트를 시각적으로 검토한다.
- `--radius-3xl`, `--radius-4xl`의 실제 사용처가 생기기 전까지 문서화만 유지한다.

---

## 4. Z-Index Scale — 레이어 순서 스케일

**현재(As-Is)** CSS 커스텀 프로퍼티로 정의된 z-index 스케일은 없다. Tailwind 유틸리티 클래스(`z-10`, `z-30`, `z-40`, `z-50`)를 직접 사용하며, 다음의 암묵적 계층 구조가 적용된다.

| 계층명 (비공식) | Tailwind 값 | z-index | 용도 | 파일 |
|----------------|------------|---------|------|------|
| content | `z-10` | 10 | 스티키 테이블 헤더, 캔버스 오버레이, AI 사이드 패널, Avatar 배지 | `DatasetDataTab.tsx`, `PipelineCanvas.tsx`, `AISidePanel.tsx`, `avatar.tsx` |
| header | `z-30` | 30 | AppLayout 상단 헤더 | `AppLayout.tsx:372` |
| overlay | `z-40` | 40 | 모바일 사이드바 배경 오버레이 | `AppLayout.tsx:243` |
| modal | `z-50` | 50 | 사이드바, Dialog, Popover, 플로팅 AI 패널, Tooltip, Select, Dropdown | `AppLayout.tsx:251`, `dialog.tsx`, `popover.tsx`, `tooltip.tsx`, `select.tsx`, `dropdown-menu.tsx`, `alert-dialog.tsx`, `AIFloating.tsx` |

**현재의 한계**:

- `z-50` 레이어에 사이드바, Dialog, Tooltip, Select, Floating 패널 등이 혼재한다. 이들이 동시에 렌더링될 때 DOM 순서에 의존하여 레이어 충돌이 잠재적으로 발생할 수 있다.
- CSS 변수로 명시적으로 정의되지 않아, 새로운 레이어드 컴포넌트를 추가할 때 어느 값을 써야 하는지 불명확하다.

**권장(To-Be)** Phase D-2에서 다음 CSS 변수 스케일 도입을 검토한다:

```css
:root {
  --z-content:  10;   /* 스티키 헤더, 캔버스 오버레이 */
  --z-header:   30;   /* 앱 헤더 */
  --z-overlay:  40;   /* 모바일 드로어 오버레이 */
  --z-sidebar:  50;   /* 사이드바 패널 */
  --z-dialog:   60;   /* Dialog, AlertDialog */
  --z-popover:  70;   /* Popover, Dropdown, Select */
  --z-tooltip:  80;   /* Tooltip (항상 최상위) */
  --z-floating: 90;   /* 플로팅 AI 패널 */
}
```

이렇게 명시적으로 분리하면 레이어 충돌을 예방하고, `dialog` 위에 `popover`가 올 수 있도록 보장할 수 있다.

---

## 5. Shadow Usage — 그림자 사용 패턴

**현재(As-Is)** 그림자는 Tailwind의 표준 shadow 유틸리티를 사용한다. CSS 커스텀 프로퍼티로 정의된 shadow 토큰은 없다.

| Shadow 클래스 | 사용 컴포넌트 | 맥락 |
|--------------|-------------|------|
| `shadow-sm` | `StepNode.tsx` (버튼), `PipelineCanvas.tsx` (정보 오버레이) | 경미한 입체감, 평면 UI에서의 약한 분리감 |
| `shadow-md` | `popover.tsx` (Popover 콘텐츠) | 콘텐츠 레이어 분리 |
| `shadow-lg` | `dialog.tsx`, `alert-dialog.tsx` | 모달 분리감 |
| `shadow-2xl` | `AIFloating.tsx` (플로팅 AI 패널) | 강한 부유감, 캔버스와의 명확한 분리 |

**관찰된 패턴**:

- Shadow 강도는 레이어 깊이(z-index)와 대략적으로 상관관계가 있다: 더 높은 z-index일수록 더 강한 shadow를 사용한다.
- `shadow-sm`은 인라인 UI 요소(버튼, 작은 오버레이)에, `shadow-2xl`은 독립적인 플로팅 패널에 사용된다.
- `shadow-xl`은 현재 사용되지 않는다.

**권장(To-Be)**:

- 현재 사용 패턴은 일관성이 있으므로 단기적으로 변경 필요성은 낮다.
- 새로운 레이어드 컴포넌트 추가 시 위 패턴(`shadow-sm` → `shadow-md` → `shadow-lg` → `shadow-2xl`)을 참조하여 일관성을 유지한다.
- Phase D-2에서 z-index 스케일 토큰화와 함께 shadow 토큰화도 검토할 수 있다:

```css
:root {
  --shadow-content:  var(--shadow-sm);   /* z-content 레이어 */
  --shadow-overlay:  var(--shadow-md);   /* z-overlay 레이어 */
  --shadow-dialog:   var(--shadow-lg);   /* z-dialog 레이어 */
  --shadow-floating: var(--shadow-2xl);  /* z-floating 레이어 */
}
```

---

## 변경 이력

| 날짜 | 버전 | 내용 |
|------|------|------|
| 2026-03-02 | v1.0 | 최초 작성 — Color, Border Radius, Z-Index, Shadow 토큰 현황 감사 및 권장 방향 정의 |
