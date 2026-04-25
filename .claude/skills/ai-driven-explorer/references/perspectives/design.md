# Design 관점 — 시각/카피/레이아웃 검증 (전문 디자이너 시점)

매트릭스 파일: `.coverage-matrix-design.md`.

> 출처: Refactoring UI (Adam Wathan/Steve Schoger) · NN/g Usability Heuristics · Material Design 3 · Microsoft Style Guide · Tufte data-ink ratio · shadcn/ui & Tailwind v4. 권위 출처는 §4.

이 perspective는 **UI가 동작하는가**가 아니라 **UI가 사람에게 잘 전달되는가**를 본다. 기능 결함이 아닌 사용자 경험·시각 일관성·정보 위계 문제를 발견한다. 캡처 1장으로 판정 가능한 문제만 포함한다 — 토큰 정적 분석은 §5에서 별도 트랙으로 분리.

## 1. 우선순위 (12 카테고리)

| # | 카테고리 | 핵심 |
|---|---|---|
| 1 | **시각 위계 (Hierarchy)** | 그레이스케일에서도 우선순위가 보이는가 |
| 2 | **카피 / Voice & Tone** | 톤·길이·정확성·일관성 |
| 3 | **간격 / 그리드 (Spacing)** | 4/8 배수 그리드, Gestalt 근접 원칙 |
| 4 | **컬러 / 토큰** | shadcn 시맨틱 토큰, 의미 색상 일관 |
| 5 | **상태 (Empty/Loading/Error)** | 3-state matrix 강제 |
| 6 | **정보 밀도 (Density)** | Tufte data-ink ratio, 데이터 허브는 밀도↑ |
| 7 | **모션 / 듀레이션** | M3 토큰 기반 수치 (100~500ms) |
| 8 | **포커스 / 키보드 가시성 (시각)** | WCAG 2.4.7/2.4.11 시각 부분 |
| 9 | **다크모드 페어링** | 라이트와 1:1 비교, halation 방지 |
| 10 | **아이콘 일관성 (lucide)** | weight·size·정렬 |
| 11 | **데이터 시각화** | chartjunk 제거, 색맹 안전 |
| 12 | **좁은 데스크탑 안전성** | 1280px 폭에서 깨지지 않음, 긴 텍스트 줄바꿈/ellipsis (※ 데스크탑 전용 — 모바일 검증 안 함) |

## 2. 카테고리별 체크리스트

### 2.1 시각 위계
- [ ] Primary CTA 1개가 페이지당 명확히 단 1개로 선언되었는가 (색·크기·여백 모두에서 우월)
- [ ] H1/H2/Body의 폰트 크기 비율이 최소 1.25배 이상 차이를 보이는가 (타입 스케일)
- [ ] 중요한 텍스트와 보조 텍스트가 **font-weight + color** 두 축으로 구분되는가
- [ ] 보조/메타 텍스트가 본문보다 작아지면서 동시에 옅어졌는가
- [ ] 그레이스케일 캡처에서 클릭해야 할 요소가 식별되는가 (색 의존 금지)
- [ ] 페이지 제목 영역과 콘텐츠 영역이 시각적으로 분리되는가
- [ ] 비활성/보조 정보가 활성 정보보다 시각적으로 가벼운가 (`text-muted-foreground`)
- [ ] 카드/패널 내부에서도 자체 위계가 있는가 (제목 → 내용 → 메타)

### 2.2 카피 / UX Writing (Microsoft Style Guide + Mailchimp)
- [ ] 버튼 라벨이 **동사+명사** 형태로 행동을 명시하는가 ("저장", "데이터셋 만들기" / "확인" 같은 모호한 단어 X)
- [ ] 버튼·라벨·체크박스 끝에 마침표가 없는가 (Microsoft 규칙)
- [ ] 에러 메시지가 "What went wrong + Why + How to fix" 3요소를 갖추는가
- [ ] "오류가 발생했습니다" 같이 무정보 카피가 없는가
- [ ] Empty state에 **이유 1줄 + 다음 행동 CTA**가 있는가
- [ ] Toast가 결과를 동사 과거형으로 명확히 전달하는가 ("저장됨", "3개 항목 삭제됨")
- [ ] 같은 개념을 같은 단어로 부르는가 (데이터셋 vs 데이터 세트, 작업 vs 잡 — 통일)
- [ ] Placeholder가 라벨을 대체하지 않는가
- [ ] 숫자 단위·복수형·시간 표현이 통일됐는가 ("3 minutes ago" vs "3분 전" 혼용 X)
- [ ] 대문자/문장형 케이스가 일관된가 (sentence case vs title case 혼용 X)
- [ ] 약관/위험 작업 문구가 결과를 명시하는가 ("이 동작은 되돌릴 수 없습니다")

### 2.3 간격·그리드 (Gestalt 근접)
- [ ] 간격이 4 또는 8 배수 스케일인가 (5px, 7px, 13px 같은 임의 값 X)
- [ ] 관련된 요소 그룹이 더 가깝고, 다른 그룹 사이가 더 먼가
- [ ] 카드/섹션의 외부 여백이 내부 여백보다 큰가
- [ ] 폼 라벨과 입력 필드의 간격이 입력 필드 사이 간격보다 작은가
- [ ] 페이지 좌우 여백이 일관된가 (컨테이너 max-width·padding 통일)
- [ ] 아이콘과 텍스트 사이 간격이 모든 위치에서 동일한가 (`gap-2` 등)
- [ ] 버튼 좌우 패딩이 상하보다 큰가 (직사각형 비율)
- [ ] 표(table)에서 셀 패딩이 좁아 텍스트가 붙어 보이지 않는가

### 2.4 컬러 / 토큰
- [ ] 한 화면에 **브랜드 1 + 보조 1 + 그레이 N**으로 컬러 수가 통제되는가
- [ ] 회색이 차가운/따뜻한 한쪽 hue로 일관되는가
- [ ] 위험·성공·정보 컬러가 토큰으로 분리되어 있고 의미가 일관된가
- [ ] 흰색 배경 위에서 카드가 떠 보이는가 (살짝 톤다운 또는 border)
- [ ] 동일 컴포넌트의 hover/active/disabled가 같은 hue 안에서 lightness만 변하는가
- [ ] 본문 4.5:1 대비 확보 (특히 `text-muted-foreground` 작은 글씨에서 너무 옅지 않은가)
- [ ] Hover 색상 변화가 너무 미세하지 않은가 (인지 가능)
- [ ] 채도 높은 색은 작은 영역(아이콘·뱃지·CTA)에만 쓰였는가

### 2.5 상태 (Empty / Loading / Error / Skeleton)
- [ ] 모든 데이터 영역에 **3-state(Empty/Loading/Error)** 가 모두 디자인되어 있는가
- [ ] Skeleton이 실제 콘텐츠의 **모양·블록 크기**를 모방하는가
- [ ] Skeleton이 1초 이내 사라지는가 (그 이상이면 spinner+상태 메시지)
- [ ] Empty state에 **일러스트/아이콘 + 제목 + 설명 + CTA** 4요소가 있는가
- [ ] 일러스트가 모노톤/브랜드 톤이며 본문보다 시선을 빼앗지 않는가
- [ ] 첫 사용(empty) vs 검색 결과 없음(no results) vs 권한 없음(forbidden)이 다른 카피로 분기되는가
- [ ] 에러 상태에 retry 버튼 또는 다음 행동 가이드가 있는가
- [ ] 부분 로딩 시 전체 화면이 깜빡이지 않는가

### 2.6 정보 밀도 (Tufte)
- [ ] 표/리스트가 한 화면에서 충분한 행을 보여주는가 (대시보드 10건+)
- [ ] 표 셀에 불필요한 그리드 라인·헤더 그라데이션·radius·shadow가 중첩되지 않는가
- [ ] 같은 정보가 라벨+아이콘+툴팁 3중 중복되지 않는가
- [ ] 카드 안 여백이 너무 많아 1화면당 정보량이 적지 않은가 (데이터 허브는 밀도↑)
- [ ] 핵심 KPI/메타가 카드 첫 줄 또는 좌상단(F-pattern)에 위치하는가
- [ ] 리스트가 좌측 시작점 정렬되어 스캔되는가 (가운데 정렬 본문 X)
- [ ] 보조 메타가 본문보다 작고 옅어 한 번에 무시할 수 있는가
- [ ] 페이지네이션·필터·정렬 UI가 데이터 위/아래 일관 위치인가

### 2.7 모션 / 듀레이션 (Material Design 3 토큰)
- [ ] 마이크로(hover/focus/toggle) 100~150ms (M3 short)
- [ ] 카드/다이얼로그 200~300ms (M3 medium), 풀스크린 350~500ms (M3 long)
- [ ] 사라짐(exit) duration이 등장(enter)보다 짧거나 같은가
- [ ] easing이 linear가 아닌가 (`ease-in-out` / `cubic-bezier(.2,0,0,1)`)
- [ ] 동시에 움직이는 요소가 3개 이하인가
- [ ] 같은 컴포넌트 타입은 같은 duration을 쓰는가
- [ ] `prefers-reduced-motion: reduce` 시 모션이 즉시 또는 페이드만 남는가
- [ ] 무한 회전 spinner가 3초 이상 단독 표시되지 않는가

### 2.8 포커스 / 키보드 가시성 (시각 부분만, WCAG 2.4.7/2.4.11)
- [ ] Tab 이동 시 모든 인터랙티브 요소에 **2px 이상** 포커스 링이 보이는가
- [ ] 포커스 링과 배경 명도 대비 3:1 이상 (다크/라이트 모두)
- [ ] 포커스 링이 카드 모서리에 잘리거나 sticky 헤더에 가려지지 않는가
- [ ] 다이얼로그 열릴 때 포커스가 첫 인풋/닫기 버튼으로 이동하는가
- [ ] Esc로 다이얼로그/팝오버가 시각적으로 즉시 닫히는가
- [ ] 드롭다운 활성 옵션이 시각적으로 표시되는가
- [ ] 포커스 링 색이 destructive 같은 의미 색과 충돌하지 않는가

### 2.9 다크모드 (next-themes)
- [ ] 다크 배경이 순수 #000이 아닌 살짝 들어올린 톤(#0A0A0B~#171717)
- [ ] elevation을 **shadow가 아니라 더 밝은 surface**로 표현하는가 (M3)
- [ ] 라이트의 그림자가 다크에서 사라져 카드 경계가 흐려지지 않는가 (border/lighter surface 대체)
- [ ] 채도 높은 브랜드 컬러가 다크에서 빛 번짐(halation)을 일으키지 않는가
- [ ] 다크 본문 글자가 순백이 아닌가 (#F5F5F5 정도)
- [ ] 라이트/다크 전환 시 깜빡임/색 잔상 없는가 (`color-scheme` 메타)
- [ ] 같은 카드 라이트/다크 캡처 나란히 비교 시 위계가 동일한가
- [ ] 이미지/일러스트에 다크 전용 변형 또는 mix-blend-mode가 있는가
- [ ] 비활성 상태가 다크에서 너무 어두워 사라지지 않는가

### 2.10 아이콘 일관성 (lucide-react)
- [ ] 한 화면 안 아이콘이 모두 같은 라이브러리(lucide)인가
- [ ] stroke-width 일관 (lucide 기본 2)
- [ ] 아이콘 크기가 텍스트 cap height에 맞춰 정렬 (16px 텍스트 + 16px 아이콘)
- [ ] 같은 의미에 같은 아이콘 (삭제는 어디서나 Trash, X 혼용 X)
- [ ] 의미 모호한 아이콘에 라벨/툴팁 (햄버거·점3개 외)
- [ ] 컬러 아이콘과 모노톤 아이콘이 섞이지 않는가

### 2.11 데이터 시각화 (Tufte)
- [ ] 차트에 grid·border·tick·label이 중복되지 않는가 (chartjunk 제거)
- [ ] 범례가 데이터 가까이 배치되어 시선 왕복 없는가
- [ ] 범주 색상이 컬러 토큰과 일치하며 색맹 안전한가
- [ ] 0부터 시작하지 않는 축이 명시 표시되는가
- [ ] ETL 캔버스(xyflow) 노드 간 연결선 교차 최소화
- [ ] 노드 라벨 잘리지 않고 읽히는가 (긴 이름 ellipsis + 툴팁)
- [ ] 미니맵·줌 컨트롤이 캔버스 한 모서리에 고정되어 있는가
- [ ] 데이터 없음 차트가 빈 사각형이 아닌 명시적 empty state

### 2.12 좁은 데스크탑 안전성

> 본 프로젝트는 데스크탑 전용(firehub-web). 모바일·터치 검증 안 함. 단, 13인치 노트북(1280px) 사용자를 위해 좁은 폭에서 깨지지 않는지만 본다.

- [ ] 클릭 타겟 최소 32×32 CSS px (마우스 정밀도 + 모터 접근성)
- [ ] 1280px 폭에서 사이드바·핵심 버튼이 가려지거나 가로 스크롤 발생 X
- [ ] 표가 좁은 폭에서 가로 스크롤 또는 컬럼 우선순위 축소로 대응
- [ ] 긴 단어(데이터셋명·이메일) 줄바꿈/ellipsis 처리
- [ ] 모달이 1280px 폭에서 화면을 벗어나지 않고 안전 여백 유지
- [ ] 폼이 좁은 폭에서 라벨-인풋 정렬이 깨지지 않는가

## 3. shadcn/Tailwind v4 환경 특화 함정

이 항목들은 DOM/style 검사도 같이 본다 (playwright `evaluate`로 computed style 추출).

### 3.1 시맨틱 토큰 미사용 (grep으로 잡힘)
- [ ] `text-gray-500`, `bg-zinc-100` 같은 **무톤 팔레트 직접 사용** 금지 → `text-muted-foreground`, `bg-muted`
- [ ] `text-black`, `bg-white` 직접 사용 → `text-foreground`, `bg-background`
- [ ] `border-gray-200` 직접 사용 → `border` (= `border-border`)
- [ ] `text-red-500` 같은 직접 컬러 → `text-destructive`
- [ ] 하드코딩 hex (`#1E293B`, `rgba(...)`) 검색 0건 (hsl 변수 권장)

### 3.2 다크모드 누락
- [ ] `bg-white` 같이 `dark:` 변형 없는 클래스 (다크에서 흰 박스 발광)
- [ ] 커스텀 그림자 `shadow-[...]`가 다크에서 의미 잃는가
- [ ] `text-gray-900` + `dark:text-gray-100` 같이 둘 다 토큰 미사용 안티패턴
- [ ] 이미지/SVG에 다크 분기(`dark:invert`, `dark:opacity-90`) 누락

### 3.3 Tailwind v4 특유
- [ ] `@theme inline`에 `--color-*` 토큰이 모두 등록되어 있는가
- [ ] `cssVariables: false` 같은 옛 컨피그 잔존 X
- [ ] `oklch()`/`hsl()` 래핑 위치 일관 (변수에 hsl 래핑, @theme에선 var)
- [ ] arbitrary value (`p-[13px]`)가 디자인 시스템 우회하지 않는가

### 3.4 컴포넌트 슬롯 변형
- [ ] shadcn `<Button variant="...">` 외 ad-hoc className으로 색·크기 덮어쓴 곳 없는가
- [ ] Dialog/Popover/Tooltip이 default `bg-popover` `text-popover-foreground` 토큰 사용
- [ ] Form `<Label>`/`<FormDescription>`/`<FormMessage>` 위계 살아있는가
- [ ] 같은 의미의 컴포넌트가 한 곳은 `<Card>`, 다른 곳은 직접 `<div className="rounded-lg border">` 갈리지 않는가

### 3.5 lucide-react
- [ ] `<Icon className="w-4 h-4">`와 `size={16}` 혼용 → 한 가지로 통일
- [ ] `strokeWidth` 컴포넌트별 다르게 지정해 weight 흩어지지 않는가
- [ ] 색을 `color="#..."` prop으로 박지 않고 `text-*` 토큰 currentColor 상속

### 3.6 ring / focus
- [ ] 글로벌 `outline-none` 후 `focus-visible:ring-*` 누락된 컴포넌트
- [ ] `ring-offset` 색이 다크모드에서 배경과 동일해지지 않는가 (`ring-offset-background` 사용)

## 4. 권위 출처

- **Refactoring UI** (Steve Schoger / Adam Wathan): https://refactoringui.com/
- **NN/g 10 Usability Heuristics**: https://www.nngroup.com/articles/ten-usability-heuristics/
- **NN/g — Heuristics for Complex Apps**: https://www.nngroup.com/articles/usability-heuristics-complex-applications/
- **Material Design 3 — Tokens / Type / Motion**: https://m3.material.io/foundations/design-tokens, https://m3.material.io/styles/typography/type-scale-tokens, https://m3.material.io/styles/motion/easing-and-duration/tokens-specs
- **shadcn/ui Theming + Tailwind v4**: https://ui.shadcn.com/docs/theming, https://ui.shadcn.com/docs/tailwind-v4
- **Microsoft Writing Style Guide**: https://learn.microsoft.com/en-us/style-guide/welcome/
- **Tufte data-ink ratio**: https://infovis-wiki.net/wiki/Data-Ink_Ratio
- **Stripe / Carbon Empty State**: https://docs.stripe.com/stripe-apps/patterns/empty-state, https://carbondesignsystem.com/patterns/empty-states-pattern/
- **NN/g Dark Mode**: https://www.nngroup.com/articles/dark-mode-users-issues/
- **Sara Soueidan — Focus indicators**: https://www.sarasoueidan.com/blog/focus-indicators/
- **WCAG 2.2 — Focus Visible**: https://www.w3.org/WAI/WCAG22/Understanding/focus-visible.html

## 5. 의도적으로 제외 (다른 트랙)

- **자동 회귀** (Storybook + Chromatic / Playwright snapshot) — 픽셀 회귀, props 매트릭스
- **자동 a11y** (axe-core) — ARIA 속성, 색대비 수치 측정 → `a11y` perspective
- **성능** (Lighthouse) — LCP/CLS/INP → `perf` perspective
- **토큰 정적 분석** (ESLint/Stylelint) — 하드코딩 hex grep, 금지 클래스
- **콘텐츠 검수** (i18n key, 한글 맞춤법) — 별도 인적 검수

> **이 체크리스트의 역할**: 사람이 화면을 보고 위화감을 느끼는 지점 — 스크린샷 1장으로 판정 가능한 문제만.

## 6. 디자이너 관점 이슈 등록 가이드

이 perspective에서 발견한 이슈는 본문이 다음 형태:

```markdown
## 현상
[어떤 화면에서 어떤 디자인 문제 — 스크린샷 첨부]

## 영향
사용자가 무엇을 못하거나 잘못 이해하는가. 단순 미관이 아닌 인지/행동 영향까지.

## 비교 (선택)
다른 페이지/컴포넌트에서 같은 패턴을 어떻게 처리했는가. 일관성 문제면 비교 캡처.

## 수정 방향
디자인 토큰/spacing scale/typography scale 기준으로 어떻게 맞출지.

## 메타
- **컴포넌트**: `파일경로:라인번호`
- **발견**: YYYY-MM-DD (디자인 패스)
```

라벨: `bug,severity:ux,design` (ux 심각도 + design 라벨로 일반 버그와 구분 — pilot이 자율 처리 제외)

## 7. AI 운영 팁

- 한 페이지당 **라이트/다크 × 1440px(기본)/1280px(좁은 노트북) = 4장 캡처** 필수 (모바일 검증 X)
- 캡처 후 **그레이스케일 변환본**도 생성 (CSS filter)으로 시각 위계 검증
- 폼·다이얼로그는 **빈/오류/성공 3장 추가**
- `prefers-reduced-motion: reduce` 에뮬레이션으로 모션 별도 캡처
- 비교 보고는 항목별 PASS/WARN/FAIL + 캡처 thumbnail
