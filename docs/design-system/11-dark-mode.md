# 11. 다크 모드 (Dark Mode)

---

## A. 인프라 (Infrastructure)

- **Theme provider**: `next-themes` 라이브러리
- **토글**: UI 토글 제공 (구체적인 위치는 별도 문서화 필요)
- **CSS**: `next-themes`가 root 요소에 `.dark` 클래스를 추가/제거
- **토큰 레이어**: 완비됨 — `index.css`에 30개 이상의 토큰이 light/dark 값 쌍으로 정의되어 있음

---

## B. Light ↔ Dark 토큰 매핑

값은 `apps/firehub-web/src/index.css` 현행 정의(indigo 기본 테마)다. 전체 표는 [01-design-tokens.md](./01-design-tokens.md) §1-1·§1-2 참조.

| 토큰 | Light | Dark | 규칙 |
|------|-------|------|------|
| `--background` | oklch(0.975 0.003 264) | oklch(0.13 0.015 280) | 명도 반전 + 한색(h 264→280). 라이트는 흰 카드가 떠 보이게 카드보다 한 단계 어둡다 (WD-755) |
| `--foreground` | oklch(0.145 0 0) | oklch(0.93 0 0) | 반전 (순백 아님) |
| `--card` | oklch(1 0 0) | oklch(1 0 0 / 4.5%) | 솔리드 → 흰색 알파 오버레이 (ocean·sunset 다크는 브랜드 색 4.5%, WD-755) |
| `--primary` | oklch(0.45 0.2 264) | oklch(0.65 0.2 264) | 인디고 — 다크에서 더 밝게(L↑) |
| `--secondary` | oklch(0.958 0.006 264) | oklch(1 0 0 / 5%) | 솔리드 → 흰색 5% 알파 |
| `--muted` | oklch(0.958 0.006 264) | oklch(1 0 0 / 6.5%) | 라이트는 secondary와 같고, 다크는 secondary보다 한 단계 진하다 — 카드 위 머리 띠가 구분되게 (WD-755) |
| `--muted-foreground` | oklch(0.5 0 0) | **oklch(0.7 0 0)** (이전 oklch(0.6 0 0)) | 다크 배경에서 더 밝게 — 가장 밝은 표면(두 겹 카드·머리 띠 위 hover)에서도 4.5:1 |
| `--accent` | oklch(0.93 0.01 264) | oklch(1 0 0 / 9%) | 솔리드 → 흰색 알파. 양쪽 모두 머리 띠(`--muted`)보다 한 단계 위 (WD-755) |
| `--destructive` | oklch(0.53 0.22 27.325) | oklch(0.704 0.191 22.216) | 다크에서 더 밝고 채도 낮게 — 그래서 다크 `--destructive-foreground`는 근검정 (#367) |
| `--divider` | oklch(0.93 0.005 264) | oklch(1 0 0 / 9%) | 솔리드 → 흰색 9% 알파 — 행 구분선, 외곽보다 옅게 (WD-755) |
| `--border` | oklch(0.88 0.006 264) | oklch(1 0 0 / 16%) | 솔리드 → 흰색 16% 알파 — 덩어리 외곽선 (WD-755) |
| `--input` | oklch(0.64 0.005 264) | oklch(0.52 0.01 280) | **양쪽 모두 불투명** — 알파는 표면마다 합성 결과가 달라져 3:1 을 보장할 수 없다 (#376) |
| `--ring` | oklch(0.55 0.15 264) | oklch(0.65 0.2 264) | 인디고 — 다크에서 더 밝게 |

---

## C. 표면 고도 모델 (Surface Elevation Model)

다크 모드에서 고도(elevation)는 그림자 대신 더 밝은 표면으로 표현된다. 표면은 솔리드 회색이 아니라 **흰색 알파 오버레이**(`oklch(1 0 0 / N%)`)라서, 알파가 쌓일수록 밝아진다(테마 배경이 달라도 같은 위계).

| 레벨 | 표면 | OKLch | 토큰 |
|------|------|-------|------|
| 0 (Base) | 페이지 배경 | oklch(0.13 0.015 280) | `--background` |
| 0' (Sidebar) | 사이드바 베이스 | oklch(0.14 0.02 280) | `--sidebar` |
| 1 (Card) | 카드·패널 | oklch(1 0 0 / 4.5%) | `--card` |
| 2 (Secondary/Muted) | 보조 표면·카드 안 머리 띠 | oklch(1 0 0 / 5~6.5%) | `--secondary`(5%), `--muted`(6.5%) |
| 3 (Accent) | hover·누름 표면 | oklch(1 0 0 / 7~9%) | `--sidebar-accent`(7%), `--accent`(9% — 머리 띠보다 한 단계 위, WD-755) |
| Popover | 드롭다운·팝오버 | oklch(0.18 0.015 280) | `--popover` (솔리드, 예외) |

> `--popover`만 알파가 아닌 솔리드다. 팝오버는 아래 콘텐츠가 비치면 안 되므로 의도적으로 불투명하게 둔다.
>
> 테두리도 같은 알파 누적 모델을 따른다 — 행 구분선(`--divider` 9%) < 외곽(`--border` 16%). 입력 경계 `--input`만은 불투명 `oklch(0.52 0.01 280)`으로 고정한다(#376).

### C-1. 영역 경계 단계 (WD-755)

배경 3단(페이지 `background` < 카드 `card` < 머리 띠 `muted`, 그 위 hover 면 `accent`)·테두리 3단(행 구분선 `divider` < 외곽 `border` < 입력 `input`)의 대비 기준은 [01-design-tokens.md](./01-design-tokens.md) §1-7, 값은 §1-1·§1-2 가 기준 문서다. 다크에서 특히 지킬 것:

- **겹쳐서 잰다.** 반투명 토큰의 대비는 바탕 → 카드 → 토큰 순으로 실제 합성한 색으로 잰다. 카드 위 머리 띠는 바탕 위에 4.5%, 그 위에 6.5%가 겹친 색이다. 카드 안에 카드가 한 겹 더 깔리는 곳(패널 속 카드)은 두 겹을 합성한다.
- **가장 밝은 표면 위 보조 글자.** 표면·hover 를 진하게 하면 그 위 `text-muted-foreground`의 대비가 준다. 다크 `--muted-foreground`는 두 겹 카드 위 머리 띠·hover, 카드 위 머리 띠 위 hover 에서 12px 글자가 4.5:1 이상이 되게 잡는다.
- **hover 면은 머리 띠보다 한 단계 위.** `--accent`를 `--muted`보다 확실히 높은 알파로 둬, 머리 띠 안 버튼·메뉴 hover 와 띠 위 선택 항목이 띠와 갈린다(띠 위에 겹친 실색으로 1.04:1 이상).
- **ocean·sunset 다크**는 `--card`·`--accent`를 브랜드 색 알파로 덮어쓰므로, 6조합 모두 같은 기준을 `design-tokens.contrast.test.ts` 「경계 단계」가 검사한다.
- **구조 경계에 알파 수식자를 붙이지 않는다.** 다크 토큰은 이미 알파라서 `border-border/50`은 외곽선 알파의 절반 — 행 구분선보다도 옅어져 단계가 뒤집힌다. 외곽선은 `border`, 행 사이는 `divide-y`·`row-divider`(기본 색 `--divider`), 영역 구분 면은 단색 `bg-muted`. hover 도 단색으로 준다(이유는 01 §1-7 「알파 규칙」). 재발은 `boundary-alpha-gate.test.ts`(흐린 경계 금지 게이트)가 막는다.

---

## D. 현재 구현 상태 (As-Is)

- **shadcn/ui primitive**: ✅ CSS 변수를 사용하여 다크 모드 완전 지원
- **CSS 토큰**: ✅ light/dark 쌍 완비
- **애플리케이션 페이지**: ⚠️ 명시적인 다크 모드 처리가 매우 제한적
  - 페이지 레벨에서 `dark:` 유틸리티 사용 사례:
    - `DatasetMapTab.tsx:66`: `dark:border-yellow-800 dark:bg-yellow-950/30`
    - `SqlQueryEditor.tsx:172`: `dark:bg-green-950/30 dark:text-green-400`
    - `MessageBubble.tsx:108,113`: `dark:text-green-400`, `dark:text-yellow-400`
  - 그 외 모든 페이지는 CSS 변수 자동 전환에 의존

---

## E. 다크 모드 문제점 (현재 문제점)

1. **하드코딩된 색상이 다크 모드에서 깨짐**: `bg-green-100`, `bg-red-100`, `bg-amber-50` 등 50개 이상의 인스턴스가 다크 모드에서 대비/가시성 저하
   - `bg-green-100 text-green-800` → 다크 배경에서 거의 보이지 않음
   - `bg-amber-50` → 다크 배경에서 지나치게 밝음
2. **Gray 색상 충돌**: 파이프라인 컴포넌트의 `text-gray-400`, `bg-gray-900` 등이 다크 모드 토큰과 충돌할 수 있음
3. **그림자(Shadow) 효과 없음**: `shadow-sm`, `shadow-lg` 등은 다크 배경에서 보이지 않음 — 그림자 대신 밝은 표면(고도 모델)을 사용해야 함
4. **차트 색상**: 다크 모드용 팔레트(파란색-보라색 계열)가 이미 정의되어 있으나, 차트 배경 조정이 필요할 수 있음

---

## F. 설계 규칙 (Design Rules)

1. **순수 검은색 사용 금지**: `#000000` 대신 한색을 띤 `oklch(0.13 0.015 280)` 사용 — 눈의 피로 감소
2. **불투명도 기반 테두리**: 다크 모드의 외곽선·행 구분선은 단색 회색 대신 흰색 알파(`--divider` < `--border`)를 쓴다. 단, 입력 경계 `--input`은 합성 후 3:1 을 보장하려고 불투명이다(#376)
3. **다크에서 텍스트를 더 밝게**: muted-foreground는 라이트보다 밝게 올려, 가장 밝은 표면(두 겹 카드·머리 띠 위 hover)에서도 4.5:1 을 지킨다(값은 01 §1-2)
4. **시맨틱 토큰 사용**: 항상 `bg-background`, `text-foreground`, `border-border` 사용 — 색상 하드코딩 금지. 구조 경계(외곽선·행 구분선·영역 구분 면)에는 알파 수식자(`border-border/50`·`divide-border/30`·`bg-muted/30`)도 붙이지 않는다 — §C-1
5. **상태 색상에 다크 변형 포함**: `--success`, `--warning`, `--info` 등을 정의할 때 반드시 다크 모드 값도 함께 정의

---

## G. index.css 정리 필요 사항

`index.css` 119-120행과 123-124행에 완전히 동일한 규칙이 중복 정의되어 있다. Phase D-2에서 정리가 필요하다.

```css
/* 중복 — 하나만 남기고 제거 필요 */
* { @apply border-border outline-ring/50; }
* { @apply border-border outline-ring/50; }

/* 중복 — 하나만 남기고 제거 필요 */
body { @apply bg-background text-foreground; }
body { @apply bg-background text-foreground; }
```
