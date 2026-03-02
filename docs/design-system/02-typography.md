# 02. Typography

Smart Fire Hub 타이포그래피 시스템 — As-Is 감사 결과와 To-Be 권장 스케일.

---

## 1. 현재(As-Is) 감사

코드베이스를 스캔하여 실제 사용 중인 타이포그래피 패턴을 정리했다.

| Level | Tailwind Classes | Size | Weight | 용도 | 빈도 |
|-------|-----------------|------|--------|------|------|
| Page Title | `text-2xl font-bold` | 24px | 700 | 모든 목록 페이지 h1 태그 | 22+ 페이지 |
| Stat Value | `text-2xl font-bold` | 24px | 700 | HomePage 통계 카드 숫자 | 4 곳 |
| Dialog Title | `text-lg font-semibold` | 18px | 600 | Dialog/Sheet 제목 (shadcn 기본값) | ~15 곳 |
| Section Head | `text-base font-semibold` | 16px | 600 | 섹션 헤딩 (예: "내 대시보드") | ~10 곳 |
| Card Label | `text-sm font-medium` | 14px | 500 | 카드 제목, 테이블 헤더, 네비게이션 항목 | ~50 곳 |
| Body | `text-sm` | 14px | 400 | 일반 본문, 폼 레이블 | ~200 곳 |
| Caption | `text-xs` | 12px | 400 | 배지, 메타데이터, 타임스탬프 | ~80 곳 |
| Tiny | `text-[10px]` | 10px | 400 | 쿼리 에디터 레이블 (드물게 사용) | ~3 곳 |
| Sidebar Nav | `text-[13px]` | 13px | 400 | 사이드바 네비게이션 항목 | AppLayout |

### 현재 문제점

- **충돌하는 의미**: Page Title과 Stat Value가 동일한 클래스(`text-2xl font-bold`)를 사용하나 의미적으로 다른 요소다.
- **누락된 단계**: `text-xl`(20px)은 거의 미사용, `text-3xl` 이상은 완전히 미사용.
- **비일관적인 line-height**: `leading-*` 클래스를 대부분 생략하여 브라우저 기본값에 의존한다.
- **letter-spacing 부재**: 큰 제목에도 tracking 조정이 없어 가독성이 저하된다.
- **`text-[10px]` 사용**: 디자인 토큰 범위 밖의 매직 넘버로, 유지보수 부채다.

---

## 2. 권장(To-Be) 타이포그래피 스케일

Vercel Geist 3-tier 시스템(Heading / Body / Label)을 참고하여 13개 의미론적 레벨을 정의한다.

### 2.1 전체 스케일

| Semantic Name | 용도 | Tailwind Classes | Size | Line-Height | Weight | Tracking |
|---------------|------|-----------------|------|-------------|--------|----------|
| `heading-page` | 페이지 타이틀 (H1) | `text-[32px] leading-[40px] font-semibold tracking-tight` | 32px | 40px | 600 | -0.025em |
| `heading-section` | 섹션 제목 (H2) | `text-2xl leading-8 font-semibold tracking-tight` | 24px | 32px | 600 | -0.025em |
| `heading-card` | 카드/다이얼로그 제목 (H3) | `text-xl leading-7 font-semibold` | 20px | 28px | 600 | normal |
| `heading-group` | 그룹 라벨 (H4) | `text-base leading-6 font-semibold` | 16px | 24px | 600 | normal |
| `heading-column` | 테이블 컬럼 헤더 | `text-sm leading-5 font-semibold` | 14px | 20px | 600 | normal |
| `body-primary` | 주요 본문 텍스트 | `text-base leading-7` | 16px | 28px | 400 | normal |
| `body-secondary` | 보조 본문, 카드 설명 | `text-sm leading-6` | 14px | 24px | 400 | normal |
| `caption` | 캡션, 힌트, 타임스탬프 | `text-[13px] leading-5` | 13px | 20px | 400 | normal |
| `label-primary` | UI 라벨, 폼 레이블 | `text-sm leading-5 font-medium` | 14px | 20px | 500 | normal |
| `label-secondary` | 배지, 태그, 메타 | `text-xs leading-4 font-medium` | 12px | 16px | 500 | normal |
| `code-inline` | 인라인 코드, API 키 | `text-sm font-mono` | 14px | - | 400 | normal |
| `code-block` | 코드 블록, SQL 에디터 | `text-[13px] leading-5 font-mono` | 13px | 20px | 400 | normal |
| `data-number` | 데이터 테이블 숫자 | `text-sm font-mono tabular-nums` | 14px | - | 400 | normal |

### 2.2 Heading 계층

`heading-page`부터 `heading-column`까지 5단계. 반드시 의미론적 HTML 요소(`h1`–`h4`, `th`)와 함께 사용한다. 시각적 스타일과 HTML 계층이 일치해야 스크린 리더 접근성이 보장된다.

```tsx
// heading-page — 페이지 진입점, 페이지당 1개만
<h1 className="text-[32px] leading-[40px] font-semibold tracking-tight">
  데이터셋 목록
</h1>

// heading-section — 페이지 내 주요 섹션 구분
<h2 className="text-2xl leading-8 font-semibold tracking-tight">
  내 대시보드
</h2>

// heading-card — 카드, 다이얼로그, 시트 제목
<h3 className="text-xl leading-7 font-semibold">
  파이프라인 생성
</h3>

// heading-group — 폼 섹션, 설정 그룹 라벨
<h4 className="text-base leading-6 font-semibold">
  연결 설정
</h4>

// heading-column — 데이터 테이블 컬럼 헤더
<th className="text-sm leading-5 font-semibold text-muted-foreground">
  생성일
</th>
```

### 2.3 Body 계층

본문 텍스트. `body-primary`는 주요 콘텐츠 영역에, `body-secondary`는 카드 내 설명이나 보조 정보에 사용한다.

```tsx
// body-primary — 주요 본문, 긴 설명 텍스트
<p className="text-base leading-7 text-foreground">
  이 데이터셋은 공간 쿼리를 지원합니다. PostGIS 함수를 활용하여
  반경 내 객체를 검색할 수 있습니다.
</p>

// body-secondary — 카드 설명, 보조 정보
<p className="text-sm leading-6 text-muted-foreground">
  마지막 실행: 2시간 전
</p>

// caption — 힌트, 타임스탬프, 부가 메타데이터
<span className="text-[13px] leading-5 text-muted-foreground">
  2026-03-02 14:32
</span>
```

### 2.4 Label 계층

UI 컨트롤에 붙는 레이블. 본문 텍스트와 달리 line-height보다 수직 정렬이 중요하므로 `leading-5`(20px) 고정을 기본으로 한다.

```tsx
// label-primary — 폼 레이블, 버튼 텍스트, 네비게이션 항목
<label className="text-sm leading-5 font-medium">
  데이터베이스 호스트
</label>

// label-secondary — 배지, 태그, 상태 칩
<span className="text-xs leading-4 font-medium px-2 py-0.5 rounded-full bg-muted">
  PostgreSQL
</span>
```

### 2.5 Code / Data 계층

코드와 숫자 데이터 전용. `font-mono`를 반드시 명시하여 가변폭 폰트와 구분한다.

```tsx
// code-inline — 인라인 코드 스니펫, API 키, 테이블/컬럼 이름
<code className="text-sm font-mono bg-muted px-1.5 py-0.5 rounded">
  SELECT * FROM public.datasets
</code>

// code-block — SQL 에디터, 코드 뷰어 (Monaco, CodeMirror 등)
// font-family는 에디터 자체 설정에 위임하고 size만 지정
<div className="text-[13px] leading-5 font-mono">
  {/* editor content */}
</div>

// data-number — 데이터 테이블 내 숫자 (행 수, 파일 크기, 통계값)
<td className="text-sm font-mono tabular-nums text-right">
  1,234,567
</td>
```

---

## 3. As-Is → To-Be 마이그레이션 매핑

| As-Is 클래스 조합 | 용도 | To-Be Semantic Name | To-Be 클래스 조합 |
|-----------------|------|---------------------|-----------------|
| `text-2xl font-bold` | 페이지 h1 | `heading-section` | `text-2xl leading-8 font-semibold tracking-tight` |
| `text-2xl font-bold` | 통계 숫자 (홈) | `data-number` (확대) | `text-2xl font-mono tabular-nums font-semibold` |
| `text-lg font-semibold` | Dialog/Sheet 제목 | `heading-card` | `text-xl leading-7 font-semibold` |
| `text-base font-semibold` | 섹션 헤딩 | `heading-group` | `text-base leading-6 font-semibold` |
| `text-sm font-medium` | 카드 제목, 네비게이션 | `label-primary` | `text-sm leading-5 font-medium` |
| `text-sm` | 일반 본문, 폼 레이블 | `body-secondary` / `label-primary` | 문맥에 따라 분기 |
| `text-xs` | 배지, 메타데이터 | `label-secondary` | `text-xs leading-4 font-medium` |
| `text-[10px]` | 쿼리 에디터 레이블 | `label-secondary` 또는 `caption` | `text-xs leading-4 font-medium` |
| `text-[13px]` | 사이드바 네비게이션 | `caption` | `text-[13px] leading-5` |

> **마이그레이션 우선순위**: `text-[10px]` 제거(즉시) → Page Title 통일(sprint 내) → 나머지 점진적 적용.

---

## 4. font-mono 사용 규칙

`font-mono`는 아래 5가지 컨텍스트에서만 사용한다. 그 외 일반 UI 텍스트에는 절대 사용하지 않는다.

| 컨텍스트 | Semantic Name | 예시 |
|---------|---------------|------|
| API 키, 토큰 | `code-inline` | `sk-ant-...` |
| SQL 쿼리, 코드 | `code-block` | `SELECT id FROM ...` |
| 테이블 이름, 컬럼 이름 | `code-inline` | `public.datasets` |
| 코드 블록 | `code-block` | 에디터 내 전체 영역 |
| 숫자 데이터 (테이블) | `data-number` | `1,234,567` |

---

## 5. Letter-Spacing 규칙

`tracking-tight`(`letter-spacing: -0.025em`)은 **Heading 계층에서 32px 이상**에만 적용한다.

| 조건 | Tracking | 이유 |
|------|----------|------|
| `heading-page` (32px), `heading-section` (24px) | `tracking-tight` | 큰 글자는 자간이 넓어 보여 타이트하게 보정 |
| `heading-card` (20px) 이하 | normal | 작은 크기에서 tight tracking은 가독성 저하 |
| Body / Label / Code | normal | 읽기 텍스트에 tracking 조정 금지 |
| `letter-spacing` 매직 넘버 (`tracking-[...]`) | 금지 | 디자인 토큰 범위 밖 |

---

## 6. Font Family

### 6.1 현재(As-Is)

Tailwind CSS 기본 시스템 스택을 사용한다.

```css
font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont,
  "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans", sans-serif;
```

`font-mono`는 Tailwind 기본 mono 스택:

```css
font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas,
  "Liberation Mono", "Courier New", monospace;
```

### 6.2 권장(To-Be) — D-2 검토 항목

Vercel Geist Sans / Geist Mono 도입을 D-2(디자인 시스템 v2) 단계에서 검토한다.

**도입 시 이점**:
- Geist Sans: 화면 최적화 hinting, 자간 일관성
- Geist Mono: 코드 에디터와 UI 간 mono 폰트 통일

**도입 방법** (검토용 참고):

```bash
pnpm add --filter firehub-web geist
```

```ts
// apps/firehub-web/src/main.tsx
import { GeistSans } from 'geist/font/sans';
import { GeistMono } from 'geist/font/mono';
```

```css
/* tailwind.config.ts 또는 globals.css */
:root {
  --font-sans: 'Geist', ui-sans-serif, system-ui, sans-serif;
  --font-mono: 'Geist Mono', ui-monospace, monospace;
}
```

> **현재 액션**: 시스템 폰트 유지. D-2 계획 수립 시 Geist 도입 여부를 사용자와 협의한다.

---

## 7. 접근성 가이드라인

- **최소 폰트 크기**: 본문 텍스트는 14px(`text-sm`) 이상. `text-[10px]` 사용 금지.
- **색상 대비**: `text-muted-foreground`는 배경 대비 WCAG AA(4.5:1) 이상을 유지해야 한다. 12px 이하 텍스트는 AAA(7:1) 권장.
- **line-height**: 본문(`body-primary`, `body-secondary`)은 font-size 대비 1.5배 이상(`leading-6` 이상) 유지.
- **HTML 의미론**: 시각적 스타일이 아닌 문서 구조에 따라 `h1`–`h6` 계층을 결정한다. 스타일은 CSS로 분리.

---

## 8. Tailwind CSS v4 참고 사항

현재 프로젝트는 Tailwind CSS v4를 사용한다. v4에서 달라진 점:

- `text-[32px]`과 같은 arbitrary value는 그대로 지원.
- `leading-[40px]`도 arbitrary value로 지원.
- `tabular-nums`는 v4에서 `font-variant-numeric: tabular-nums`로 처리.
- `tracking-tight`은 `-0.025em`으로 동일.

```tsx
// v4에서 tabular-nums 사용 예
<td className="text-sm font-mono tabular-nums text-right">
  1,234,567
</td>
```
