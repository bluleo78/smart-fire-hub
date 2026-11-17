import { Download, Folder } from 'lucide-react';
import { useCallback, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';

import { Button } from '../../../components/ui/button';
import { InlineBanner } from '../../../components/ui/inline-banner';
import { SearchInput } from '../../../components/ui/search-input';
import { useDatasetPermissions } from '../../../hooks/queries/useDatasets';
import { type UploadResult, useObjectList, useUploadObjects } from '../../../hooks/queries/useObjects';
import { extractApiError } from '../../../lib/api-error';
import {
  folderBreadcrumbs,
  folderDisplayName,
  formatObjectDate,
  formatObjectSize,
  nameInFolder,
  openObjectInNewTab,
  parentFolderOf,
  toFolderParam,
} from '../../../lib/objectFile';
import { collectEntries, filesToItems, type UploadItem } from '../../../lib/uploadTree';

/** 검색어 입력이 멈춘 뒤 서버에 묻기까지 기다리는 시간(ms) — 타이핑마다 데이터셋 전체를 훑지 않게 한다. */
const SEARCH_DEBOUNCE_MS = 300;

/** 목록 그리드 열 — 이름 / 크기 / (sm 이상)수정일 / 다운로드. 모바일(<sm)은 수정일 칸을 빼 이름 칸이 0폭으로 눌리지 않게 한다. */
const ROW_GRID = 'grid-cols-[1fr_5rem_2.5rem] sm:grid-cols-[1fr_6rem_12rem_2.5rem]';

/**
 * 현재 폴더 경로 표시 — 「전체 › 제품 › Tibero7」(WD-761 A안).
 * 각 조각은 그 지점의 폴더로 이동하는 버튼이고, 마지막 조각은 현재 위치라 aria-current="page" 로 알린다.
 */
function ObjectBreadcrumb({ path, onNavigate }: { path: string; onNavigate: (path: string) => void }) {
  const crumbs = [{ name: '전체', path: '' }, ...folderBreadcrumbs(path)];
  return (
    <nav aria-label="폴더 경로" className="flex min-w-0 items-center gap-2 text-sm">
      <Folder className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      <ol className="flex min-w-0 flex-wrap items-center gap-1">
        {crumbs.map((c, i) => {
          const isCurrent = i === crumbs.length - 1;
          // 공백뿐인 폴더 이름은 빈 조각이 되지 않게 「(공백)」으로 보인다(WD-776).
          const label = folderDisplayName(c.name);
          return (
            <li key={c.path} className="flex min-w-0 items-center gap-1">
              {/* 구분자는 장식 — 조각 사이 관계는 목록 순서가 전한다. */}
              {i > 0 && (
                <span aria-hidden="true" className="text-muted-foreground">
                  ›
                </span>
              )}
              <button
                type="button"
                onClick={() => onNavigate(c.path)}
                aria-current={isCurrent ? 'page' : undefined}
                // 긴 폴더 이름은 잘려 보이므로 마우스를 올리면 전체 이름을 볼 수 있게 한다.
                title={label}
                className={`max-w-[16rem] truncate rounded px-1 hover:underline ${
                  isCurrent ? 'font-medium text-foreground' : 'text-muted-foreground'
                }`}
              >
                {label}
              </button>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

/**
 * FILE 데이터셋 오브젝트 브라우저 탭 — 업로드(드래그앤드롭) + S3 스타일 목록(이름/크기/수정일) + 무한스크롤.
 *
 * 폴더 탐색(WD-761 A안): 현재 폴더는 URL ?path=a/b(앞뒤 / 없이)에 두어 뒤로가기·직접 진입·공유가 되게 하고, 서버에 그대로 넘긴다.
 * 기본 목록은 그 폴더의 하위 폴더(먼저) + 파일이고, 폴더를 누르면 들어가며 경로 표시로 올라온다.
 * 이름 검색어가 있으면 데이터셋 전체를 서버가 부분 일치로 찾은 결과(전체 상대 경로)를 보이고, 결과를 누르면 그 파일이 있는 폴더로 간다.
 * 업로드는 지금 보는 폴더로 간다.
 *
 * 파일 이름 클릭은 inline(브라우저에서 열기), 다운로드 아이콘은 attachment(파일 저장) presign 을 요청한다 — attachment 는 서버가
 * 내보내기 정책을 판정하므로(S4) 조회자 기준 내보내기 불가(exportAllowed=false)면 아이콘을 숨긴다(보조 다운로드 → 숨김, 스펙 §5-4).
 */
export function DatasetObjectsTab({ datasetId, exportAllowed }: { datasetId: number; exportAllowed: boolean }) {
  const [searchParams, setSearchParams] = useSearchParams();
  // 현재 폴더 — URL 값을 앞뒤 / 없이 맞춘다. 가운데 값(.. 등)의 안전 판정은 서버(400)가 하고 화면은 그 오류를 보인다.
  const path = toFolderParam(searchParams.get('path'));
  // 검색어 — SearchInput 이 입력이 멈춘 뒤(300ms)에만 넘겨 주고 한글 조합 중에는 넘기지 않는다. URL 에는 두지 않는다(?path= 만 요구).
  const [search, setSearch] = useState('');
  // 폴더가 바뀌면(폴더 클릭·경로 표시·브라우저 뒤로가기 모두) 검색을 끝낸다 — 뒤로가기로 경로 표시·업로드 대상은 바뀌었는데
  // 목록만 지난 검색 결과로 남지 않게. 렌더 중 이전 값과 비교해 바로 맞추는 React 권장 방식(effect 한 번 늦게 그리지 않는다).
  const [searchFolder, setSearchFolder] = useState(path);
  if (searchFolder !== path) {
    setSearchFolder(path);
    setSearch('');
  }
  // 공백만인 검색어는 검색이 아니다(서버 계약과 같다).
  const q = search.trim();
  const isSearch = q.length > 0;

  // 검색 모드는 데이터셋 전체를 훑으므로 path 를 싣지 않는다 — 폴더를 옮겨도 같은 검색 캐시를 쓴다.
  const {
    data,
    isLoading,
    isError,
    error,
    hasNextPage,
    fetchNextPage,
    isFetchingNextPage,
    isFetchNextPageError,
    refetch,
    isRefetching,
  } = useObjectList(datasetId, isSearch ? { q } : { path });
  const upload = useUploadObjects(datasetId, path);
  const inputRef = useRef<HTMLInputElement>(null);
  const folderInputRef = useRef<HTMLInputElement | null>(null);
  const [dragOver, setDragOver] = useState(false);
  /**
   * 서버가 파일별 검증에서 거부한 파일과 이유(WD-776) — 첫 묶음 결과에서 따로 들어 「실패건 재시도」(upload.data 를 바꾼다) 뒤에도 남긴다.
   * 새 업로드를 시작하거나 사용자가 닫으면 비운다.
   */
  const [rejected, setRejected] = useState<{ folderPath: string; items: UploadResult['rejectedItems'] } | null>(null);
  // 업로드 URL 발급(POST upload-urls)은 dataset:write — 없으면 드롭존·폴더 선택·재시도를 숨기고 목록만 보인다 (WD-139)
  const { canWriteDataset } = useDatasetPermissions();

  /**
   * 폴더로 이동한다 — ?path= 만 바꾸고 다른 쿼리(?tab= 등)는 지우지 않게 함수형으로 갱신한다.
   * 기본 push 라 브라우저 뒤로가기가 이전 폴더로 돌아간다. 폴더 이동은 검색을 끝낸다(검색 결과에서 폴더로 가는 경우 포함).
   * 이미 그 폴더면 기록을 쌓지 않는다(경로 표시의 현재 조각 클릭 등).
   */
  const goToFolder = (next: string) => {
    setSearch('');
    if (next === path) return;
    setSearchParams((prev) => {
      const params = new URLSearchParams(prev);
      if (next) params.set('path', next);
      else params.delete('path');
      return params;
    });
  };

  // webkitdirectory는 표준 타입에 없어 JSX 속성으로 못 준다 → 인풋이 붙는 순간 콜백 ref로 부여(폴더 선택 인풋).
  const attachFolderInput = useCallback((el: HTMLInputElement | null) => {
    folderInputRef.current = el;
    el?.setAttribute('webkitdirectory', '');
  }, []);

  // 파일/폴더 선택 공통 처리 — webkitRelativePath가 있으면 상대경로로, 없으면 파일명으로 업로드한다(지금 폴더 아래로).
  const handleFiles = (files: FileList | null) => {
    if (!files || files.length === 0) return;
    startUpload(filesToItems(Array.from(files)));
  };

  /**
   * 업로드 시작 — 새 업로드와 「실패건 재시도」가 함께 쓴다 (WD-776, WD-783).
   * - 새 업로드(기본): 지난 거부 목록을 비우고, 이번 묶음에서 서버가 거부한 파일을 따로 들어 둔다.
   * - append(재시도): 목록을 먼저 비우지 않고, 새로 거부된 파일을 기존 「올리지 못한 파일」 목록에 이어 붙인다 —
   *   지난 거부 파일이 사라지면 안 되므로. 같은 폴더면 같은 경로는 새 이유로 교체(중복 없이), 다른 폴더면 새 것으로 바꾼다.
   * 두 갈래를 한 함수로 둬 mutate·거부 목록 갱신 규칙이 어긋나지 않게 한다(WD-783).
   */
  function startUpload(items: UploadItem[], opts?: { append?: boolean }) {
    const append = opts?.append ?? false;
    if (!append) setRejected(null);
    upload.mutate(items, {
      onSuccess: (r) => {
        // 새 거부가 없으면 기존 목록을 둔다(재시도는 그대로, 새 업로드는 위에서 이미 비웠다).
        if (r.rejectedItems.length === 0) return;
        setRejected((prev) =>
          // 재시도 대상은 이미 거부된 파일을 뺀 것이라 같은 경로가 prev 에 있을 수 없다 — 이어 붙이기만 한다.
          append && prev && prev.folderPath === r.folderPath
            ? { folderPath: r.folderPath, items: [...prev.items, ...r.rejectedItems] }
            : { folderPath: r.folderPath, items: r.rejectedItems },
        );
      },
    });
  }

  // 드롭 처리 — 폴더가 섞이면 FileSystemEntry로 재귀 순회해 하위 파일까지 상대경로로 수집한다.
  // 엔트리는 드롭 이벤트 동안에만 유효하므로 동기적으로 먼저 확보한 뒤 비동기로 순회한다.
  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const dt = e.dataTransfer;
    const entries = Array.from(dt.items)
      .map((it) => it.webkitGetAsEntry?.() ?? null)
      .filter((en): en is FileSystemEntry => en !== null);
    const flatFiles = Array.from(dt.files); // 엔트리 API 미지원 브라우저 폴백용
    void (async () => {
      const items = entries.length > 0 ? await collectEntries(entries) : filesToItems(flatFiles);
      if (items.length > 0) startUpload(items);
    })();
  };

  // 부분 실패한 항목 목록 — 있으면 배너 + 재시도(실패건만 재업로드)를 노출한다.
  // 재시도는 훅의 현재 폴더로 올라가므로, 실패한 배치를 올린 폴더를 보고 있을 때만 띄운다(다른 폴더로 잘못 올라가지 않게).
  const uploadResult = upload.data && upload.data.folderPath === path ? upload.data : undefined;
  const failedItems = uploadResult?.failedItems ?? [];
  // 마지막 묶음에서 실패한 수 — PUT 실패 + 서버가 거부한 파일(WD-776, 거부 파일은 재시도에서 빼고 아래 목록으로 따로 보인다).
  const failedCount = uploadResult ? uploadResult.total - uploadResult.succeeded : 0;
  // 거부 목록은 그 묶음을 올린 폴더를 보고 있을 때만 보인다(다른 폴더에서 엉뚱한 안내가 남지 않게).
  const rejectedItems = rejected && rejected.folderPath === path ? rejected.items : [];

  const pages = data?.pages ?? [];
  // 폴더 행은 검색 모드에 없다. 예전 응답(folders 없음)도 받도록 기본값을 준다. 불러온 폴더를 모두 파일보다 먼저 보인다.
  const folders = isSearch ? [] : pages.flatMap((p) => p.folders ?? []);
  const items = pages.flatMap((p) => p.objects);
  // 검색이 요청당 훑는 상한에 닿아 일부만 찾았다 — 서버가 이어 보기(hasMore)를 끄므로 안내로 알린다.
  const truncated = isSearch && pages.some((p) => p.truncated ?? false);

  const emptyMessage = isSearch
    ? `「${q}」와 일치하는 파일이 없습니다.`
    : path
      ? '이 폴더에 파일이 없습니다.'
      : '오브젝트가 없습니다.';
  // 올라갈 폴더 표시 — 공백뿐인 폴더 이름은 「(공백)」으로 보여 빈칸이 되지 않게 한다(WD-776).
  const pathLabel = path.split('/').map(folderDisplayName).join('/');
  const uploadHint = `${path ? `${pathLabel} 폴더에 ` : ''}파일·폴더를 드래그하거나 클릭하여 업로드`;

  return (
    <div className="space-y-4 p-2">
      {/* 위치·검색 도구 줄: 경로 표시(현재 폴더) + 이름 검색. 좁은 화면은 두 줄로 내려간다. */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <ObjectBreadcrumb path={path} onNavigate={goToFolder} />
        {/* key=path: 폴더가 바뀌면 입력을 새로 그려 남은 입력 대기 타이머(300ms)를 버린다 — 치자마자 폴더를 눌러도
            뒤늦게 검색 모드가 켜지지 않게. */}
        <SearchInput
          key={path}
          placeholder="파일 이름 검색"
          value={search}
          onChange={setSearch}
          debounceMs={SEARCH_DEBOUNCE_MS}
          className="sm:max-w-xs"
        />
      </div>

      {canWriteDataset && (
      <>
      {/* 업로드 드롭존: 클릭 시 파일 선택, 드롭 시 즉시 업로드. 폴더 안이면 올라갈 폴더를 문구로 알린다. */}
      <div
        role="button"
        tabIndex={0}
        onClick={() => inputRef.current?.click()}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') inputRef.current?.click();
        }}
        onDragOver={(e) => {
          e.preventDefault();
          setDragOver(true);
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
        className={`cursor-pointer rounded-md border border-dashed p-6 text-center text-sm ${
          dragOver ? 'border-primary bg-accent' : 'text-muted-foreground'
        }`}
      >
        {upload.isPending
          ? '업로드 중…'
          : upload.isError
            ? '업로드 실패 — 다시 시도하세요'
            : uploadHint}
        <input
          ref={inputRef}
          type="file"
          multiple
          className="hidden"
          onChange={(e) => handleFiles(e.target.files)}
        />
      </div>

      {/* 폴더 선택: webkitdirectory 인풋으로 디렉터리 전체(하위 구조 포함)를 선택해 지금 폴더 아래로 업로드한다. */}
      <div className="text-center text-sm">
        <button
          type="button"
          onClick={() => folderInputRef.current?.click()}
          className="rounded-md border px-3 py-1 text-muted-foreground hover:bg-accent"
        >
          또는 폴더 선택
        </button>
        <input
          ref={attachFolderInput}
          type="file"
          multiple
          className="hidden"
          onChange={(e) => handleFiles(e.target.files)}
        />
      </div>

      {/* 부분 실패 배너: 마지막 묶음의 실패 건수 + PUT 이 실패한 파일만 재시도. 재시도 중엔 숨김.
          서버가 거부한 파일(WD-776)은 건수에 넣되 재시도에서 빼고 아래 「올리지 못한 파일」에 이유와 함께 보인다. */}
      {!upload.isPending && uploadResult && failedCount > 0 && (
        <InlineBanner
          variant="destructive"
          data-testid="upload-failure"
          title={`${uploadResult.total}개 중 ${failedCount}개 업로드 실패`}
          actions={
            failedItems.length > 0 ? (
              <Button variant="outline" size="sm" onClick={() => startUpload(failedItems, { append: true })}>
                실패건 재시도
              </Button>
            ) : undefined
          }
        />
      )}

      {/* 올리지 못한 파일(WD-776): 서버가 파일별 검증에서 거부한 파일과 이유. 실패건 재시도를 해도 남고, 닫거나 새로 올리면 사라진다. */}
      {rejectedItems.length > 0 && (
        <InlineBanner
          variant="destructive"
          data-testid="upload-rejected"
          title={`올리지 못한 파일 ${rejectedItems.length}개 — 이유를 확인해 고친 뒤 다시 올려 주세요`}
          actions={
            <Button
              variant="outline"
              size="sm"
              aria-label="올리지 못한 파일 안내 닫기"
              onClick={() => setRejected(null)}
            >
              닫기
            </Button>
          }
        >
          <ul aria-label="올리지 못한 파일" className="space-y-1 text-xs">
            {rejectedItems.map((r, i) => (
              // 긴 경로가 화면 밖으로 넘치지 않게 아무 곳에서나 줄을 바꾼다.
              <li key={`${i}:${r.item.path}`} className="break-all">
                <span className="font-medium">{r.item.path}</span> — {r.reason}
              </li>
            ))}
          </ul>
        </InlineBanner>
      )}
      </>
      )}

      {/* 검색 상한 안내 — 결과가 없어도 보인다(「없음」이 전체를 훑은 결과가 아님을 알린다). InlineBanner 는 role=status. */}
      {truncated && (
        <InlineBanner variant="warning">
          일부만 검색됨 — 파일이 많아 검색을 중간에 멈췄습니다. 검색어를 더 구체적으로 입력하세요.
        </InlineBanner>
      )}

      {isLoading ? (
        <div className="p-6 text-muted-foreground">불러오는 중…</div>
      ) : isError && !data ? (
        // 서버가 폴더 경로를 거부(400, 손으로 고친 ?path=../ 등)하거나 조회가 실패했다 — 빈 목록으로 오인시키지 않고 서버 문구를 보인다.
        // 경로 표시는 그대로 있어 「전체」로 빠져나올 수 있다.
        <div role="alert" className="p-6 text-sm text-destructive">
          {extractApiError(error, '파일 목록을 불러오지 못했습니다.')}
        </div>
      ) : folders.length === 0 && items.length === 0 ? (
        <div className="p-6 text-muted-foreground">{emptyMessage}</div>
      ) : (
        <>
          {/* S3 스타일 목록: 이름 / 크기 / 수정일 / 다운로드. 폴더 행이 먼저, 그다음 파일 행.
              차단이어도 4번째 칸은 비워 둔다 — 행마다 열 폭이 달라져 크기·수정일 정렬이 흔들리지 않게. */}
          <div data-testid="object-list" className="overflow-hidden rounded-md border">
            <div className={`grid gap-4 border-b bg-muted px-4 py-2 text-xs font-medium text-muted-foreground ${ROW_GRID}`}>
              <span>이름</span>
              <span className="text-right">크기</span>
              <span className="hidden text-right sm:block">수정일</span>
              <span aria-hidden="true" />
            </div>
            {/* 폴더 행 — 누르면 그 폴더로 들어간다. 폴더는 크기·수정일이 없어(접두일 뿐) 「—」로 둔다. */}
            {folders.map((f) => (
              <div
                key={`folder:${f.path}`}
                data-testid="folder-row"
                className={`grid min-h-10 items-center gap-4 row-divider px-4 text-sm hover:bg-accent ${ROW_GRID}`}
              >
                <button
                  type="button"
                  // 서버는 열리는 폴더만 준다(WD-776 — ObjectKeyGenerator.isOpenableFolder) — 끝 '/' 만 떼고 그대로 쓴다.
                  onClick={() => goToFolder(f.path.replace(/\/$/, ''))}
                  title={folderDisplayName(f.name)}
                  className="flex min-w-0 items-center gap-2 py-2 text-left hover:underline"
                >
                  <Folder className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                  {/* 공백뿐인 폴더 이름은 빈 줄이 되지 않게 「(공백)」으로 보인다(WD-776). */}
                  <span className="truncate">{folderDisplayName(f.name)}</span>
                </button>
                <span className="text-right text-muted-foreground">—</span>
                <span className="hidden text-right text-muted-foreground sm:block">—</span>
                <span aria-hidden="true" />
              </div>
            ))}
            {items.map((o) => (
              <div
                key={o.key}
                data-testid="file-row"
                className={`grid min-h-10 items-center gap-4 row-divider px-4 text-sm hover:bg-accent ${ROW_GRID}`}
              >
                {isSearch ? (
                  // 검색 결과: 어느 폴더의 파일인지 알도록 전체 상대 경로를 보이고, 누르면 그 파일이 있는 폴더로 간다.
                  <button
                    type="button"
                    onClick={() => goToFolder(parentFolderOf(o.name))}
                    title={`${o.key} — 이 파일이 있는 폴더로 이동`}
                    className="truncate py-2 text-left hover:underline"
                  >
                    {o.name}
                  </button>
                ) : (
                  // 폴더 모드: 현재 폴더 안 이름만(서버 폴더 모드면 마지막 세그먼트) 보이고, 전체 키는 title 로 준다.
                  <button
                    type="button"
                    onClick={() => openObjectInNewTab(datasetId, o.key, 'inline')}
                    title={o.key}
                    className="truncate py-2 text-left hover:underline"
                  >
                    {nameInFolder(o.name, path)}
                  </button>
                )}
                <span className="text-right text-muted-foreground">{formatObjectSize(o.size)}</span>
                <span className="hidden text-right text-muted-foreground sm:block">{formatObjectDate(o.lastModified)}</span>
                <span className="flex justify-end">
                  {exportAllowed && (
                    <Button
                      variant="ghost"
                      size="icon"
                      aria-label="다운로드"
                      title="다운로드"
                      onClick={() => openObjectInNewTab(datasetId, o.key, 'attachment')}
                    >
                      <Download className="h-4 w-4" />
                    </Button>
                  )}
                </span>
              </div>
            ))}
          </div>
          {/* 불러온 목록이 있는데 조회가 실패했으면 목록은 그대로 두고 알린다(WD-776 — 다음 쪽 실패와 첫 쪽 재조회 실패를 구분해야
              「더 보기」를 누르지 않은 사용자도 무엇이 실패했는지 안다). 다음 쪽은 「더 보기」, 재조회는 「다시 불러오기」로 다시 시도한다.
              서버 사유가 있으면 아래 줄에 덧붙이고, 없으면 그 줄을 숨긴다(empty:hidden). */}
          {isError && (
            <div role="alert" className="flex flex-col items-center gap-2 text-center text-sm text-destructive">
              <p>
                {isFetchNextPageError
                  ? '다음 목록을 불러오지 못했습니다. 다시 시도하세요.'
                  : '목록을 새로 불러오지 못했습니다. 다시 시도하세요.'}
              </p>
              <p className="text-xs empty:hidden">{extractApiError(error, '')}</p>
              {!isFetchNextPageError && (
                <Button variant="outline" size="sm" onClick={() => void refetch()} disabled={isRefetching}>
                  {isRefetching ? '불러오는 중…' : '다시 불러오기'}
                </Button>
              )}
            </div>
          )}
          {hasNextPage && (
            <button
              type="button"
              onClick={() => fetchNextPage()}
              disabled={isFetchingNextPage}
              className="mx-auto block rounded-md border px-4 py-2 text-sm hover:bg-accent"
            >
              {isFetchingNextPage ? '불러오는 중…' : '더 보기'}
            </button>
          )}
        </>
      )}
    </div>
  );
}
