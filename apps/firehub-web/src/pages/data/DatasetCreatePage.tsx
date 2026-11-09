import { zodResolver } from '@hookform/resolvers/zod';
import { type FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { FormProvider, useForm } from 'react-hook-form';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';

import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card } from '../../components/ui/card';
import { FormField } from '../../components/ui/form-field';
import { Input } from '../../components/ui/input';
import { SearchableSelect } from '../../components/ui/searchable-select';
import { useCategories, useCreateDataset, useDatasets } from '../../hooks/queries/useDatasets';
import { useDataStoreFeatures } from '../../hooks/queries/useDataStoreCapabilities';
import { useRhfDirtyReader } from '../../hooks/useRhfDirtyReader';
import { useUnsavedChangesGuard } from '../../hooks/useUnsavedChangesGuard';
import { extractApiError, handleApiError } from '../../lib/api-error';
import { getOriginTypeLabel, getStorageTypeLabel } from '../../lib/formatters';
import type { CreateDatasetFormData } from '../../lib/validations/dataset';
import { createDatasetSchema, makeCreateDatasetSchema } from '../../lib/validations/dataset';
import { MAX_DESCRIPTION_LENGTH } from '../../lib/validations/description';
import { SchemaBuilder } from './components/SchemaBuilder';

export default function DatasetCreatePage() {
  const { data: categoriesData } = useCategories();
  const createDataset = useCreateDataset();

  const categories = categoriesData || [];

  // 유형은 더 이상 폼에서 선택하지 않고 URL 쿼리(생성 유형 선택 모달에서 전달)로 고정한다.
  const [searchParams] = useSearchParams();
  const storageType = (() => {
    const s = searchParams.get('storageType');
    return s === 'DOCUMENT' || s === 'FILE' ? s : 'TABLE';
  })() as 'TABLE' | 'DOCUMENT' | 'FILE';
  // TEMP 는 파이프라인 러너만 만드는 예약 출처라 서버가 400(DATASET_ORIGIN_RESERVED)으로 거부한다 — URL 로 들어와도 SOURCE 로 취급한다.
  const originType = (() => {
    const o = searchParams.get('originType');
    return o === 'DERIVED' ? o : 'SOURCE';
  })() as 'SOURCE' | 'DERIVED';
  // DOCUMENT/FILE 유형 여부 — 칼럼·테이블명 UI 토글에 사용 (URL에서 파생되므로 폼 watch 불필요)
  const isDocument = storageType === 'DOCUMENT';
  const isFile = storageType === 'FILE';
  // 동적 테이블/컬럼이 없는 스키마리스 유형 통합 플래그
  const isSchemaless = isDocument || isFile;

  // WD-636: 칼럼 VARCHAR 길이 상한은 저장소 기능 응답을 따른다(PG 10000, Tibero 4000 — 조회 전·실패 시 10000).
  // 응답이 폼 생성 뒤에 와도 react-hook-form 은 매 렌더의 resolver 를 쓰므로 새 상한이 다음 제출부터 적용된다.
  const { maxVarcharLength } = useDataStoreFeatures();
  const formSchema = useMemo(() => makeCreateDatasetSchema(maxVarcharLength), [maxVarcharLength]);
  // 기본값은 한 번만 만든다 (WD-642) — tableName 에 Date.now() 가 들어가 렌더마다 새로 만들면 매번 값이 달라진다.
  const [defaultValues] = useState<CreateDatasetFormData>(() => ({
    name: '',
    // DOCUMENT/FILE은 동적 테이블/컬럼이 없어 tableName을 규칙([a-z][a-z0-9_]*) 만족하는 메타 식별자로 자동 생성
    tableName: isFile ? `file_${Date.now()}` : isDocument ? `doc_${Date.now()}` : '',
    description: '',
    categoryId: undefined,
    storageType,
    originType,
    columns: isSchemaless
      ? []
      : [
          {
            columnName: '',
            displayName: '',
            dataType: 'TEXT',
            isNullable: true,
            isIndexed: false,
            isPrimaryKey: false,
            description: '',
            // VARCHAR → 다른 타입 전환 시 SchemaBuilder 가 maxLength 를 undefined 로 되돌린다 (WD-577).
            maxLength: undefined,
          },
        ],
  }));

  const form = useForm<CreateDatasetFormData>({
    mode: 'onSubmit',
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(formSchema) as any,
    defaultValues,
  });

  // 이 화면 인스턴스가 아직 살아 있는가 (WD-642) — 이탈 후 같은 생성 화면으로 돌아오면 새 인스턴스가 뜨므로,
  // 옛 인스턴스의 늦은 저장 응답은 이 값이 false 라 새 화면을 건드리지 못한다.
  // StrictMode 의 마운트→정리→재마운트에서도 맞도록 초기값이 아니라 effect 본문에서 true 로 둔다.
  const aliveRef = useRef(false);
  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
    };
  }, []);

  // 제출 잠금 (WD-642) — idle: 제출 가능 / pending: 검증·요청 진행 중 / created: 이미 만들어짐.
  // Cmd/Ctrl+S 의 requestSubmit 은 비활성 버튼과 무관하게 submit 을 일으키고 RHF handleSubmit 도 동시 실행을 막지 않아,
  // 진행 중 거듭 누르면 생성 요청이 여러 번 간다. 만들어진 뒤에도 상세로 넘어가는 라우터 전환 중이나
  // 확인창 선택을 기다리는 동안 화면이 남아 있으므로, created 는 풀지 않는다.
  const submitLockRef = useRef<'idle' | 'pending' | 'created'>('idle');
  // 이 화면에서 만든 데이터셋 (WD-662) — 상세 경로, 만들 때 이 생성 화면의 경로,
  // 확인창이 떠 있는 동안 만들어져 「취소」(남기) 때 상세로 보낼지(goOnStay, WD-642 에서 공용 훅에 있던 것을 옮김).
  const createdRef = useRef<{ detailPath: string; formPathname: string; goOnStay: boolean } | null>(null);
  const navigate = useNavigate();

  // 사용자가 아직 이 화면 인스턴스로 그 경로에 있는가 (WD-642, WD-662) — 인스턴스가 살아 있고 실제 주소가 같을 때만 참이다.
  // 마운트 여부만으로는 부족하다: react-router 가 이동을 startTransition 으로 처리해, 주소는 이미 바뀌었어도
  // 다음 화면(lazy 라우트)이 준비될 때까지 이 페이지가 남아 저장 응답·Cmd/Ctrl+S·확인창 이벤트를 받는 틈이 있다.
  const isStillOn = useCallback(
    (pathname: string) => aliveRef.current && window.location.pathname === pathname,
    [],
  );

  // 확인창에서 남기를 골랐을 때 (WD-662) — 그 사이 끝난 저장이 있으면 결과(상세) 화면으로 보낸다.
  // 저장된 입력을 그대로 두면 같은 입력으로 다시 만들게 된다. 표식은 한 번 쓰고 비우며, 이미 다른 주소면 비우기만 한다.
  // useCallback: 공용 훅의 onStay 동기화 effect 가 매 렌더 돌지 않게.
  const handleStay = useCallback(() => {
    const created = createdRef.current;
    if (!created?.goOnStay) return;
    created.goOnStay = false;
    if (isStillOn(created.formPathname)) navigate(created.detailPath);
  }, [isStillOn, navigate]);

  // 미저장 입력 판정 (WD-642) — 가드가 물을 때만 현재 값을 기본값과 직접 비교한다(useRhfDirtyReader 참고).
  // shouldDirty 를 빠뜨린 setValue 도 잡히고, URL 쿼리(storageType/originType)로 정해진 초기값만으로는 dirty 가 되지 않는다.
  // (WD-676) 저장값 기준선은 훅이 갖는다 — 저장 뒤 폼 초기화는 form.reset 대신 resetToSaved 로 해야 기준선이 함께 바뀐다.
  const { readIsDirty, resetToSaved } = useRhfDirtyReader(form);

  // 미저장 입력 이탈 가드 (WD-577) — 이름·테이블명·설명·카테고리 입력이나 칼럼 추가·삭제·편집이 있으면
  // 사이드바 링크·브라우저 뒤로가기·새로고침/탭 닫기·취소 버튼에서 확인 다이얼로그를 띄운다.
  // (WD-662) 확인창에서 남기를 고르면 handleStay 가 그 사이 끝난 저장의 상세로 보낸다.
  const { dialog: unsavedChangesDialog, requestNavigate, isDialogOpen } = useUnsavedChangesGuard(readIsDirty, {
    onStay: handleStay,
  });

  // 폼 ref — Cmd/Ctrl+S 단축키에서 submit 트리거에 사용 (#100)
  const formRef = useRef<HTMLFormElement>(null);

  // 이름·테이블명 중복 검증을 위한 debounce 처리값 (#103).
  // 사용자가 입력을 멈추고 약 400ms 경과 후에만 검색 쿼리를 트리거하여 키 입력마다 API가 폭주하지 않도록 함.
  const watchedName = form.watch('name');
  const watchedTableName = form.watch('tableName');
  const [debouncedName, setDebouncedName] = useState('');
  const [debouncedTableName, setDebouncedTableName] = useState('');

  useEffect(() => {
    const t = setTimeout(() => setDebouncedName(watchedName?.trim() ?? ''), 400);
    return () => clearTimeout(t);
  }, [watchedName]);

  useEffect(() => {
    const t = setTimeout(() => setDebouncedTableName(watchedTableName?.trim() ?? ''), 400);
    return () => clearTimeout(t);
  }, [watchedTableName]);

  // 백엔드 search 파라미터로 부분 일치 결과를 받아 클라이언트에서 정확 일치를 검사한다 (#103).
  // 전용 check-name 엔드포인트가 없는 현재 환경에서 useDatasets 결과를 활용해 중복 여부를 판정.
  const { data: nameSearchData } = useDatasets({
    search: debouncedName,
    size: 20,
  });
  const { data: tableNameSearchData } = useDatasets({
    search: debouncedTableName,
    size: 20,
  });

  const isNameDuplicate =
    debouncedName.length > 0 &&
    !!nameSearchData?.content?.some((d) => d.name.toLowerCase() === debouncedName.toLowerCase());
  const isTableNameDuplicate =
    debouncedTableName.length > 0 &&
    !!tableNameSearchData?.content?.some(
      (d) => d.tableName.toLowerCase() === debouncedTableName.toLowerCase()
    );
  // (#644) 폼이 mode: 'onSubmit'이라 formState.errors.tableName은 최초 제출 전까지 비어있다.
  // 입력 중 "사용 가능" 메시지가 형식 오류를 무시하고 표시되는 것을 막기 위해
  // 스키마의 tableName 규칙(형식·길이 상한)을 직접 재검사한다 — 규칙을 여기 따로 베끼면 둘이 어긋난다.
  const isTableNameFormatValid = createDatasetSchema.shape.tableName.safeParse(debouncedTableName).success;

  // 전역 Cmd/Ctrl+S 단축키로 폼 저장 (#100).
  // 브라우저 기본 "페이지 저장" 다이얼로그를 preventDefault하고 requestSubmit으로 폼 검증을 거쳐 onSubmit 실행.
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && (e.key === 's' || e.key === 'S')) {
        e.preventDefault();
        formRef.current?.requestSubmit();
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  const onSubmit = async (data: CreateDatasetFormData) => {
    // 제출 시점 경로 — 생성 요청 중 사용자가 이탈을 확정해 다른 화면으로 갔다면 응답 후 상세로 끌고 가지 않으려고 기억한다 (WD-577, isStillOn 참고).
    const submitPathname = window.location.pathname;
    // 이 제출이 낡았는가 — 화면 인스턴스가 사라졌거나(이탈 후 같은 화면으로 돌아온 경우 포함) 주소가 바뀌었으면 낡았다 (WD-642)
    const isStale = () => !isStillOn(submitPathname);
    // 중복 인라인 검증 결과가 있으면 제출 차단 (#103) — 서버 왕복으로 알게 되는 비용을 줄임
    if (isNameDuplicate) {
      form.setError('name', { type: 'duplicate', message: '이미 사용 중인 이름입니다.' });
      return;
    }
    // DOCUMENT/FILE은 tableName을 자동 생성하므로 중복 검사에서 제외한다.
    if (!isSchemaless && isTableNameDuplicate) {
      form.setError('tableName', { type: 'duplicate', message: '이미 사용 중인 테이블명입니다.' });
      return;
    }
    try {
      const result = await createDataset.mutateAsync({
        name: data.name,
        tableName: data.tableName,
        description: data.description || undefined,
        categoryId: data.categoryId || undefined,
        storageType: data.storageType,
        originType: data.originType,
        // FILE 유형의 저장 경로(bucket/prefix)는 보내지 않는다 — 서버가 데이터셋마다 datasets/<id>/ 로 만든다
        // (클라이언트 지정 prefix 는 다른 데이터셋 경로를 덮을 수 있어 서버가 400 으로 거부한다).
        columns: data.columns.map((col) => ({
          columnName: col.columnName,
          displayName: col.displayName || undefined,
          dataType: col.dataType,
          maxLength: col.maxLength ?? undefined,
          isNullable: col.isNullable,
          isIndexed: col.isIndexed,
          isPrimaryKey: col.isPrimaryKey,
          description: col.description || undefined,
        })),
      });
      // 이후 이 화면에서의 재제출을 막는다 — 다시 누르면 만든 데이터셋으로 이동을 재시도한다 (WD-662)
      submitLockRef.current = 'created';
      // 이탈 후 응답이 와도 서버에는 만들어졌으므로 토스트는 그대로 알린다.
      toast.success('데이터셋이 생성되었습니다.');
      // 응답 사이 이미 이탈했으면(주소가 바뀜·화면 인스턴스가 바뀜) 사용자가 있는 화면을 건드리지 않는다 (WD-577, WD-642).
      if (isStale()) return;
      // 떠나지 않았을 때만 기억한다 — 떠난 뒤(라우터 전환 중 남은 화면)의 재제출이 사용자를 상세로 끌고 가지 않게 (WD-662)
      const detailPath = `/data/datasets/${result.data.id}`;
      createdRef.current = { detailPath, formPathname: submitPathname, goOnStay: isDialogOpen() };
      // 저장한 값으로 폼을 초기화해 dirty 를 끈다 (WD-577) — 이제 잃을 입력이 없다. 기준선도 함께 바꾼다 (WD-676).
      resetToSaved(data);
      // 상세로 이동한다. 이탈 확인창이 떠 있으면 끌고 가지 않고 사용자의 선택을 기다린다 (WD-642, WD-662) —
      // 「이탈」이면 가려던 곳으로(WD-577 의 "이탈 선택 → 상세로 가지 않음"과 같다), 「취소」면 handleStay 가 만든 데이터셋 상세로 보낸다.
      if (!createdRef.current.goOnStay) navigate(detailPath);
    } catch (error) {
      // 떠난 화면의 저장 실패도 알린다 (WD-642) — 성공은 떠난 뒤에도 알리므로 실패만 숨기면 만들어졌는지 알 수 없다.
      // 다른 화면 위에 뜨므로 어느 데이터셋의 일인지와 저장되지 않았다는 결과를 함께 적는다.
      // (WD-662) 서버가 사유(ErrorResponse)를 주면 덧붙인다 — 왜 실패했는지 알아야 돌아가 고칠 수 있다. 없으면 지금 문구 그대로.
      if (isStale()) {
        const reason = extractApiError(error, '');
        const base = `데이터셋 「${data.name}」 생성에 실패했습니다 — 저장되지 않았습니다.`;
        // 사유 자체에 「(외 2건)」 같은 괄호가 있을 수 있어 괄호로 감싸지 않고 「 — 사유: 」로 잇는다
        toast.error(reason ? `${base} — 사유: ${reason}` : base);
        return;
      }
      handleApiError(error, '데이터셋 생성에 실패했습니다.');
    }
  };

  // RHF 제출 핸들러(검증 → onSubmit) — 실패 시 첫 오류 필드로 이동한다 (#69)
  const runSubmit = form.handleSubmit(onSubmit, (errors) => {
    // 에러가 있는 첫 번째 필드를 찾아 focus + scrollIntoView
    const firstErrorKey = Object.keys(errors)[0];
    if (firstErrorKey) {
      const el = document.querySelector<HTMLElement>(
        `[name="${firstErrorKey}"], #${firstErrorKey}`
      );
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'center' });
        el.focus({ preventScroll: true });
      }
    }
  });

  // 폼 submit 진입점 (WD-642) — 저장이 진행 중이거나 이미 만들어졌으면 새 제출을 무시한다.
  // 표식은 검증 전에 동기로 세워, 비동기 zod 검증이 도는 사이 들어온 두 번째 제출도 막는다.
  // 이미 만들어진 경우: 상세로 넘어가는 전환 중이거나 확인창 선택을 기다리는 중이다 — Cmd/Ctrl+S 는 모달 뒤에서도 window 로 들어온다.
  const handleFormSubmit = async (e: FormEvent<HTMLFormElement>) => {
    if (submitLockRef.current !== 'idle') {
      e.preventDefault();
      // (WD-662) 이미 만들어졌고 아직 생성 화면에 있으면 무반응 대신 알리고 상세로 보낸다(다시 입력했으면 requestNavigate 가 확인창).
      // createdRef 는 떠나지 않은 성공에서만 생기므로, 떠난 뒤 성공·진행 중(pending)은 여기 오지 않고 그냥 막힌다.
      const created = createdRef.current;
      if (created && !isDialogOpen() && isStillOn(created.formPathname)) {
        toast.info('이미 만들어졌습니다. 만든 데이터셋으로 이동합니다.');
        requestNavigate(created.detailPath);
      }
      return;
    }
    submitLockRef.current = 'pending';
    try {
      await runSubmit(e);
    } catch (error) {
      // onSubmit 이 API 오류를 모두 처리하므로 여기 오는 것은 예기치 못한 오류뿐이다 — 처리되지 않은 rejection 으로 남기지 않는다
      console.error('데이터셋 생성 제출 중 예기치 못한 오류', error);
    } finally {
      // 검증 실패·요청 실패면 다시 제출할 수 있게 푼다. 만들어졌으면(created) 그대로 둔다.
      if (submitLockRef.current === 'pending') submitLockRef.current = 'idle';
    }
  };

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">새 데이터셋</h1>
      </div>

      <FormProvider {...form}>
        {/*
         * 폼 검증 실패 시 인라인 에러 메시지만 노출한다 (#69).
         * 과거에는 toast.error로도 동일한 안내를 띄웠으나, 인라인 에러와
         * 중복되어 사용자 시선을 분산시키고 어떤 필드가 잘못됐는지 알리지 못해 제거.
         * 대신 첫 번째 에러 필드 input으로 자동 스크롤·focus 이동시켜 사용자가
         * 즉시 어떤 필드를 수정해야 하는지 인지할 수 있도록 한다.
         * 단, 서버 에러(API 실패)는 onSubmit catch의 handleApiError로 toast 노출 유지.
         */}
        {/* noValidate(WD-636): 숫자 칸의 min/max 를 브라우저가 먼저 막으면 영어 말풍선만 뜨고 zod 의 한국어 인라인 오류(「최대 4000」)가 가려진다. */}
        <form ref={formRef} noValidate onSubmit={handleFormSubmit} className="space-y-6">
          <Card className="p-6">
            <h2 className="text-xl leading-7 font-semibold mb-4">기본 정보</h2>
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <FormField
                  label="데이터셋 이름"
                  htmlFor="name"
                  required
                  error={
                    form.formState.errors.name?.message ??
                    (isNameDuplicate ? '이미 사용 중인 이름입니다.' : undefined)
                  }
                >
                  <Input
                    id="name"
                    {...form.register('name')}
                    placeholder="예: 사용자 데이터"
                    aria-invalid={isNameDuplicate || !!form.formState.errors.name}
                  />
                  {/* 중복 검사 통과 시 양호 표시 (#103) */}
                  {!isNameDuplicate && debouncedName.length > 0 && !form.formState.errors.name && (
                    <p className="text-xs text-muted-foreground mt-1">사용 가능한 이름입니다.</p>
                  )}
                </FormField>

                {/* DOCUMENT/FILE 유형은 tableName을 자동 생성하므로 입력 필드를 숨긴다 */}
                {!isSchemaless && (
                  <FormField
                    label="테이블명"
                    htmlFor="tableName"
                    required
                    error={
                      form.formState.errors.tableName?.message ??
                      (isTableNameDuplicate ? '이미 사용 중인 테이블명입니다.' : undefined)
                    }
                  >
                    <Input
                      id="tableName"
                      {...form.register('tableName')}
                      placeholder="예: user_data"
                      className="font-mono"
                      aria-invalid={isTableNameDuplicate || !!form.formState.errors.tableName}
                    />
                    {!isTableNameDuplicate &&
                      debouncedTableName.length > 0 &&
                      isTableNameFormatValid &&
                      !form.formState.errors.tableName && (
                        <p className="text-xs text-muted-foreground mt-1">사용 가능한 테이블명입니다.</p>
                      )}
                  </FormField>
                )}
              </div>

              <FormField label="설명" htmlFor="description" error={form.formState.errors.description?.message}>
                <Input
                  id="description"
                  {...form.register('description')}
                  placeholder="데이터셋 설명"
                  maxLength={MAX_DESCRIPTION_LENGTH}
                  aria-invalid={!!form.formState.errors.description}
                />
              </FormField>

              <div className="grid grid-cols-2 gap-4">
                {/* 카테고리는 사용자 정의로 늘어날 수 있어 검색 가능한 SearchableSelect로 대체 (#104) */}
                <FormField label="카테고리" htmlFor="categoryId">
                  <SearchableSelect
                    id="categoryId"
                    value={form.watch('categoryId')?.toString()}
                    onChange={(value) => {
                      // 카테고리 선택도 미저장 입력이다 — 가드는 값 비교라 shouldDirty 없이도 잡힌다 (WD-642)
                      form.setValue('categoryId', value === undefined ? undefined : Number(value));
                    }}
                    options={categories.map((cat) => ({
                      value: cat.id.toString(),
                      label: cat.name,
                    }))}
                    placeholder="카테고리 선택"
                    searchPlaceholder="카테고리 검색..."
                    emptyText="일치하는 카테고리가 없습니다."
                    allowClear
                    clearLabel="선택 안 함"
                  />
                </FormField>

                {/* 유형은 생성 유형 선택 모달에서 정해 URL로 고정되므로 폼에서 변경 불가 — 읽기 전용으로 표시 */}
                <FormField label="데이터셋 유형" htmlFor="datasetTypeDisplay">
                  <div id="datasetTypeDisplay" className="flex items-center gap-2 h-9">
                    <Badge variant="secondary">{getStorageTypeLabel(storageType)}</Badge>
                    <Badge variant="secondary">{getOriginTypeLabel(originType)}</Badge>
                  </div>
                </FormField>
              </div>
            </div>
          </Card>

          {/* DOCUMENT/FILE 유형은 동적 칼럼이 없으므로 칼럼 정의 카드를 숨긴다 */}
          {!isSchemaless && (
            <Card className="p-6">
              <h2 className="text-xl leading-7 font-semibold mb-4">칼럼 정의</h2>
              <SchemaBuilder />
            </Card>
          )}

          <div className="flex gap-2">
            <Button
              type="submit"
              disabled={form.formState.isSubmitting || isNameDuplicate || isTableNameDuplicate}
            >
              {form.formState.isSubmitting ? '만드는 중...' : '만들기'}
            </Button>
            <Button
              type="button"
              variant="outline"
              // (WD-577) navigate() 직접 호출은 이탈 가드(<a> 클릭 캡처)를 우회하므로 requestNavigate 로 이동한다.
              onClick={() => requestNavigate('/data/datasets')}
            >
              취소
            </Button>
          </div>
        </form>
      </FormProvider>
      {/* 미저장 입력 이탈 확인 다이얼로그 (WD-577) — useUnsavedChangesGuard 가 렌더 */}
      {unsavedChangesDialog}
    </div>
  );
}
