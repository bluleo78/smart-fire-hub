# 11. 다크 모드 (Dark Mode)

---

## A. 인프라 (Infrastructure)

- **Theme provider**: `next-themes` 라이브러리
- **토글**: UI 토글 제공 (구체적인 위치는 별도 문서화 필요)
- **CSS**: `next-themes`가 root 요소에 `.dark` 클래스를 추가/제거
- **토큰 레이어**: 완비됨 — `index.css`에 30개 이상의 토큰이 light/dark 값 쌍으로 정의되어 있음

---

## B. Light ↔ Dark 토큰 매핑

| 토큰 | Light | Dark | 규칙 |
|------|-------|------|------|
| `--background` | oklch(1 0 0) #fff | oklch(0.145 0 0) ~#111 | 명도 반전 |
| `--foreground` | oklch(0.145 0 0) | oklch(0.985 0 0) | 반전 |
| `--card` | oklch(1 0 0) | oklch(0.205 0 0) | 배경보다 약간 밝게 |
| `--primary` | oklch(0.205 0 0) | oklch(0.922 0 0) | 반전 |
| `--secondary` | oklch(0.97 0 0) | oklch(0.269 0 0) | Muted는 muted 유지 |
| `--muted` | oklch(0.97 0 0) | oklch(0.269 0 0) | secondary와 동일 |
| `--muted-foreground` | oklch(0.556 0 0) | oklch(0.708 0 0) | 다크 배경에서 더 밝게 |
| `--accent` | oklch(0.97 0 0) | oklch(0.269 0 0) | secondary와 동일 |
| `--destructive` | oklch(0.577 0.245 27.325) | oklch(0.704 0.191 22.216) | 다크에서 더 밝고 채도 낮게 |
| `--border` | oklch(0.922 0 0) | oklch(1 0 0 / 10%) | 불투명도(opacity) 기반 |
| `--input` | oklch(0.922 0 0) | oklch(1 0 0 / 15%) | 불투명도 기반 |
| `--ring` | oklch(0.708 0 0) | oklch(0.556 0 0) | 반전 |

---

## C. 표면 고도 모델 (Surface Elevation Model)

다크 모드에서 고도(elevation)는 그림자 대신 더 밝은 표면으로 표현된다.

| 레벨 | 표면 | OKLch | 사용처 |
|------|------|-------|--------|
| 0 (Base) | 페이지 배경 | oklch(0.145 0 0) | Body |
| 1 (Card) | 카드, 패널 | oklch(0.205 0 0) | Card, Sidebar |
| 2 (Popover) | 드롭다운, 팝오버 | oklch(0.269 0 0) | Secondary, Muted, Accent |
| 3 (Tooltip) | 오버레이 | oklch(0.371 0 0) | (현재 토큰으로 미정의) |

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

1. **순수 검은색 사용 금지**: `#000000` 대신 `oklch(0.145 0 0)` (~#111) 사용 — 눈의 피로 감소
2. **불투명도 기반 테두리**: 다크 모드에서는 단색 회색 대신 `oklch(1 0 0 / 10%)` 사용
3. **다크에서 텍스트를 더 밝게**: muted-foreground는 oklch(0.556) → oklch(0.708)로 전환하여 가독성 확보
4. **시맨틱 토큰 사용**: 항상 `bg-background`, `text-foreground`, `border-border` 사용 — 색상 하드코딩 금지
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
