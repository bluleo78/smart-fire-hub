# 05. 페이지 레이아웃 패턴

Smart Fire Hub 프론트엔드(`apps/firehub-web`)에서 반복되는 페이지 레이아웃 구조를 5가지 템플릿으로 정의한다.
새 페이지를 개발할 때 아래 템플릿 중 가장 가까운 것을 기반으로 작성한다.

---

## A. List Page (목록 페이지)

검색/필터 + 테이블 + 페이지네이션으로 구성되는 가장 일반적인 패턴이다.

**실제 적용 페이지**: `DatasetListPage`, `PipelineListPage`, `QueryListPage`, `UserListPage`

**TSX 스켈레톤**:

```tsx
export default function XxxListPage() {
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const { data, isLoading } = useXxxList({ page, search });

  return (
    <div className="space-y-6">
      {/* 페이지 헤더 */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">페이지 제목</h1>
        <Button>
          <Plus className="h-4 w-4 mr-2" />
          새로 만들기
        </Button>
      </div>

      {/* 검색/필터 툴바 */}
      <div className="flex items-center gap-3 flex-wrap">
        <SearchInput
          placeholder="검색..."
          className="w-64"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <Select>
          <SelectTrigger className="w-36">
            <SelectValue placeholder="상태" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">전체</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* 데이터 테이블 */}
      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>컬럼 A</TableHead>
              <TableHead>컬럼 B</TableHead>
              <TableHead className="w-[100px]">액션</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableSkeleton rows={10} columns={3} />
            ) : data?.content.length === 0 ? (
              <TableEmpty colSpan={3} message="데이터가 없습니다" />
            ) : (
              data?.content.map((item) => (
                <TableRow
                  key={item.id}
                  className="cursor-pointer hover:bg-muted/50 transition-colors group"
                  onClick={() => navigate(`/xxx/${item.id}`)}
                >
                  <TableCell className="font-medium">{item.name}</TableCell>
                  <TableCell>{item.status}</TableCell>
                  <TableCell>
                    <div className="opacity-0 group-hover:opacity-100 transition-opacity flex gap-1 justify-end">
                      <Button variant="ghost" size="icon-sm">
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

      {/* 페이지네이션 */}
      <SimplePagination
        page={page}
        totalPages={data?.totalPages ?? 0}
        onPageChange={setPage}
      />
    </div>
  );
}
```

**주요 변형 사례**:

- `DatasetListPage`: 테이블 행 클릭 → 상세 페이지 이동. 태그 컬럼에 Badge 복수 표시.
- `PipelineListPage`: 상태 컬럼에 실행 상태 Badge + 마지막 실행 시간 표시.
- `QueryListPage`: 쿼리 타입 필터 추가. 행 클릭 → 쿼리 에디터 이동.
- `UserListPage`: 역할(Role) 필터 추가. 관리자 전용 페이지로 권한 가드 적용.

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
          <h1 className="text-2xl font-bold truncate">{item.name}</h1>
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
      <h1 className="text-2xl font-bold">새로 만들기</h1>

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
<div>
  <Input {...register("name")} className={errors.name ? "border-red-500" : ""} />
  {errors.name && <p className="text-sm text-red-500">{errors.name.message}</p>}
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
          <CardTitle className="text-2xl font-bold">로그인</CardTitle>
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
| 로딩 상태 처리가 페이지마다 다름 (null 반환, Spinner, Skeleton 혼용) | 테이블은 `TableSkeleton`, 단일 리소스는 `Skeleton` 컴포넌트로 통일 |
| 에디터 페이지의 뷰포트 높이 계산값이 페이지마다 다르게 하드코딩됨 | CSS 변수 `--content-height`로 AppShell에서 주입하는 방식으로 통일 |
| `navigate(-1)` vs `navigate("/목록경로")` 혼용 | Detail/Form 페이지의 취소/뒤로가기는 `navigate(-1)` 우선, 최상위 진입 시는 경로 지정 |
| 삭제 확인 없이 바로 삭제하는 케이스 존재 | 모든 삭제 액션은 `DeleteConfirmDialog`를 거치도록 통일 |
