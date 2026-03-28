# 테이블 위젯 UX 개선

> **날짜**: 2026-03-28
> **스코프**: InlineDatasetWidget + InlineTableWidget UX 폴리시
> **대상 파일**: `apps/firehub-web/src/components/ai/widgets/InlineDatasetWidget.tsx`, `InlineTableWidget.tsx`

---

## 1. 개요

AI 챗의 테이블 기반 위젯(데이터셋, 테이블)의 UX가 "개발자 스러운" 날것 상태다. 데이터 타입별 렌더링, 필터 드롭다운, 페이지네이션, 내보내기 등을 세련되게 개선한다.

---

## 2. 데이터 타입별 렌더링

테이블 셀을 데이터 타입에 따라 다르게 렌더링한다.

| 타입 | 렌더링 | 적용 기준 |
|------|--------|----------|
| 숫자 | 우측 정렬 + `font-variant-numeric: tabular-nums` + font-weight 500 | `typeof value === 'number'` 또는 `!isNaN(Number(value))` |
| 카테고리/텍스트 | 컬러 뱃지 (배경색 + `border-radius: 4px` + 작은 폰트) | DatasetWidget: 컬럼 dataType이 TEXT/VARCHAR이고 값이 짧은 경우 (10자 이하). TableWidget: 자동 감지 (유니크 값 10개 이하) |
| 상태 | 컬러 도트 ● + 색상 텍스트 | 값이 상태 키워드 매칭 (정상/활성→green, 점검중/경고→yellow, 수리중/오류/실패→red) |
| null/undefined | `—` 회색 (`text-muted-foreground/50`) | 현재와 동일 |
| 긴 텍스트 | `max-w-[160px] truncate` | 현재와 동일 |

### 상태 색상 매핑

```typescript
const STATUS_COLORS: Record<string, { dot: string; text: string }> = {
  // Green
  '정상': { dot: 'bg-emerald-400', text: 'text-emerald-400' },
  '활성': { dot: 'bg-emerald-400', text: 'text-emerald-400' },
  '완료': { dot: 'bg-emerald-400', text: 'text-emerald-400' },
  'SUCCESS': { dot: 'bg-emerald-400', text: 'text-emerald-400' },
  // Yellow
  '점검중': { dot: 'bg-yellow-400', text: 'text-yellow-400' },
  '경고': { dot: 'bg-yellow-400', text: 'text-yellow-400' },
  '대기': { dot: 'bg-yellow-400', text: 'text-yellow-400' },
  'PENDING': { dot: 'bg-yellow-400', text: 'text-yellow-400' },
  // Red
  '수리중': { dot: 'bg-red-400', text: 'text-red-400' },
  '오류': { dot: 'bg-red-400', text: 'text-red-400' },
  '실패': { dot: 'bg-red-400', text: 'text-red-400' },
  'FAILED': { dot: 'bg-red-400', text: 'text-red-400' },
};
```

매칭되지 않는 값은 기본 텍스트로 렌더링.

---

## 3. 메타 정보 칩화 (DatasetWidget만)

현재:
```
컬럼 5개    행 5건    최종 수정: 2026.02.28
```

개선:
```
[📐 5개 컬럼] [📏 5건] [📅 2026.02.28]
```

- 각 항목을 `bg-muted rounded-full px-2 py-0.5 text-xs` pill 스타일 칩으로 변경
- 간격: `gap-2`

---

## 4. 컬럼 헤더 드롭다운 필터

기존 컬럼 아래 `<input>` 필터 행을 제거하고, 컬럼 헤더에 드롭다운 필터를 통합한다.

### 헤더 구조
```
[소방서명 ↕ ▼] [장비유형 ↕ ▼] [수량 ↕] [상태 ↕ ▼]
```

- 컬럼명 클릭 → 정렬 토글 (asc/desc/none)
- ▼ 아이콘 클릭 → 필터 드롭다운 열기

### 필터 드롭다운
- 상단: 텍스트 검색 input
- 하단: 현재 페이지 데이터의 유니크 값 목록 (체크박스 다중 선택)
- 선택 시 즉시 필터 적용

### 활성 필터 칩
- 테이블 위에 활성 필터 표시: `장비유형: 펌프차 ✕` `상태: 정상 ✕`
- 칩 ✕ 클릭으로 개별 해제
- "전체 해제" 링크

### 데이터 소스별 동작
- **InlineDatasetWidget**: 서버사이드 — API `search` 파라미터 활용 (단일 검색어)
- **InlineTableWidget**: 클라이언트사이드 — 현재 rows 데이터에서 필터링

---

## 5. 페이지네이션 개선

현재:
```
1–20 / 5건          [← 이전] [다음 →]
```

개선:
```
5건 중 3건 표시      [‹] [1] [2] [3] [›]
```

- 번호 버튼: `w-6 h-6 rounded text-xs`
- 현재 페이지: `bg-primary text-primary-foreground`
- 비활성: `text-muted-foreground`
- 페이지 5개 이상일 때: `‹ 1 ... 4 [5] 6 ... 10 ›` 말줄임 패턴
- ‹/› 화살표로 이전/다음

---

## 6. 행 간격 + hover 효과

- 행 패딩: `py-1.5` → `py-2` (8px)
- hover: `hover:bg-muted/20` + `transition-colors duration-150`
- 행 구분선: `border-border` → `border-border/50` (더 미묘하게)

---

## 7. 내보내기 드롭다운

WidgetShell 헤더 actions 영역에 내보내기 버튼 추가 (양쪽 위젯 모두).

- 버튼: `📥` 아이콘 (Download from lucide-react)
- 클릭 시 드롭다운: CSV / Excel / JSON
- CSV: `\uFEFF` BOM + 콤마 구분 (기존 InlineTableWidget 로직 재활용)
- Excel: 미지원 표시 ("준비 중") — 기존 ExportDialog 연동 필요
- JSON: `JSON.stringify(rows, null, 2)` blob 다운로드

### DatasetWidget 내보내기
- 현재 페이지 데이터만 내보내기 (서버사이드 페이지네이션)
- 전체 데이터 내보내기는 "상세 보기"에서 기존 ExportDialog 사용

### TableWidget 내보내기
- 필터 적용된 전체 데이터 내보내기 (클라이언트사이드)

---

## 8. 검증 기준

- [ ] 숫자 컬럼 우측 정렬 + tabular-nums 적용
- [ ] 상태 값에 컬러 도트 인디케이터 표시
- [ ] 메타 정보 pill 칩 스타일 (DatasetWidget)
- [ ] 컬럼 헤더 ▼ 클릭 시 필터 드롭다운 열림
- [ ] 활성 필터 칩 표시 + 개별/전체 해제
- [ ] 페이지네이션 번호 버튼 + 현재 페이지 하이라이트
- [ ] 행 hover 시 부드러운 배경 전환
- [ ] 내보내기 드롭다운 (CSV/JSON) 동작
- [ ] 빌드 + 타입체크 통과
- [ ] 사이드/플로팅/전체화면 3모드에서 정상 표시
