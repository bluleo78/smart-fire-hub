# Design Tokens — Smart Fire Hub

> **범위**: `apps/firehub-web` 프론트엔드 디자인 시스템의 토큰 정의, 현황 감사, 권장 방향을 다룬다.
> **기준 파일**: `apps/firehub-web/src/index.css`, shadcn/ui 컴포넌트 라이브러리

---

## 목차

1. [Color Tokens — 색상 토큰](#1-color-tokens--색상-토큰)
   - 1-1. Light Theme (`:root`)
   - 1-2. Dark Theme (`.dark`)
   - 1-3. 역할 계약 (Role Contract)
   - 1-4. Semantic Status Tokens
   - 1-5. Theme Variants (ocean / sunset)
   - 1-6. Domain & Accent Tokens
2. [Hard-coded Color Audit — 하드코딩 색상 감사](#2-hard-coded-color-audit--하드코딩-색상-감사)
3. [Border Radius Scale — 모서리 반경 스케일](#3-border-radius-scale--모서리-반경-스케일)
4. [Z-Index Scale — 레이어 순서 스케일](#4-z-index-scale--레이어-순서-스케일)
5. [Shadow Usage — 그림자 사용 패턴](#5-shadow-usage--그림자-사용-패턴)

---

## 1. Color Tokens — 색상 토큰

Smart Fire Hub의 색상 시스템은 CSS 커스텀 프로퍼티(CSS Custom Properties)로 정의되며, 모든 색상은 [OKLch](https://oklch.com/) 색공간을 사용한다. OKLch는 인지적으로 균일한(perceptually uniform) 색공간으로, 명도(L), 채도(C), 색상각(h) 세 축으로 색상을 표현한다.

> ### ⚠️ 토큰은 **완결된 색 함수**다 — `hsl()`로 감싸지 마라
>
> 이 프로젝트의 색 토큰 값은 `oklch(0.5 0 0)`처럼 **그 자체로 완결된 색 함수**다.
> shadcn 초기 세대가 쓰던 HSL **성분값** 규약(`--muted-foreground: 0 0% 45%`)이 **아니다**.
>
> ```css
> /* ❌ hsl(oklch(...)) → 무효 CSS. 브라우저가 선언을 통째로 버리고 initial 값으로 되돌린다.
>       fill → rgb(0,0,0), stroke → none, background/border → 소실 */
> fill: hsl(var(--muted-foreground));
>
> /* ✅ 래핑 없이 그대로 쓴다 */
> fill: var(--muted-foreground);
> ```
>
> `var(--X)`는 SVG **표현 속성**(`<line stroke="var(--border)">`)에서도 정상 해석되므로
> recharts/nivo 차트도 동일하게 쓰면 된다.
>
> 실제 사고(#374): 차트 13종 전역이 `hsl(var(--X))`를 써서 다크 모드 축 라벨 대비가
> **1.06~1.10:1**(SC 1.4.3 요구 4.5:1)까지 떨어졌고, `격자 표시` 토글이 라이트·다크 공통으로
> 무동작했다(격자 `stroke: none`). 재발은 `src/styles/hsl-var-gate.test.ts`가 막는다.

### 1-1. Light Theme (`:root`)

라이트 모드의 기본 색상 토큰이다. `:root` 선택자에 정의되며 기본값으로 적용된다.
아래 값은 `apps/firehub-web/src/index.css`의 현행 정의와 일치한다(#365·#367·#370 반영).

#### Core UI Tokens

| Token | OKLch 값 | 근사 색상 | 용도 |
|-------|----------|-----------|------|
| `--background` | `oklch(0.985 0.002 264)` | Near-white(살짝 푸른) | 페이지 전체 배경 |
| `--foreground` | `oklch(0.145 0 0)` | Near-black | 기본 본문 텍스트 |
| `--card` | `oklch(1 0 0)` | 흰색 | 카드 컴포넌트 배경 |
| `--card-foreground` | `oklch(0.145 0 0)` | Near-black | 카드 내 텍스트 |
| `--popover` | `oklch(1 0 0)` | 흰색 | 팝오버, 드롭다운 배경 |
| `--popover-foreground` | `oklch(0.145 0 0)` | Near-black | 팝오버 내 텍스트 |

#### Brand & Interaction Tokens

| Token | OKLch 값 | 근사 색상 | 용도 |
|-------|----------|-----------|------|
| `--primary` | `oklch(0.45 0.2 264)` | 인디고 | 기본 브랜드 색상, CTA 버튼 배경 |
| `--primary-foreground` | `oklch(0.985 0 0)` | Near-white | Primary 위 텍스트 (대비 7.54:1) |
| `--secondary` | `oklch(0.965 0.005 264)` | 연한 회색 | 보조 버튼, 보조 배경 |
| `--secondary-foreground` | `oklch(0.25 0 0)` | 어두운 회색 | Secondary 위 텍스트 |
| `--muted` | `oklch(0.965 0.005 264)` | 연한 회색 | 음소거 배경, 비활성 영역 |
| `--muted-foreground` | `oklch(0.5 0 0)` | 중간 회색 | 플레이스홀더, 부가 설명 텍스트 |
| `--accent` | `oklch(0.955 0.01 264)` | 연한 회색 | hover·active 상태 배경 |
| `--accent-foreground` | `oklch(0.25 0 0)` | 어두운 회색 | Accent 위 텍스트 |
| `--destructive` | `oklch(0.53 0.22 27.325)` | 빨간색 | 위험·삭제 액션, 에러 상태 |
| `--destructive-foreground` | `oklch(1 0 0)` | 흰색 | Destructive 배경 위 텍스트 (5.83:1) |

#### Structural Tokens

| Token | OKLch 값 | 근사 색상 | 용도 |
|-------|----------|-----------|------|
| `--border` | `oklch(0.94 0.005 264)` | 연한 회색 | 일반 테두리 |
| `--input` | `oklch(0.92 0.005 264)` | 연한 회색 | Input 컴포넌트 테두리 |
| `--ring` | `oklch(0.55 0.15 264)` | 인디고 | 키보드 Focus ring (배경 대비 4.76~4.98:1) |
| `--radius` | `0.625rem` | — | 모서리 반경 기준값 |

#### Chart Tokens

| Token | OKLch 값 | 근사 색상 | 용도 |
|-------|----------|-----------|------|
| `--chart-1` | `oklch(0.45 0.2 264)` | 인디고 | 차트 데이터 시리즈 1 (primary와 동일) |
| `--chart-2` | `oklch(0.55 0.15 195)` | 시안/틸 | 차트 데이터 시리즈 2 |
| `--chart-3` | `oklch(0.5 0.18 300)` | 퍼플 | 차트 데이터 시리즈 3 |
| `--chart-4` | `oklch(0.7 0.15 84)` | 앰버 | 차트 데이터 시리즈 4 |
| `--chart-5` | `oklch(0.6 0.2 27)` | 레드 | 차트 데이터 시리즈 5 |

#### Sidebar Tokens

| Token | OKLch 값 | 근사 색상 | 용도 |
|-------|----------|-----------|------|
| `--sidebar` | `oklch(1 0 0)` | 흰색 | 사이드바 배경 |
| `--sidebar-foreground` | `oklch(0.145 0 0)` | Near-black | 사이드바 텍스트 |
| `--sidebar-primary` | `oklch(0.45 0.2 264)` | 인디고 | 사이드바 선택 항목 배경 |
| `--sidebar-primary-foreground` | `oklch(0.985 0 0)` | Near-white | 사이드바 선택 항목 텍스트 |
| `--sidebar-accent` | `oklch(0.955 0.015 264)` | 연한 회색 | 사이드바 hover 배경 |
| `--sidebar-accent-foreground` | `oklch(0.25 0 0)` | 어두운 회색 | 사이드바 hover 텍스트 |
| `--sidebar-border` | `oklch(0.94 0.005 264)` | 연한 회색 | 사이드바 테두리 |
| `--sidebar-ring` | `oklch(0.55 0.15 264)` | 인디고 | 사이드바 focus ring |

---

### 1-2. Dark Theme (`.dark`)

`.dark` 클래스가 `<html>`에 적용될 때 오버라이드되는 토큰이다.
다크의 표면 토큰(`--card`·`--secondary`·`--muted`·`--accent`·`--border`·`--input`)은 **흰색 알파 합성**으로 정의되어, 어떤 테마 배경 위에서도 같은 위계를 만든다.

#### Core UI Tokens (Dark)

| Token | OKLch 값 | 비고 |
|-------|----------|------|
| `--background` | `oklch(0.13 0.015 280)` | Near-black(보라 기미) |
| `--foreground` | `oklch(0.93 0 0)` | Near-white |
| `--card` | `oklch(1 0 0 / 3%)` | 배경 위 흰색 3% — 실색은 테마마다 다르다 |
| `--card-foreground` | `oklch(0.93 0 0)` | Near-white |
| `--popover` | `oklch(0.18 0.015 280)` | 불투명(팝오버는 뒤가 비치면 안 된다) |
| `--popover-foreground` | `oklch(0.93 0 0)` | Near-white |

#### Brand & Interaction Tokens (Dark)

| Token | OKLch 값 | 비고 |
|-------|----------|------|
| `--primary` | `oklch(0.65 0.2 264)` | 밝은 인디고 — 다크의 브랜드 정체성 |
| `--primary-foreground` | `oklch(0.17 0.02 280)` | **근검정.** 밝은 액센트 위에 얹는 색 (#365, 5.64:1) |
| `--secondary` | `oklch(1 0 0 / 5%)` | 흰색 5% |
| `--secondary-foreground` | `oklch(0.93 0 0)` | Near-white |
| `--muted` | `oklch(1 0 0 / 5%)` | 흰색 5% |
| `--muted-foreground` | `oklch(0.6 0 0)` | 중간 회색 (배경 대비 5.09:1) |
| `--accent` | `oklch(1 0 0 / 7%)` | 흰색 7% |
| `--accent-foreground` | `oklch(0.93 0 0)` | Near-white |
| `--destructive` | `oklch(0.704 0.191 22.216)` | 밝은 빨강 |
| `--destructive-foreground` | `oklch(0.17 0.02 280)` | 근검정 (#367, 6.64:1) |

#### Structural Tokens (Dark)

| Token | OKLch 값 | 비고 |
|-------|----------|------|
| `--border` | `oklch(1 0 0 / 10%)` | 흰색 10% 알파 |
| `--input` | `oklch(1 0 0 / 12%)` | 흰색 12% 알파 |
| `--ring` | `oklch(0.65 0.2 264)` | `--primary`와 동일 값 (배경 대비 5.54~5.92:1) |

#### Chart Tokens (Dark)

| Token | OKLch 값 | 근사 색상 |
|-------|----------|-----------|
| `--chart-1` | `oklch(0.65 0.2 264)` | 인디고 (다크 primary와 동일) |
| `--chart-2` | `oklch(0.7 0.15 195)` | 시안 |
| `--chart-3` | `oklch(0.65 0.2 300)` | 퍼플 |
| `--chart-4` | `oklch(0.75 0.15 84)` | 앰버 |
| `--chart-5` | `oklch(0.7 0.2 27)` | 레드 |

> 라이트/다크 모두 `--chart-1`은 그 테마의 `--primary`와 같은 값이다. 테마 변형(ocean/sunset)도 `--chart-1`을 함께 덮어써 차트 1번 시리즈가 브랜드 색을 따라간다.

#### Sidebar Tokens (Dark)

| Token | OKLch 값 | 비고 |
|-------|----------|------|
| `--sidebar` | `oklch(0.14 0.02 280)` | 배경보다 살짝 밝은 면 |
| `--sidebar-foreground` | `oklch(0.93 0 0)` | Near-white |
| `--sidebar-primary` | `oklch(0.65 0.2 264)` | 밝은 인디고 |
| `--sidebar-primary-foreground` | `oklch(0.17 0.02 280)` | 근검정 (#365) |
| `--sidebar-accent` | `oklch(1 0 0 / 7%)` | 흰색 7% |
| `--sidebar-accent-foreground` | `oklch(0.93 0 0)` | Near-white |
| `--sidebar-border` | `oklch(1 0 0 / 10%)` | 흰색 10% 알파 |
| `--sidebar-ring` | `oklch(0.65 0.2 264)` | 밝은 인디고 |

---

### 1-3. 역할 계약 (Role Contract) — 토큰을 쓰기 전에 반드시 읽을 것

색 토큰은 이름마다 **쓸 수 있는 자리가 정해져 있다.** 이 계약을 어기면 대비가 계산 불가능해지거나(알파) 전경/배경이 뒤바뀌어 WCAG AA를 깬다. #365·#366·#367·#368·#370이 전부 이 계약의 부재에서 나왔다.

```
--X              : 배경(도트·바·보더) 겸 "그 위에 --X-foreground를 얹는" 색.
                   동시에 text-X 유틸의 전경으로도 쓰이므로,
                   → 흰 카드 및 X/10 · X-subtle 틴트 위에서 4.5:1을 만족해야 한다.
--X-foreground   : --X를 배경으로 깔았을 때 그 위에 올리는 색. 4.5:1.
--X-subtle       : 배경 전용. 절대 전경으로 쓰지 않는다.
--input          : 폼 컨트롤 경계 전용. 장식 구분선(--border)과 계약이 다르다.
                   → 컨트롤이 앉는 모든 표면(배경·카드·팝오버) 위에서 합성 후 3:1.
알파 수식자      : text-* / ring-* / outline-* 에 금지.
                   bg-* / border-* 에는 허용(장식·틴트 용도).
```

**결과적으로 지켜야 하는 것들**

- `--X-subtle`을 `text-*`에 쓰지 않는다. 배경 전용이다.
- `text-muted-foreground/70` 처럼 전경에 알파를 붙이지 않는다. 위계는 **폰트 크기·굵기**로 표현한다.
- 포커스 링에 알파를 붙이지 않는다(`ring-ring/50` ✗ → `ring-ring` ✓). `--ring` 원색은 6개 테마 조합 전부 3:1을 넉넉히 넘는다.
- 라이트에서 `--X`는 "밝은 배경색"이 아니라 "흰 배경 위에서 읽히는 색"이다. 밝은 앰버를 `--warning`에 넣으면 도트가 1.66:1로 SC 1.4.11조차 못 넘긴다.
- **`text-X` 전경 아래 깔리는 `bg-X` 틴트는 10%를 넘지 않는다 (#372).** 계약이 4.5:1을 보장하는 범위가 `X/10`까지다. 틴트가 진해질수록 전경과 배경이 같은 색으로 수렴한다 — `bg-primary/30 text-primary`는 light/ocean에서 3.68:1이다. 상태 위계는 배경 농도가 아니라 **보더 농도·글로우**로 표현한다. 전경이 텍스트가 아니라 아이콘이면 SC 1.4.11(3:1)이 적용되므로 예외이며, 테스트 allowlist에 사유와 함께 적는다.
- **컨트롤 경계 보더는 합성 후 3:1 (#376).** `input`/`textarea`/`select`의 배경은 표면과 1.00~1.06:1로 사실상 같으므로, 보더가 "여기가 입력란이다"를 알리는 **유일한 시각 정보**다 → SC 1.4.11 적용 대상. `--input`을 `--border`와 같은 "옅은 구분선" 명도로 잡으면 안 된다(라이트 0.92 = 1.21:1이었다). 알파를 쓸지 여부가 기준이 아니라 **표면 위에 합성한 뒤의 대비값**이 기준이며, 다크는 표면마다 합성 결과가 달라지므로 불투명 값으로 고정한다. `--input`은 `bg-input`(스위치 트랙)·`dark:bg-input/30`(컨트롤 배경)도 함께 움직이므로 아웃라인 버튼·스위치의 인상이 같이 바뀐다 — 의도된 것이다.
- **예외(문서화된 것)**: `aria-invalid:ring-destructive/20` 계열. 포커스 표시가 아니라 오류 상태 보조 표시이고, 같은 요소의 `aria-invalid:border-destructive`가 3:1을 담당한다.

**자동 검증**

| 검사 | 위치 |
|---|---|
| 토큰 값 대비(라이트·다크 × indigo/ocean/sunset 6조합) | `apps/firehub-web/src/styles/design-tokens.contrast.test.ts` |
| 알파 수식자 금지 게이트(allowlist 포함) | `apps/firehub-web/src/styles/alpha-utility-gate.test.ts` |
| 포커스 링 실렌더 불투명도 + 테마 모드 버튼 a11y | `apps/firehub-web/e2e/pages/a11y-focus-theme-controls.spec.ts` |

> 과거 이 문서는 "채도 0의 의도적 무채색 팔레트"를 기술했으나, 현재 팔레트는 인디고 브랜드 기반의 **유채색**이며 테마 변형(ocean/sunset)까지 갖고 있다. 무채색 서술은 폐기됐다.

---

### 1-4. Semantic Status Tokens

`index.css`에 Light/Dark 모두 정의되어 있다. 값은 1-3의 역할 계약을 만족하도록 잡혀 있다.

#### 토큰 정의 (현행)

```css
:root {
  /* Success */
  --success: oklch(0.46 0.16 149.5);
  --success-foreground: oklch(0.985 0 0);
  --success-subtle: oklch(0.95 0.05 149.5);

  /* Warning */
  --warning: oklch(0.52 0.13 84);
  --warning-foreground: oklch(0.985 0 0);
  --warning-subtle: oklch(0.97 0.04 84);

  /* Info */
  --info: oklch(0.5 0.15 240);
  --info-foreground: oklch(0.985 0 0);
  --info-subtle: oklch(0.95 0.04 240);

  /* Caution — warning(amber)보다 강한 주의 */
  --caution: oklch(0.52 0.15 55);
  --caution-foreground: oklch(0.985 0 0);
  --caution-subtle: oklch(0.96 0.04 55);
}

.dark {
  /* 다크는 밝은 액센트가 정체성이므로 배경 계열 값은 유지하고 전경만 근검정으로 뒤집는다 */
  --success: oklch(0.65 0.15 149.5);
  --success-foreground: oklch(0.17 0.02 280);
  --success-subtle: oklch(0.2 0.04 149.5);

  --warning: oklch(0.76 0.14 84);
  --warning-foreground: oklch(0.17 0.02 280);
  --warning-subtle: oklch(0.2 0.04 84);

  --info: oklch(0.7 0.13 240);
  --info-foreground: oklch(0.17 0.02 280);
  --info-subtle: oklch(0.2 0.04 240);

  --caution: oklch(0.78 0.13 55);
  --caution-foreground: oklch(0.17 0.02 280);
  --caution-subtle: oklch(0.2 0.04 55);
}
```

#### 라이트 테마 대비 (계산값)

| 토큰 | hex | 흰 카드 위 텍스트 | `bg-X/10` 틴트 위 | `X-subtle` 위 | 도트/바 (1.4.11) | `--X-foreground` 위 |
|---|---|---|---|---|---|---|
| `--warning` | `#8c6000` | 5.54 | 4.84 | 5.06 | 5.54 | 5.31 |
| `--success` | `#006e1c` | 6.47 | 5.58 | 5.70 | 6.47 | 6.20 |
| `--destructive` | `#cd000f` | 5.83 | 4.87 | — | 5.83 | 5.58 |
| `--info` | `#006aaf` | 5.71 | 4.94 | 4.93 | 5.71 | 5.47 |
| `--caution` | `#a74a00` | 5.80 | 5.02 | 5.01 | 5.80 | 5.55 |

#### 토큰 사용 패턴

| 토큰 패턴 | 역할 | 사용 예 |
|-----------|------|---------|
| `--{status}` | 상태 색상 — 아이콘·텍스트·도트·바·보더 | `text-success`, `bg-warning`(도트) |
| `--{status}-foreground` | `bg-{status}` 배경 위 텍스트 | `bg-destructive text-destructive-foreground` |
| `--{status}-subtle` | 연한 상태 배경 (알림 박스, 배지) | `bg-success-subtle text-success` |

#### Tailwind CSS v4 매핑

`@theme inline` 블록에서 CSS 변수를 유틸리티 색으로 노출한다. **`-foreground` 짝을 빠뜨리면 해당 `text-*-foreground` 유틸이 무효값이 되어 `--foreground`를 상속한다**(#367의 근본 원인).

```css
@theme inline {
  --color-destructive: var(--destructive);
  --color-destructive-foreground: var(--destructive-foreground);
  --color-success: var(--success);
  --color-success-foreground: var(--success-foreground);
  --color-success-subtle: var(--success-subtle);
  /* warning / info / caution 도 동일한 3종 세트 */
}
```

---

### 1-5. Theme Variants — 테마 변형 (ocean / sunset)

사용자가 사이드바 사용자 메뉴에서 고르는 색 테마. `<html>`에 `theme-ocean` / `theme-sunset` 클래스가 붙는다(기본 indigo는 클래스 없음 — `:root`/`.dark`의 기본값이 곧 indigo다).

변형 블록은 `--primary` · `--ring` · `--sidebar-primary` · `--sidebar-ring` · `--chart-1` · `--accent` 계열만 덮어쓴다.

| 조합 | `--primary` | hex | `--primary-foreground` 대비 | `bg-primary/10` 틴트 위 `text-primary` |
|---|---|---|---|---|
| indigo 라이트 | `oklch(0.45 0.2 264)` | `#3730c4` | 7.54 | 6.36 |
| ocean 라이트 | `oklch(0.46 0.14 195)` | `#006e70` | 5.81 | 5.02 |
| sunset 라이트 | `oklch(0.5 0.18 50)` | `#ae3400` | 6.11 | 5.21 |
| indigo 다크 | `oklch(0.65 0.2 264)` | `#4e86ff` | 5.64 | 5.39 |
| ocean 다크 | `oklch(0.75 0.12 195)` | `#2cc5c5` | 9.04 | 8.47 |
| sunset 다크 | `oklch(0.72 0.15 50)` | `#ee8545` | 7.38 | 7.05 |

> **중요**: 변형 블록은 `--primary-foreground`를 **재정의하지 않는다.** `.dark`의 값을 상속하므로, 다크 전경을 한 줄 고치면 세 테마가 모두 따라온다. 반대로 변형 블록에 전경을 따로 넣으면 이 상속이 끊어지니 넣지 말 것.
>
> **`--primary`도 §1-3 역할 계약을 진다 (#372).** 라이트 변형의 `--primary`는 원래 "버튼 배경으로 예뻐 보이는 밝기"로 잡혀 있었는데(ocean 0.52 / sunset 0.55), 같은 토큰이 아바타 이니셜·AI 상태 칩·`원본` 배지에서 `bg-primary/10` 틴트 위 **전경**으로 재사용되면서 3.75~4.29:1로 AA에 미달했다. hue/chroma는 유지하고 명도만 0.46 / 0.50으로 내려 해결했다. `--ring`·`--sidebar-primary`·`--sidebar-ring`·`--chart-1`도 같은 값을 공유하므로 **반드시 함께** 움직인다.
>
> 마지막 열은 브라우저 실측값이다(페이지 배경 위에 얹힌 틴트). 흰색(`1.0`) 기준으로 계산하면 실측보다 0.15가량 낙관적으로 나오니, 흰 카드만으로 검증하지 말 것.
>
> `--primary`의 L을 올리는 변경은 라이트 틴트 열(5.02가 가장 빠듯하다)을 먼저 확인하라.

---


### 1-6. Domain & Accent Tokens

도메인 엔티티와 AI 관련 기능의 시각적 구분을 위한 시맨틱 토큰.

#### Domain Tokens

| Token | Light 값 | Dark 값 | 용도 |
|-------|----------|---------|------|
| `--pipeline` | `oklch(0.52 0.14 195)` | `oklch(0.75 0.12 195)` | 파이프라인 엔티티 색상 |
| `--pipeline-foreground` | `oklch(0.985 0 0)` | `oklch(0.17 0.02 280)` | Pipeline 위 텍스트 |
| `--dataset` | `oklch(0.45 0.2 264)` | `oklch(0.7 0.17 264)` | 데이터셋 엔티티 색상 |
| `--dataset-foreground` | `oklch(0.985 0 0)` | `oklch(0.17 0.02 280)` | Dataset 위 텍스트 |
| `--dashboard-accent` | `oklch(0.48 0.2 300)` | `oklch(0.7 0.18 300)` | 대시보드 강조 색상 |
| `--dashboard-accent-foreground` | `oklch(0.985 0 0)` | `oklch(0.17 0.02 280)` | Dashboard accent 위 텍스트 |

#### AI Accent Tokens

AI 관련 UI 요소(AI 분류 스텝, AI 상태 칩, AI 패널 등)에 사용하는 보라색 계열 토큰.

| Token | Light 값 | Dark 값 | 용도 |
|-------|----------|---------|------|
| `--ai-accent` | `oklch(0.52 0.18 293)` | `oklch(0.72 0.15 293)` | AI 기능 강조 색상 (보라색) |
| `--ai-accent-foreground` | `oklch(0.985 0 0)` | `oklch(0.17 0.02 280)` | AI accent 위 텍스트 |
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
| `--caution` | `oklch(0.52 0.15 55)` | `oklch(0.78 0.13 55)` | 주의 색상 (오렌지) |
| `--caution-foreground` | `oklch(0.985 0 0)` | `oklch(0.17 0.02 280)` | Caution 위 텍스트 |
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
