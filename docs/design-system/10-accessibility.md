# 10. 접근성 (Accessibility)

**목표 기준**: WCAG 2.2 AA

---

## A. 색상 대비 (Color Contrast)

| 텍스트 유형 | 최소 비율 | 현재 상태 |
|-------------|-----------|-----------|
| 일반 텍스트 (< 18px) | 4.5:1 | foreground oklch(0.145) on background oklch(1) ≈ 19:1 ✅ |
| 큰 텍스트 (≥ 18px bold) | 3:1 | ✅ |
| muted-foreground on background | 4.5:1 | oklch(0.556) on oklch(1) ≈ 5.3:1 ✅ |
| UI 컴포넌트 (테두리, 아이콘) | 3:1 | border oklch(0.922) on oklch(1) ≈ 1.2:1 ⚠️ (비필수 테두리로 허용 가능) |
| 비활성(Disabled) 상태 | N/A | 의도적으로 낮은 대비 |

---

## B. Focus Indicator (포커스 표시)

- **현재(As-Is)**: `focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]` (shadcn 기본값)
- Button, Input, Select, Checkbox, Radio 모두 `focus-visible` 스타일 적용됨
- Ring 색상: `--ring: oklch(0.708 0 0)` — 충분한 대비 확보

**권장(To-Be)**: 현재 shadcn 기본 설정을 유지하되, 커스텀 컴포넌트 작성 시 반드시 `focus-visible` 스타일을 포함해야 한다.

---

## C. ARIA 패턴 현황 (As-Is)

현재 앱 전체(shadcn primitive 제외)에서 약 25개의 ARIA 속성이 사용 중이다:

- `aria-label`: ChartTypeSelector, StepNode 버튼, EditorHeader, DatasetDataTab 체크박스 등 약 8개소
- `role="combobox"` + `aria-expanded`: PipelineChainForm, DatasetCombobox 등 약 4개소
- `role="button"`: DatasetCombobox 태그 제거 버튼
- `sr-only`: Dialog 닫기 버튼 ("Close"), Command dialog 헤더

---

## D. ARIA 부족 항목 (현재 문제점)

1. **아이콘 전용 버튼에 `aria-label` 누락**: `<Button variant="ghost" size="icon">`에 `aria-label`이 없는 경우 다수
   - 파이프라인 편집, 삭제, 즐겨찾기 버튼 등 해당
2. **데이터 테이블에 `aria-label` 누락**: `<Table>` 요소에 테이블 내용을 설명하는 `aria-label` 없음
3. **정렬 표시 없음**: 정렬 기능이 있는 테이블 컬럼에 `aria-sort` 속성 부재
4. **라이브 영역(Live Region) 없음**: 비동기 로딩/업데이트 알림을 위한 `aria-live` 미사용
5. **차트 접근성 없음**: 차트 시각화에 대한 텍스트 대안 없음
6. **Toast 알림 읽기 미확인**: Sonner toast가 스크린 리더에 올바르게 알려지는지 확인 필요 (`role="alert"` 검토 필요)

---

## E. 권장 ARIA 패턴 (To-Be)

```tsx
// 1. 아이콘 전용 버튼 — aria-label 필수
<Button variant="ghost" size="icon" aria-label="편집">
  <Pencil className="h-4 w-4" />
</Button>

// 2. 데이터 테이블 — 내용 설명
<Table aria-label="데이터셋 목록">
  <TableHead aria-sort="ascending">이름</TableHead>
  ...
</Table>

// 3. 상태 Badge — 색상에만 의존하지 않기
<Badge variant="success" aria-label="상태: 활성">
  <CheckCircle className="h-3 w-3" aria-hidden="true" />
  활성
</Badge>

// 4. 비동기 업데이트용 Live Region
<div aria-live="polite" aria-atomic="true">
  {isLoading ? "로딩 중..." : `${total}개 결과`}
</div>

// 5. 차트 — 텍스트 대안 제공
<div role="img" aria-label="월별 파이프라인 실행: 1월 45건, 2월 62건">
  <ChartContainer>...</ChartContainer>
</div>
```

---

## F. 키보드 내비게이션 (Keyboard Navigation)

| 패턴 | 키 | 기대 동작 |
|------|-----|-----------|
| Modal / Dialog | `Escape` | 닫기 |
| Dropdown | `Arrow Down / Up` | 옵션 탐색 |
| Dropdown | `Enter / Space` | 옵션 선택 |
| Data Table | `Tab` | 인터랙티브 셀 간 이동 |
| Tabs | `Arrow Left / Right` | 탭 전환 |
| Sidebar | `Cmd+B` / `Ctrl+B` | 확장/축소 토글 |
| Search | `/` | 검색 입력 포커스 (미구현) |
| Form | `Enter` | 제출 |
| Tooltip | Hover / Focus | 호버와 포커스 모두 표시 (Radix 처리) |

---

## G. 터치 타겟 (Touch Targets)

- **최소 크기**: 44×44px (WCAG 2.5.5)
- `size="icon"` 버튼: h-9 w-9 (36px) — 최소보다 약간 작으나 패딩이 히트 영역을 확장
- `size="icon-xs"` 버튼: h-6 w-6 (24px) — **최소 미달**; 비필수 동작에만 제한적으로 사용할 것
