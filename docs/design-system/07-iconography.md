# 07. Iconography

Smart Fire Hub 아이콘 시스템 — 사용 규칙, 크기 스케일, 색상, 정렬 가이드라인.

---

## 1. 아이콘 라이브러리

**유일한 아이콘 소스: [Lucide React](https://lucide.dev/)**

프로젝트 전반에 걸쳐 Lucide React 단독 사용. 다른 아이콘 라이브러리(Heroicons, Radix Icons, react-icons 등)의 혼용은 금지한다. 일관된 stroke 스타일과 번들 크기 최적화를 위한 결정이다.

```tsx
// 올바른 import
import { Database, Plus, Search, ChevronDown } from 'lucide-react';

// 금지: 다른 라이브러리 혼용
// import { FiDatabase } from 'react-icons/fi';  // X
// import { MagnifyingGlassIcon } from '@heroicons/react/24/solid';  // X
```

---

## 2. 크기 스케일

shadcn/ui의 Button 컴포넌트는 `[&_svg:not([class*='size-'])]:size-4` 패턴으로 내부 아이콘을 16px로 자동 조정한다. 아이콘에 크기 클래스(`h-*`, `w-*`, `size-*`)가 없으면 버튼 내부에서 자동으로 16px이 적용된다.

| 컨텍스트 | Size | Tailwind | strokeWidth | 예시 |
|---------|------|----------|-------------|------|
| Badge/tag 내부 | 12px | `h-3 w-3` | 2 | Collapsible 내 Chevron |
| 기본 인라인 | 16px | `h-4 w-4` | 2 | 버튼 아이콘, 테이블 액션, 폼 아이콘 |
| 사이드바/헤더 | 20px | `h-5 w-5` | 2 | 사이드바 네비게이션, 헤더, DashboardWidgetCard 로더 |
| 빈 상태/강조 | 24px | `h-6 w-6` | 2 | Empty state, 기능 소개 아이콘 |

> **strokeWidth**: 모든 크기에서 기본값 `2`를 유지한다. 특별한 사유 없이 변경하지 않는다. `1.5`나 `2.5` 등의 커스텀 stroke는 Lucide 기본 SVG 디자인과 어울리지 않는다.

### 2.1 크기별 TSX 예시

```tsx
// Badge/tag 내부 — 12px
import { ChevronDown } from 'lucide-react';

<button className="flex items-center gap-1 text-xs font-medium px-2 py-0.5 rounded-full bg-muted">
  PostgreSQL
  <ChevronDown className="h-3 w-3 flex-shrink-0" />
</button>
```

```tsx
// 기본 인라인 — 16px (shadcn Button 내부에서 자동 적용)
import { Plus, Search, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';

// shadcn Button: svg에 size 클래스가 없으면 자동으로 size-4(16px) 적용
<Button>
  <Plus /> 데이터셋 추가
</Button>

// 명시적 크기 지정 (Button 외부 또는 size 재정의 시)
<button className="flex items-center gap-2">
  <Search className="h-4 w-4 flex-shrink-0 text-muted-foreground" />
  <span className="text-sm">검색</span>
</button>

// 테이블 액션 버튼
<Button variant="ghost" size="icon" className="h-8 w-8">
  <Trash2 className="h-4 w-4 text-destructive" />
</Button>
```

```tsx
// 사이드바/헤더 — 20px
import { Database, LayoutDashboard, Settings } from 'lucide-react';

// 사이드바 네비게이션 항목
<nav>
  <a href="/datasets" className="flex items-center gap-3 px-3 py-2 rounded-md hover:bg-muted">
    <Database className="h-5 w-5 flex-shrink-0 text-muted-foreground" />
    <span className="text-[13px] leading-5">데이터셋</span>
  </a>
</nav>

// 헤더 아이콘 버튼
<Button variant="ghost" size="icon" className="h-9 w-9">
  <Settings className="h-5 w-5" />
</Button>
```

```tsx
// 빈 상태/강조 — 24px
import { Inbox, FolderOpen } from 'lucide-react';

// Empty state
<div className="flex flex-col items-center gap-3 py-12 text-muted-foreground">
  <Inbox className="h-6 w-6" />
  <p className="text-sm">데이터셋이 없습니다</p>
  <Button variant="outline" size="sm">
    <Plus /> 새 데이터셋
  </Button>
</div>
```

---

## 3. 색상 규칙

아이콘 색상은 CSS `currentColor`를 통해 부모로부터 상속된다. Lucide 아이콘은 기본적으로 `stroke="currentColor"`로 렌더링되므로, Tailwind `text-*` 클래스로 색상을 제어한다.

| 상태 | 현재(As-Is) | 권장(To-Be) | 용도 |
|------|------------|------------|------|
| 기본 | `text-muted-foreground` | `text-muted-foreground` | 일반 UI 아이콘, 보조 정보 |
| 활성/브랜드 | `text-blue-600` (혼용) | `text-primary` | 활성 네비게이션, CTA 아이콘 |
| 성공 | `text-green-600` | `text-green-600` | 성공 상태 (design token 추가 권장) |
| 경고 | `text-amber-600` | `text-amber-600` | 경고 상태 (design token 추가 권장) |
| 위험/삭제 | `text-red-600` (혼용) | `text-destructive` | 삭제, 에러 상태 |
| 비활성화 | `opacity-50` 또는 없음 | `opacity-50` | 비활성화된 버튼/컨트롤 내 아이콘 |
| 버튼 내부 | (부모 상속) | inherit | shadcn Button이 variant에 따라 제어 |

> **권장 사항**: `text-green-600`, `text-amber-600`을 CSS 변수(`--color-success`, `--color-warning`)로 토큰화하여 다크 모드 대응을 준비한다. 현재는 하드코딩 허용.

### 3.1 색상별 TSX 예시

```tsx
import { CheckCircle, AlertTriangle, XCircle, Loader2, Star } from 'lucide-react';

// 기본 — muted-foreground
<Search className="h-4 w-4 text-muted-foreground" />

// 활성/브랜드 — primary (사이드바 활성 항목)
<Database className="h-5 w-5 text-primary" />

// 성공 상태
<CheckCircle className="h-4 w-4 text-green-600" />

// 경고 상태
<AlertTriangle className="h-4 w-4 text-amber-600" />

// 위험/삭제
<XCircle className="h-4 w-4 text-destructive" />

// 비활성화 (버튼 자체가 disabled일 때 아이콘에 별도 처리 불필요)
<Button disabled>
  <Plus /> {/* shadcn Button의 disabled 스타일이 opacity 처리 */}
  추가
</Button>

// 명시적 비활성화 (커스텀 컨트롤)
<span className="flex items-center gap-2 opacity-50 cursor-not-allowed">
  <Star className="h-4 w-4" />
  즐겨찾기
</span>

// 로딩 스피너 (Lucide Loader2 + spin animation)
<Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
```

---

## 4. 아이콘-텍스트 간격 (Icon-Text Spacing)

아이콘과 텍스트가 함께 있을 때 `gap-*`으로 간격을 제어한다. `margin`이나 `padding`을 아이콘에 직접 적용하는 것을 피한다.

| 컨텍스트 | gap | 픽셀 | 예시 |
|---------|-----|------|------|
| Badge/chip 내부 | `gap-1` | 4px | 태그, 배지 |
| 기본 (버튼/라벨) | `gap-2` | 8px | 버튼, 폼 레이블, 일반 인라인 |
| 사이드바 네비게이션 | `gap-3` | 12px | 사이드바 nav 항목 |

```tsx
// gap-1 — Badge/chip
<span className="flex items-center gap-1 text-xs font-medium px-2 py-0.5 rounded-full border">
  <Circle className="h-3 w-3 fill-green-500 text-green-500" />
  활성
</span>

// gap-2 — 기본 버튼
<Button>
  <Download className="h-4 w-4" />
  내보내기
</Button>

// gap-2 — 폼 레이블
<label className="flex items-center gap-2 text-sm font-medium">
  <Lock className="h-4 w-4 text-muted-foreground" />
  비밀번호
</label>

// gap-3 — 사이드바 네비게이션
<a className="flex items-center gap-3 px-3 py-2 rounded-md">
  <PieChart className="h-5 w-5 flex-shrink-0 text-muted-foreground" />
  <span className="text-[13px] leading-5">분석</span>
</a>
```

---

## 5. 정렬 규칙

### 5.1 flex 컨테이너 내 정렬

아이콘을 포함하는 컨테이너는 항상 `flex items-center`를 사용한다. 아이콘 자체에는 `flex-shrink-0`을 추가하여 텍스트가 길어질 때 아이콘이 압축되는 것을 방지한다.

```tsx
// 올바른 패턴
<div className="flex items-center gap-2">
  <FileText className="h-4 w-4 flex-shrink-0 text-muted-foreground" />
  <span className="text-sm truncate">매우 긴 파일 이름이 잘려도 아이콘은 유지됩니다</span>
</div>

// 금지 패턴 — flex-shrink-0 누락
<div className="flex items-center gap-2">
  <FileText className="h-4 w-4 text-muted-foreground" /> {/* 텍스트가 길면 아이콘이 압축됨 */}
  <span className="text-sm">텍스트</span>
</div>
```

### 5.2 인라인 텍스트 내 정렬

아이콘을 `inline`으로 텍스트 사이에 삽입할 때는 `vertical-align: -0.125em`을 사용하여 baseline 보정을 한다. Tailwind의 arbitrary value로 적용한다.

```tsx
// 인라인 텍스트 내 아이콘 — vertical-align 보정
<p className="text-sm text-muted-foreground">
  데이터셋을{' '}
  <ExternalLink className="inline h-4 w-4 [vertical-align:-0.125em]" />
  {' '}새 탭에서 엽니다
</p>

// 또는 SVG style prop으로 직접 지정
<Info
  className="inline h-4 w-4 text-blue-500"
  style={{ verticalAlign: '-0.125em' }}
/>
```

---

## 6. shadcn/ui Button SVG 자동 조정 패턴

shadcn/ui Button 컴포넌트는 CSS 선택자 `[&_svg:not([class*='size-'])]:size-4`를 사용하여 내부 `<svg>` 요소에 크기 클래스가 없으면 자동으로 `size-4`(16px × 16px)를 적용한다.

```tsx
// 자동 조정 동작 예시
<Button variant="default">
  <Plus />           {/* size 클래스 없음 → 자동으로 size-4(16px) 적용 */}
  데이터셋 추가
</Button>

// 크기 재정의 — size 클래스를 명시하면 자동 조정 무시
<Button variant="outline" size="lg">
  <Database className="h-5 w-5" />   {/* h-5 w-5 명시 → 자동 조정 비활성 */}
  데이터베이스
</Button>

// icon 전용 버튼 (정사각형)
<Button variant="ghost" size="icon">
  <Settings />   {/* 자동으로 16px → Button size="icon"(h-9 w-9)과 균형 맞음 */}
</Button>
```

> **주의**: `size-*` 클래스(`size-4`, `size-5` 등)를 사용하면 자동 조정이 비활성화된다. `h-*`/`w-*`를 동시에 지정하는 것과 동일하게 동작한다. Button 내부 아이콘 크기가 16px을 벗어나야 할 때만 명시적 클래스를 추가한다.

---

## 7. 아이콘 선택 가이드라인

### 7.1 의미 일관성

같은 개념에는 항상 같은 아이콘을 사용한다. 프로젝트 전반에서 통일된 시각적 언어를 유지한다.

| 개념 | 아이콘 | import 이름 |
|------|--------|------------|
| 데이터셋 | 표 형태 | `Table2` |
| 파이프라인 | 흐름 | `GitBranch` |
| AI 에이전트 | 봇 | `Bot` |
| 데이터베이스 연결 | 원통 | `Database` |
| 추가/생성 | 플러스 | `Plus` |
| 삭제 | 휴지통 | `Trash2` |
| 편집 | 연필 | `Pencil` |
| 검색 | 돋보기 | `Search` |
| 설정 | 톱니바퀴 | `Settings` |
| 내보내기 | 다운로드 | `Download` |
| 가져오기 | 업로드 | `Upload` |
| 새로고침 | 회전 화살표 | `RefreshCw` |
| 닫기/취소 | X | `X` |
| 확인/성공 | 체크 | `Check` |
| 경고 | 삼각형 | `AlertTriangle` |
| 정보 | 원형 i | `Info` |
| 로딩 | 로더 | `Loader2` |
| 외부 링크 | 화살표+박스 | `ExternalLink` |
| 복사 | 클립보드 | `Copy` |
| 지도 | 핀 | `MapPin` |

### 7.2 금지 패턴

```tsx
// 금지: 동일 개념에 다른 아이콘 혼용
<Trash className="h-4 w-4" />   {/* X — Trash2를 사용해야 함 */}
<Edit className="h-4 w-4" />    {/* X — Pencil을 사용해야 함 */}
<Reload className="h-4 w-4" />  {/* X — RefreshCw를 사용해야 함 */}

// 금지: 아이콘에 색상이나 크기 style prop 직접 사용 (vertical-align 제외)
<Plus style={{ color: 'blue', width: 16 }} />  {/* X — Tailwind 클래스 사용 */}

// 금지: strokeWidth 임의 변경
<Database strokeWidth={1.5} />  {/* X — 기본값 2 유지 */}

// 금지: 다른 라이브러리 아이콘 사용
import { FaDatabase } from 'react-icons/fa';  {/* X */}
```

---

## 8. 접근성

- **장식용 아이콘**: 옆에 텍스트가 있으면 `aria-hidden="true"`를 추가하여 스크린 리더가 중복 읽기를 방지한다.
- **단독 아이콘 버튼**: 텍스트 없이 아이콘만 있는 버튼은 반드시 `aria-label`을 제공한다.

```tsx
// 장식용 아이콘 — aria-hidden
<Button>
  <Plus aria-hidden="true" />
  데이터셋 추가
</Button>

// 단독 아이콘 버튼 — aria-label 필수
<Button variant="ghost" size="icon" aria-label="설정 열기">
  <Settings aria-hidden="true" />
</Button>

// shadcn/ui VisuallyHidden 활용 (대안)
import { VisuallyHidden } from '@radix-ui/react-visually-hidden';

<Button variant="ghost" size="icon">
  <Settings aria-hidden="true" />
  <VisuallyHidden>설정 열기</VisuallyHidden>
</Button>
```

---

## 9. 현재(As-Is) 감사 결과 및 권장 수정 사항

코드베이스에서 발견된 개선 필요 패턴:

| 발견된 패턴 | 빈도 | 권장 수정 |
|-----------|------|----------|
| `text-blue-600` 아이콘 색상 | 다수 | `text-primary`로 교체 |
| `text-red-600` 아이콘 색상 | 다수 | `text-destructive`로 교체 |
| `flex-shrink-0` 누락 | 일부 | 아이콘 컨테이너에 추가 |
| `aria-label` 없는 icon-only 버튼 | 일부 | `aria-label` 추가 |
| `strokeWidth` 비기본값 | 드물게 | 제거하여 기본값(2) 사용 |
| `h-4 w-4` 이외 크기 (사이드바) | AppLayout | `h-5 w-5`로 통일 |
