# 04. 컴포넌트 사용 가이드

Smart Fire Hub 프론트엔드(`apps/firehub-web`)에서 사용하는 UI 컴포넌트의 목록, 스펙, 사용 규칙을 정의한다.

---

## A. shadcn/ui Primitives

`src/components/ui/` 아래 24개 파일로 구성된 기반 컴포넌트 라이브러리다.
모든 컴포넌트는 Radix UI 프리미티브를 기반으로 하며, Tailwind CSS v4로 스타일링된다.

| Component | File | Variants / Sizes | Project Default | Notes |
|-----------|------|------------------|-----------------|-------|
| AlertDialog | `alert-dialog.tsx` | size: `sm`, `default` | `default` | 파괴적 작업 확인 전용 모달 |
| Avatar | `avatar.tsx` | — | — | fallback initials + status badge 조합 사용 |
| Badge | `badge.tsx` | `default`, `secondary`, `destructive`, `outline` | `secondary` | **확장 필요: `success`, `warning`, `info` variant** |
| Button | `button.tsx` | variants: `default`, `destructive`, `outline`, `secondary`, `ghost`, `link` / sizes: `xs`, `sm`, `default`, `lg`, `icon`, `icon-xs`, `icon-sm`, `icon-lg` | `variant=default size=default` | 프로젝트 주요 인터랙션 요소 |
| Card | `card.tsx` | — | `p-6` padding | Header / Title / Description / Action / Content / Footer 서브 컴포넌트 포함 |
| Checkbox | `checkbox.tsx` | — | — | 폼 입력 |
| Collapsible | `collapsible.tsx` | — | — | 사이드바 내비게이션 섹션 접기/펼치기 |
| Command | `command.tsx` | — | — | 커맨드 팔레트 (cmdk 기반) |
| Dialog | `dialog.tsx` | — | `sm:max-w-lg` | 모달 다이얼로그 |
| DropdownMenu | `dropdown-menu.tsx` | — | — | 컨텍스트 메뉴, UserNav |
| Input | `input.tsx` | — | `h-9` | 텍스트 입력, `aria-invalid` 지원 |
| Label | `label.tsx` | — | — | 폼 레이블 |
| Popover | `popover.tsx` | — | `w-72` | 태그/상태 인라인 편집기 |
| RadioGroup | `radio-group.tsx` | — | — | 폼 라디오 버튼 |
| ScrollArea | `scroll-area.tsx` | — | — | 스크롤 가능한 컨테이너 |
| Select | `select.tsx` | — | — | 드롭다운 선택 |
| Separator | `separator.tsx` | `horizontal`, `vertical` | `horizontal` | 구분선 |
| Skeleton | `skeleton.tsx` | — | — | 로딩 플레이스홀더 |
| Sonner | `sonner.tsx` | — | — | Toast 알림 컨테이너 |
| Switch | `switch.tsx` | — | — | 토글 스위치 |
| Table | `table.tsx` | — | — | Table / Header / Body / Row / Head / Cell / Caption 서브 컴포넌트 포함 |
| Tabs | `tabs.tsx` | — | — | 탭 내비게이션 |
| Textarea | `textarea.tsx` | — | — | 멀티라인 텍스트 입력 |
| Tooltip | `tooltip.tsx` | — | — | 호버 툴팁 (접힌 사이드바 아이콘에 필수) |

---

## B. Custom Components

`src/components/common/` 아래 6개의 프로젝트 전용 컴포넌트다.
반복 패턴을 추상화하여 일관성을 확보한다.

---

### 1. FormField (`form-field.tsx`)

**목적**: 레이블 + 자식 입력 요소 + 에러 메시지를 하나의 블록으로 묶는 래퍼.

**Props API**:

| Prop | Type | Required | Description |
|------|------|----------|-------------|
| `label` | `string` | 필수 | 레이블 텍스트 |
| `error` | `string` | 선택 | 에러 메시지 (표시 시 빨간색) |
| `children` | `ReactNode` | 필수 | 입력 요소 (Input, Textarea, Select 등) |
| `required` | `boolean` | 선택 | `*` 표시 여부 |

**사용 예시**:

```tsx
<FormField label="데이터셋 이름" error={errors.name?.message} required>
  <Input {...register("name")} placeholder="이름을 입력하세요" />
</FormField>
```

---

### 2. SearchInput (`search-input.tsx`)

**목적**: 돋보기 아이콘이 내장된 검색 입력 필드. 목록 페이지 상단 검색 바에 공통 사용.

**Props API**: `InputProps` 전체 상속 (추가 Props 없음)

**구현 특징**: 아이콘 위치를 위해 `pl-9` 패딩 적용.

**사용 예시**:

```tsx
<SearchInput
  placeholder="데이터셋 검색..."
  className="w-64"
  value={search}
  onChange={(e) => setSearch(e.target.value)}
/>
```

---

### 3. DeleteConfirmDialog (`delete-confirm-dialog.tsx`)

**목적**: 삭제 작업 전 사용자 확인을 받는 AlertDialog 래퍼. 파괴적 작업의 안전장치.

**Props API**:

| Prop | Type | Required | Description |
|------|------|----------|-------------|
| `title` | `string` | 필수 | 다이얼로그 제목 |
| `description` | `string` | 필수 | 삭제 대상 설명 문구 |
| `onConfirm` | `() => void` | 필수 | 확인 버튼 클릭 시 콜백 |
| `trigger` | `ReactNode` | 필수 | 다이얼로그를 여는 트리거 요소 |

**사용 예시**:

```tsx
<DeleteConfirmDialog
  title="데이터셋 삭제"
  description="이 데이터셋을 삭제하면 복구할 수 없습니다. 계속하시겠습니까?"
  onConfirm={handleDelete}
  trigger={
    <Button variant="destructive" size="sm">삭제</Button>
  }
/>
```

---

### 4. SimplePagination (`simple-pagination.tsx`)

**목적**: 이전/다음 버튼 + 현재 페이지/전체 페이지 표시. 목록 페이지 하단에 공통 사용.

**Props API**:

| Prop | Type | Required | Description |
|------|------|----------|-------------|
| `page` | `number` | 필수 | 현재 페이지 (0-indexed 또는 1-indexed는 구현 확인) |
| `totalPages` | `number` | 필수 | 전체 페이지 수 |
| `onPageChange` | `(page: number) => void` | 필수 | 페이지 변경 콜백 |

**사용 예시**:

```tsx
<SimplePagination
  page={page}
  totalPages={totalPages}
  onPageChange={setPage}
/>
```

---

### 5. TableEmpty (`table-empty.tsx`)

**목적**: 테이블에 데이터가 없을 때 표시하는 빈 상태 행.

**Props API**:

| Prop | Type | Required | Description |
|------|------|----------|-------------|
| `colSpan` | `number` | 필수 | 테이블 컬럼 수 (전체 너비 병합) |
| `message` | `string` | 선택 | 표시할 메시지 (기본값: "데이터가 없습니다") |
| `icon` | `ReactNode` | 선택 | 메시지 위에 표시할 아이콘 |

**사용 예시**:

```tsx
<TableBody>
  {data?.length === 0 && (
    <TableEmpty
      colSpan={5}
      message="등록된 파이프라인이 없습니다"
      icon={<GitBranch className="h-8 w-8 text-muted-foreground" />}
    />
  )}
</TableBody>
```

---

### 6. TableSkeleton (`table-skeleton.tsx`)

**목적**: 데이터 로딩 중 테이블 형태의 Skeleton 플레이스홀더 행을 표시.

**Props API**:

| Prop | Type | Required | Description |
|------|------|----------|-------------|
| `rows` | `number` | 선택 | 표시할 행 수 (기본값: 5) |
| `columns` | `number` | 선택 | 표시할 컬럼 수 (기본값: 4) |

**사용 예시**:

```tsx
<TableBody>
  {isLoading ? (
    <TableSkeleton rows={10} columns={5} />
  ) : (
    data?.map(item => <TableRow key={item.id}>...</TableRow>)
  )}
</TableBody>
```

---

## C. 컴포넌트 조합 패턴

프로젝트 전반에서 반복 사용되는 컴포넌트 조합 패턴이다.
새 페이지 개발 시 아래 패턴을 우선 참고한다.

---

### 패턴 1. Status Badge

현재(As-Is): 컬러 클래스를 직접 하드코딩하여 일관성 없음.
권장(To-Be): `variant` prop 기반으로 통일.

```tsx
// As-Is (하드코딩 — 지양)
<Badge className="bg-green-100 text-green-800 text-xs border-0">✓ 인증됨</Badge>
<Badge className="bg-red-100 text-red-800 text-xs border-0">사용 중단</Badge>
<Badge className="bg-yellow-100 text-yellow-800 text-xs border-0">검토 중</Badge>

// To-Be (variant 기반 — 권장)
<Badge variant="success">✓ 인증됨</Badge>
<Badge variant="destructive">사용 중단</Badge>
<Badge variant="warning">검토 중</Badge>
```

> Badge의 `success`, `warning`, `info` variant는 `badge.tsx`에 추가가 필요하다.
> 현재(As-Is) 방식은 신규 코드에서 사용하지 않는다.

---

### 패턴 2. Icon Button

아이콘 단독 버튼의 표준 조합이다.

```tsx
// 편집 버튼
<Button variant="ghost" size="icon-sm">
  <Pencil className="h-4 w-4" />
</Button>

// 삭제 버튼
<Button variant="ghost" size="icon-sm" className="text-destructive hover:text-destructive">
  <Trash2 className="h-4 w-4" />
</Button>

// 더보기 버튼
<Button variant="ghost" size="icon-sm">
  <MoreHorizontal className="h-4 w-4" />
</Button>
```

---

### 패턴 3. Table Row with Hover Actions

테이블 행에 마우스 오버 시 액션 버튼이 나타나는 패턴이다.

```tsx
<TableRow className="cursor-pointer hover:bg-muted/50 transition-colors group">
  <TableCell>{item.name}</TableCell>
  <TableCell>{item.status}</TableCell>
  {/* 액션 컬럼: 기본 투명, hover 시 표시 */}
  <TableCell>
    <div className="opacity-0 group-hover:opacity-100 transition-opacity flex gap-1 justify-end">
      <Button variant="ghost" size="icon-xs">
        <Pencil className="h-3 w-3" />
      </Button>
      <Button variant="ghost" size="icon-xs" className="text-destructive hover:text-destructive">
        <Trash2 className="h-3 w-3" />
      </Button>
    </div>
  </TableCell>
</TableRow>
```

---

### 패턴 4. Search + Filter Toolbar

목록 페이지 상단의 검색/필터 툴바 표준 레이아웃이다.

```tsx
<div className="flex items-center gap-3 flex-wrap">
  {/* 검색 */}
  <SearchInput placeholder="검색..." className="w-64" />

  {/* 상태 필터 */}
  <Select value={statusFilter} onValueChange={setStatusFilter}>
    <SelectTrigger className="w-36">
      <SelectValue placeholder="상태" />
    </SelectTrigger>
    <SelectContent>
      <SelectItem value="all">전체</SelectItem>
      <SelectItem value="active">활성</SelectItem>
      <SelectItem value="inactive">비활성</SelectItem>
    </SelectContent>
  </Select>

  {/* 추가 필터 버튼 (선택) */}
  <Button variant="outline" size="sm">
    <Filter className="h-4 w-4 mr-2" />
    필터
  </Button>
</div>
```

---

## D. Button 사용 규칙

Button의 `variant`는 사용 맥락에 따라 엄격히 구분한다.

| Variant | 사용 맥락 | 예시 |
|---------|-----------|------|
| `default` | 페이지/폼의 주요 액션 | 저장, 생성, 확인, 실행 |
| `destructive` | 삭제/제거 액션 | 삭제, 초기화 |
| `outline` | 보조 액션, 취소 | 취소, 닫기, 내보내기 |
| `secondary` | 3순위 액션 | 필터, 정렬, 미리보기 |
| `ghost` | 인라인/아이콘 액션 | 편집 아이콘, 더보기, 행 내 액션 |
| `link` | 텍스트 내비게이션 링크 | "자세히 보기", 외부 링크 |

**Size 선택 기준**:

| Size | 사용 맥락 |
|------|-----------|
| `lg` | 인증 페이지(로그인/회원가입) 주 CTA |
| `default` | 페이지 헤더 주 액션 |
| `sm` | 툴바, 카드 내 액션 |
| `xs` | 인라인 텍스트 레벨 액션 |
| `icon` | 독립 아이콘 버튼 (기본) |
| `icon-sm` | 테이블 행 액션, 밀도 높은 UI |
| `icon-xs` | 태그 삭제 버튼 등 매우 작은 아이콘 |
| `icon-lg` | 강조 아이콘 버튼 |

---

## E. AI 위젯 컴포넌트 (`components/ai/widgets/`)

AI 챗에서 인터랙티브 데이터를 렌더링하는 위젯 시스템. WidgetRegistry 패턴으로 MCP 도구명 → React 컴포넌트를 매핑한다.

### 1. 위젯 인프라

| Component | File | 역할 |
|-----------|------|------|
| WidgetRegistry | `widgets/WidgetRegistry.ts` | 도구명 → lazy 컴포넌트 매핑 |
| WidgetShell | `widgets/WidgetShell.tsx` | 공통 카드 래퍼 (헤더/액션/딥링크/반응형 높이) |
| WidgetErrorBoundary | `widgets/WidgetErrorBoundary.tsx` | 렌더링 실패 폴백 UI |
| WidgetSkeleton | `widgets/WidgetSkeleton.tsx` | 로딩 스켈레톤 |

**WidgetProps 인터페이스** (모든 위젯 공통):
```typescript
interface WidgetProps<T> {
  input: T;                          // MCP 도구 입력 데이터
  onNavigate?: (path: string) => void; // 딥링크 이동 콜백
  displayMode: AIMode;               // 'side' | 'floating' | 'fullscreen'
}
```

**반응형 높이** (WidgetShell):
- side / floating: `max-h-[250px]` (내부 스크롤)
- fullscreen: `max-h-[450px]`

### 2. 위젯 목록

| Widget | 도구명 | 데이터 패턴 | 설명 |
|--------|--------|------------|------|
| DatasetWidget | `show_dataset` | Reference (FE fetch) | 데이터셋 메타 + 데이터 테이블 |
| TableWidget | `show_table` | Passthrough (AI 전달) | SQL 쿼리 결과 리치 테이블 |
| ChartWidgetAdapter | `show_chart` | Passthrough | 기존 InlineChartWidget 어댑터 |
| NavigateToWidget | `navigate_to` | — | 딥링크 자동 이동 카드 |

### 3. 테이블 공통 서브 컴포넌트 (`widgets/table/`)

DatasetWidget과 TableWidget이 공유하는 테이블 UI 컴포넌트.

| Component | File | 역할 |
|-----------|------|------|
| CellRenderer | `table/CellRenderer.tsx` | 데이터 타입별 셀 렌더링 |
| ColumnFilterDropdown | `table/ColumnFilterDropdown.tsx` | 컬럼 헤더 필터 드롭다운 |
| ActiveFilterChips | `table/ActiveFilterChips.tsx` | 활성 필터 칩 표시 |
| Pagination | `table/Pagination.tsx` | 번호 기반 페이지네이션 |
| ExportDropdown | `table/ExportDropdown.tsx` | CSV/JSON 내보내기 드롭다운 |

### 4. 테이블 UX 규칙

**데이터 타입별 렌더링** (CellRenderer):
- 숫자: `text-right font-medium` + `font-variant-numeric: tabular-nums`
- 상태 키워드: 컬러 도트 `●` + 색상 텍스트 (정상=green, 점검중=yellow, 오류=red)
- null/undefined: `—` (`text-muted-foreground/50`)
- 긴 텍스트: `max-w-[160px] truncate`

**컬럼 헤더**:
- 컬럼명 클릭 → 정렬 토글 (asc → desc → none)
- ▼ 아이콘 클릭 → 필터 드롭다운 (텍스트 검색 + 유니크 값 체크박스)
- 활성 필터는 테이블 위에 pill 칩으로 표시

**페이지네이션**:
- 번호 버튼 (`w-6 h-6 rounded text-xs`)
- 현재 페이지: `bg-primary text-primary-foreground`
- 5페이지 초과: `‹ 1 ... 4 [5] 6 ... 10 ›` 말줄임

**행 스타일**:
- 패딩: `py-2` (8px)
- hover: `hover:bg-muted/20 transition-colors duration-150`
- 구분선: `border-border/50`

**내보내기**:
- 헤더 액션 영역에 다운로드 아이콘 → CSV / JSON 드롭다운
- CSV: BOM(`\uFEFF`) + 콤마 구분, 따옴표/줄바꿈 이스케이프
- JSON: `JSON.stringify(data, null, 2)`

---

## F. 아직 구현되지 않은 확장 항목 (To-Be)

아래 항목은 현재(As-Is) 코드베이스에 없으며, 향후 구현이 권장된다.

| 항목 | 우선순위 | 설명 |
|------|----------|------|
| Badge `success` variant | 높음 | 상태 뱃지 일관성 확보를 위해 필요 |
| Badge `warning` variant | 높음 | 동일 |
| Badge `info` variant | 중간 | 정보성 상태 표시 |
| Toast 유틸 함수 표준화 | 중간 | `toast.success()`, `toast.error()` 래퍼 |
| ~~DataTable 공통 컴포넌트~~ | ~~낮음~~ | ~~정렬/페이지네이션 통합 테이블~~ → **완료** (AI 위젯 `table/` 서브 컴포넌트로 구현) |
