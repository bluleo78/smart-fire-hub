# 05. 페이지 레이아웃 패턴

Smart Fire Hub 프론트엔드(`apps/firehub-web`)에서 반복되는 페이지 레이아웃 구조를 5가지 템플릿으로 정의한다.
새 페이지를 개발할 때 아래 템플릿 중 가장 가까운 것을 기반으로 작성한다.

---

## A. List Page (목록 페이지)

검색/필터 + 테이블 + 페이지네이션으로 구성되는 가장 일반적인 패턴이다.

**실제 적용 페이지**: `DatasetListPage`, `PipelineListPage`, `QueryListPage`, `UserListPage`

**TSX 스켈레톤**:

```tsx
// 실제 연결 여부(@tanstack/react-query 의 onlineManager) — 구독 함수는 모듈 범위에 둔다
const subscribeOnline = (onChange: () => void) => onlineManager.subscribe(onChange);
// 조회와 페이지네이션이 같은 페이지 크기를 쓴다
const PAGE_SIZE = 10;

export default function XxxListPage() {
  const navigate = useNavigate();
  const isOnline = useSyncExternalStore(subscribeOnline, () => onlineManager.isOnline(), () => true);
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("all");
  const { data, isLoading, isError, isPaused } = useXxxList({ page, size: PAGE_SIZE, search, status });

  // 다음 쪽을 불러오거나 실패하면 data 가 비어 쪽 이동이 사라지므로 마지막 성공 값을 쓴다(WD-956)
  const [lastTotalPages, setLastTotalPages] = useState(0);
  if (data && data.totalPages !== lastTotalPages) {
    setLastTotalPages(data.totalPages);
  }
  const totalPages = data ? data.totalPages : lastTotalPages;

  // 범위 밖 쪽(이 쪽의 마지막 항목이 지워져 재조회된 뒤 등)은 빈 목록으로 오해하지 않게 마지막 쪽으로 옮긴다(WD-146)
  const pageOutOfRange = !!data && page > 0 && page >= Math.max(data.totalPages, 1);
  useEffect(() => {
    if (pageOutOfRange && data) setPage(Math.max(data.totalPages - 1, 0));
  }, [pageOutOfRange, data]);

  // 검색·필터가 바뀌면 첫 쪽부터 다시 본다 — 기억한 쪽 수도 지워 이전 조건의 쪽 수를 보이지 않는다
  const resetPaging = () => {
    setPage(0);
    setLastTotalPages(0);
  };
  const handleSearchChange = (value: string) => {
    setSearch(value);
    resetPaging();
  };
  const handleStatusChange = (value: string) => {
    setStatus(value);
    resetPaging();
  };

  return (
    <div className="space-y-6">
      {/* 페이지 헤더 */}
      <div className="flex items-center justify-between">
        <PageHeading>페이지 제목</PageHeading>
        <Button>
          <Plus className="h-4 w-4" />
          새로 만들기
        </Button>
      </div>

      {/* 검색/필터 툴바 */}
      <div className="flex items-center gap-3 flex-wrap">
        <SearchInput
          placeholder="검색..."
          value={search}
          onChange={handleSearchChange}
          debounceMs={300}
        />
        <Select value={status} onValueChange={handleStatusChange}>
          <SelectTrigger className="w-36" aria-label="상태 필터">
            <SelectValue placeholder="상태" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">전체</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* 데이터 테이블 */}
      <div className="rounded-md border">
        <Table aria-label="항목 목록">
          <TableHeader>
            <TableRow>
              <TableHead>컬럼 A</TableHead>
              <TableHead>컬럼 B</TableHead>
              <TableHead className="w-[100px]">액션</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading || pageOutOfRange || (!data && isPaused && isOnline) ? (
              // pageOutOfRange 는 마지막 쪽으로 옮기는 동안 로딩 행으로 가린다(빈 안내가 번쩍이지 않게)
              // isPaused 는 탭이 숨어 재시도가 멈춰도 참이다 — 연결돼 있으면 불러오는 중으로 보인다(WD-966)
              <TableSkeletonRows columns={3} />
            ) : !data && isPaused ? (
              // 오프라인 대기는 로딩도 실패도 아니다(WD-966)
              <TableEmptyRow colSpan={3} message="인터넷 연결이 없어 목록을 불러오지 못하고 있습니다. 연결되면 자동으로 다시 불러옵니다." />
            ) : isError && !data ? (
              // 재조회만 실패했으면 받아 둔 목록을 그대로 보인다(WD-146)
              <TableErrorRow colSpan={3} />
            ) : data?.content.length === 0 ? (
              <TableEmptyRow
                colSpan={3}
                searchKeyword={search || undefined}
                onResetSearch={() => handleSearchChange("")}
              />
            ) : (
              data?.content.map((item) => (
                <TableRow
                  key={item.id}
                  className="cursor-pointer hover:bg-muted/50 transition-colors group"
                  onClick={() => navigate(`/xxx/${item.id}`)}
                >
                  <TableCell className="font-medium">
                    <TruncatedLink to={`/xxx/${item.id}`} value={item.name} className="max-w-xs" />
                  </TableCell>
                  <TableCell>{item.status}</TableCell>
                  <TableCell>
                    {/* 행 작업: 행 이동을 막고, 초점이 와도 보인다(WD-412) */}
                    <div className="opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity flex gap-1 justify-end">
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={`${item.name} 수정`}
                        onClick={(e) => {
                          e.stopPropagation();
                          navigate(`/xxx/${item.id}/edit`);
                        }}
                      >
                        <Pencil className="h-4 w-4" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>

      {/* 페이지네이션 — totalElements·pageSize 를 함께 넘기면 건수도 보이고 읽어 준다(WD-931) */}
      <SimplePagination
        page={page}
        totalPages={totalPages}
        onPageChange={setPage}
        totalElements={data?.totalElements}
        pageSize={PAGE_SIZE}
      />
    </div>
  );
}
```

> 상세로 가는 키보드 경로는 이름의 `TruncatedLink`(`@/components/TruncatedLink`)이고 행 `onClick` 은 마우스 편의일 뿐이라, 행에 `role`·`tabIndex` 를 주지 않는다(표 행 의미 유지, WD-816). 행 이동에 `state` 를 넘기면 링크에도 같은 값을 넘긴다.
>
> 쪽 수 기억: 쪽을 넘기면 새 쪽 조회가 끝나기 전(로딩)이나 실패했을 때 `data` 가 비어 `totalPages` 가 0 이 되고 `SimplePagination` 이 사라져 사용자가 이동할 수 없게 된다. 그래서 마지막 성공 응답의 `totalPages` 를 기억해 쪽 이동을 그대로 남긴다(실패 갈래는 `PipelineListPage`, WD-956). 검색·필터가 바뀌면 다른 결과의 쪽 수이므로 기억한 값을 지운다. 건수 줄(「총 N건 중 a-b」)은 지금 쪽의 범위라 `data` 가 없는 동안 숨는 것이 맞다 — 기억하지 않는다. 이동 중 이전 행까지 남기려면 조회 훅에 `placeholderData: keepPreviousWhenOnlyPageChanged(...)`(WD-932, `AuditLogListPage`)를 쓰고 표에 aria-busy 를 건다 — 실패하면 placeholder 가 버려지므로 쪽 수 기억은 그대로 필요하다.
>
> 범위 밖 쪽: 이 쪽의 마지막 항목이 지워져(나 또는 다른 사람) 재조회되면 `totalPages` 가 줄어 지금 쪽이 범위 밖이 된다(쪽을 URL 에 두는 화면은 북마크·뒤로 가기도). 그러면 응답이 빈 목록이라 「데이터가 없습니다」로 오해되므로, 범위 밖이면 마지막 쪽으로 옮기고 그동안은 로딩 행을 보인다(`DatasetListPage`, `UserListPage`, WD-146).
>
> 주의: 링크를 `overflow-hidden` 요소 안에 두면 초점 표시가 잘린다. 초점 링(`ring-2`)에는 그 요소에 `p-0.5 -m-0.5`(2px)가, 고대비 모드의 outline(WD-944)까지 남기려면 `p-1 -m-1`(4px)이 필요하다. 링크가 스스로 말줄임하므로 되도록 `overflow-hidden` 대신 `min-w-0` 을 쓴다.
>
> 폭 상한: 링크나 감싸는 요소에 둔다. `max-w-xs` 는 열이 적은 표의 기본값이고, 표가 넘치면 `components/ui/truncated-text.tsx` 주석의 컨테이너 비례 상한(WD-634)을 따른다(예: `PipelineListPage`).

**주요 변형 사례** (모두 이름은 상세 링크):

- `DatasetListPage`: 행 클릭 → 상세 페이지 이동. 태그 컬럼에 Badge 복수 표시.
- `PipelineListPage`: 상태 컬럼에 활성/비활성 Badge 표시.
- `QueryListPage`: 폴더 필터 추가. 행 클릭 → 쿼리 에디터 이동.
- `UserListPage`: 관리자 전용 페이지로 권한 가드 적용.

---

## B. Detail Page (상세 페이지)

단일 리소스의 정보를 탭으로 구분하여 표시하는 패턴이다.
헤더에 뒤로가기 + 편집/삭제 액션이 위치한다.

**실제 적용 페이지**: `DatasetDetailPage`, `ApiConnectionDetailPage`

**TSX 스켈레톤**:

```tsx
export default function XxxDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { data: item, isLoading } = useXxxDetail(id!);
  const deleteMutation = useDeleteXxx();

  const handleDelete = async () => {
    await deleteMutation.mutateAsync(id!);
    navigate("/xxx");
  };

  if (isLoading) return <Skeleton className="h-96" />;
  if (!item) return <div>찾을 수 없습니다</div>;

  return (
    <div className="space-y-6">
      {/* 뒤로가기 + 제목 + 액션 */}
      <div className="flex items-center gap-4">
        <Button variant="ghost" size="icon" onClick={() => navigate(-1)}>
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <div className="flex-1 min-w-0">
          <PageHeading className="truncate">{item.name}</PageHeading>
          <p className="text-sm text-muted-foreground">{item.description}</p>
        </div>
        <div className="flex gap-2 shrink-0">
          <Button variant="outline" onClick={() => navigate(`/xxx/${id}/edit`)}>
            편집
          </Button>
          <DeleteConfirmDialog
            title="삭제 확인"
            description={`"${item.name}"을(를) 삭제하면 복구할 수 없습니다.`}
            onConfirm={handleDelete}
            trigger={<Button variant="destructive">삭제</Button>}
          />
        </div>
      </div>

      {/* 탭 */}
      <Tabs defaultValue="info">
        <TabsList>
          <TabsTrigger value="info">정보</TabsTrigger>
          <TabsTrigger value="data">데이터</TabsTrigger>
          <TabsTrigger value="history">이력</TabsTrigger>
        </TabsList>

        <TabsContent value="info" className="mt-6">
          <Card>
            <CardHeader>
              <CardTitle>기본 정보</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              {/* 정보 필드 목록 */}
              <div className="grid grid-cols-2 gap-4 text-sm">
                <div>
                  <p className="text-muted-foreground">생성일</p>
                  <p className="font-medium">{formatDate(item.createdAt)}</p>
                </div>
                <div>
                  <p className="text-muted-foreground">상태</p>
                  <Badge variant="secondary">{item.status}</Badge>
                </div>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="data" className="mt-6">
          {/* 데이터 탭 내용 */}
        </TabsContent>
      </Tabs>
    </div>
  );
}
```

**주요 변형 사례**:

- `DatasetDetailPage`: 탭 구성 — 정보 / 컬럼 목록 / 미리보기 / 분석 쿼리. 컬럼 타입별 아이콘 표시.
- `ApiConnectionDetailPage`: 탭 구성 — 연결 정보 / 테스트 결과 / 연결된 데이터셋. 연결 테스트 버튼이 헤더에 추가됨.

---

## C. Form Page (폼 페이지)

리소스를 생성하거나 편집하는 폼 페이지 패턴이다.
`react-hook-form` + `zod` 조합으로 유효성 검사를 처리한다.

**실제 적용 페이지**: `DatasetCreatePage`, `ProfilePage`

**TSX 스켈레톤**:

```tsx
const schema = z.object({
  name: z.string().min(1, "이름은 필수입니다"),
  description: z.string().optional(),
});

type FormValues = z.infer<typeof schema>;

export default function XxxFormPage() {
  const navigate = useNavigate();
  const createMutation = useCreateXxx();

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ resolver: zodResolver(schema) });

  const onSubmit = async (values: FormValues) => {
    await createMutation.mutateAsync(values);
    navigate("/xxx");
  };

  return (
    <div className="space-y-6">
      {/* 페이지 제목 */}
      <PageHeading>새로 만들기</PageHeading>

      <Card>
        <CardHeader>
          <CardTitle>기본 정보</CardTitle>
          <CardDescription>필수 항목(*)을 모두 입력하세요.</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
            <FormField label="이름" error={errors.name?.message} required>
              <Input
                {...register("name")}
                placeholder="이름을 입력하세요"
              />
            </FormField>

            <FormField label="설명" error={errors.description?.message}>
              <Textarea
                {...register("description")}
                placeholder="설명을 입력하세요 (선택)"
                rows={3}
              />
            </FormField>

            {/* 폼 액션 */}
            <div className="flex justify-end gap-2 pt-2">
              <Button
                type="button"
                variant="outline"
                onClick={() => navigate(-1)}
              >
                취소
              </Button>
              <Button type="submit" disabled={isSubmitting}>
                {isSubmitting ? "저장 중..." : "저장"}
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
```

**주요 변형 사례**:

- `DatasetCreatePage`: 소스 타입 선택(파일 업로드 / API 연결 / 직접 입력)에 따라 폼 필드가 동적으로 변경된다. 멀티 스텝 폼 구조 고려 가능.
- `ProfilePage`: 생성이 아닌 편집 폼으로, 기존 값을 `defaultValues`로 주입한다. 비밀번호 변경 섹션이 별도 Card로 분리되어 있다.

**현재(As-Is) 주의사항**:

```tsx
// As-Is: 에러 메시지를 각 컴포넌트 안에서 직접 렌더링
// (색은 시맨틱 토큰으로 적었다 — 이 예시의 논점은 "인라인 vs FormField" 이지 색이 아닌데,
//  border-red-500 처럼 P1 에서 제거한 하드코딩 팔레트를 예시로 남기면 그게 다시 유입된다)
<div>
  <Input {...register("name")} className={errors.name ? "border-destructive" : ""} />
  {errors.name && <p className="text-[0.8rem] font-medium text-destructive">{errors.name.message}</p>}
</div>

// To-Be: FormField 컴포넌트로 통일
<FormField label="이름" error={errors.name?.message} required>
  <Input {...register("name")} />
</FormField>
```

---

## D. Editor Page (에디터 페이지)

코드/쿼리/설정을 편집하는 전용 에디터 패턴이다.
뷰포트 전체 높이를 사용하며, 헤더 + 좌측 설정 패널 + 우측 편집 영역으로 구성된다.

**실제 적용 페이지**: `PipelineEditorPage`, `QueryEditorPage`, `ChartBuilderPage`

**TSX 스켈레톤**:

```tsx
export default function XxxEditorPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [isDirty, setIsDirty] = useState(false);

  return (
    <div className="flex flex-col h-[calc(100vh-120px)]">
      {/* 에디터 헤더 */}
      <div className="flex items-center h-12 px-4 border-b shrink-0 gap-3">
        <Button variant="ghost" size="icon" onClick={() => navigate(-1)}>
          <ArrowLeft className="h-4 w-4" />
        </Button>
        <span className="font-medium truncate">{title}</span>
        {isDirty && (
          <span className="text-xs text-muted-foreground">저장되지 않은 변경사항</span>
        )}
        <div className="ml-auto flex gap-2">
          <Button variant="outline" size="sm">실행</Button>
          <Button size="sm" disabled={!isDirty}>저장</Button>
        </div>
      </div>

      {/* 분할 패널 */}
      <div className="flex-1 flex min-h-0">
        {/* 좌측 설정 패널 */}
        <div className="w-64 border-r overflow-y-auto p-4 shrink-0">
          <div className="space-y-4">
            {/* 설정 항목들 */}
          </div>
        </div>

        {/* 우측 메인 편집 영역 */}
        <div className="flex-1 overflow-auto flex flex-col">
          {/* 편집기 / 미리보기 */}
        </div>
      </div>
    </div>
  );
}
```

**주요 변형 사례**:

- `PipelineEditorPage`: 좌측 패널 — 노드 팔레트. 우측 — ReactFlow 캔버스. 하단 — 실행 로그 패널 (토글 가능).
- `QueryEditorPage`: 좌측 패널 — 데이터셋/컬럼 탐색기. 우측 상단 — SQL 에디터(CodeMirror). 우측 하단 — 결과 테이블. 수직 분할 구조.
- `ChartBuilderPage`: 좌측 — 차트 타입 선택 + 축 매핑 설정. 우측 — 차트 미리보기. 상단에 쿼리 선택 드롭다운 추가.

**높이 계산 기준**:

```tsx
// 100vh에서 AppShell 헤더(64px)와 여백(56px)을 제외
h-[calc(100vh-120px)]

// 현재(As-Is): 페이지마다 이 값이 다르게 하드코딩되어 있음
// To-Be: CSS 변수 또는 레이아웃 컨텍스트로 통일 권장
```

---

## E. Auth Page (인증 페이지)

로그인/회원가입 전용 레이아웃이다.
AppShell 없이 독립적으로 렌더링되며, 화면 중앙 Card 형태로 표시된다.

**실제 적용 페이지**: `LoginPage`, `SignupPage`

**TSX 스켈레톤**:

```tsx
export default function AuthPage() {
  return (
    <div className="flex min-h-screen items-center justify-center px-4 bg-background">
      <Card className="w-full max-w-md">
        <CardHeader className="text-center space-y-1">
          {/* 로고 (선택) */}
          <div className="flex justify-center mb-4">
            <img src="/logo.svg" alt="Smart Fire Hub" className="h-10" />
          </div>
          {/* `CardTitle` 은 `<div>` 를 렌더하므로(components/ui/card.tsx) 그 자체로는 heading 이
              아니다. 인증 화면은 카드가 곧 페이지라 문서 개요에 h1 이 하나는 있어야 하므로
              CardTitle 대신 h1 을 직접 쓴다 (#433). */}
          <h1 className="text-2xl leading-8 font-semibold tracking-tight">로그인</h1>
          <CardDescription>계정에 로그인하세요</CardDescription>
        </CardHeader>

        <CardContent>
          <form className="space-y-4">
            <FormField label="이메일" error={errors.email?.message} required>
              <Input
                type="email"
                {...register("email")}
                placeholder="name@example.com"
                autoComplete="email"
              />
            </FormField>

            <FormField label="비밀번호" error={errors.password?.message} required>
              <Input
                type="password"
                {...register("password")}
                autoComplete="current-password"
              />
            </FormField>

            <Button type="submit" className="w-full" size="lg" disabled={isSubmitting}>
              {isSubmitting ? "로그인 중..." : "로그인"}
            </Button>
          </form>
        </CardContent>

        <CardFooter className="flex justify-center">
          <p className="text-sm text-muted-foreground">
            계정이 없으신가요?{" "}
            <Link to="/signup" className="text-primary hover:underline">
              회원가입
            </Link>
          </p>
        </CardFooter>
      </Card>
    </div>
  );
}
```

**주요 변형 사례**:

- `LoginPage`: 에러 메시지를 폼 상단에 Alert 컴포넌트로 표시. 이메일/비밀번호 필드.
- `SignupPage`: 이름 + 이메일 + 비밀번호 + 비밀번호 확인 필드. 회원가입 완료 후 로그인 페이지로 리다이렉트.

---

## 패턴 선택 가이드

새 페이지 개발 시 아래 기준으로 템플릿을 선택한다.

| 상황 | 사용할 패턴 |
|------|-------------|
| 여러 리소스를 나열하고 검색/필터가 필요한 경우 | **A. List Page** |
| 단일 리소스의 상세 정보를 탭으로 표시하는 경우 | **B. Detail Page** |
| 리소스를 생성하거나 설정을 편집하는 경우 | **C. Form Page** |
| 코드/쿼리/시각화를 편집하는 전문 에디터 | **D. Editor Page** |
| 인증 관련 페이지 (AppShell 없음) | **E. Auth Page** |

---

## 현재(As-Is) 공통 문제점 및 개선 방향(To-Be)

| 현재 문제 | 권장 개선 방향 |
|-----------|---------------|
| 페이지별로 `space-y-6` / `space-y-4`가 일관되지 않게 사용됨 | List/Detail/Form 패턴은 `space-y-6`으로 통일 |
| 로딩 상태 처리가 페이지마다 다름 (null 반환, Spinner, Skeleton 혼용) | 테이블은 `TableSkeletonRows`, 단일 리소스는 `Skeleton` 컴포넌트로 통일 |
| 에디터 페이지의 뷰포트 높이 계산값이 페이지마다 다르게 하드코딩됨 | CSS 변수 `--content-height`로 AppShell에서 주입하는 방식으로 통일 |
| `navigate(-1)` vs `navigate("/목록경로")` 혼용 | Detail/Form 페이지의 취소/뒤로가기는 `navigate(-1)` 우선, 최상위 진입 시는 경로 지정 |
| 삭제 확인 없이 바로 삭제하는 케이스 존재 | 모든 삭제 액션은 `DeleteConfirmDialog`를 거치도록 통일 |
