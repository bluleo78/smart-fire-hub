import { Download } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

import { Button } from '../../../components/ui/button';
import { useObjectList, useUploadObjects } from '../../../hooks/queries/useObjects';
import { formatObjectDate, formatObjectSize, openObjectInNewTab } from '../../../lib/objectFile';
import { collectEntries, filesToItems } from '../../../lib/uploadTree';

/**
 * FILE 데이터셋 오브젝트 브라우저 탭 — 업로드(드래그앤드롭) + S3 스타일 목록(이름/크기/수정일) + 무한스크롤.
 * 실제 S3처럼 키의 마지막 세그먼트를 파일명으로 표시한다. 이름 클릭은 inline(브라우저에서 열기), 다운로드 아이콘은
 * attachment(파일 저장) presign 을 요청한다 — attachment 는 서버가 내보내기 정책을 판정하므로(S4) 조회자 기준
 * 내보내기 불가(exportAllowed=false)면 아이콘을 숨긴다(보조 다운로드 → 숨김, 스펙 §5-4).
 */
export function DatasetObjectsTab({ datasetId, exportAllowed }: { datasetId: number; exportAllowed: boolean }) {
  const { data, isLoading, hasNextPage, fetchNextPage, isFetchingNextPage } =
    useObjectList(datasetId);
  const upload = useUploadObjects(datasetId);
  const inputRef = useRef<HTMLInputElement>(null);
  const folderInputRef = useRef<HTMLInputElement>(null);
  const [dragOver, setDragOver] = useState(false);

  // webkitdirectory는 표준 타입에 없어 JSX 속성으로 못 준다 → 마운트 시 ref로 부여(폴더 선택 인풋).
  useEffect(() => {
    folderInputRef.current?.setAttribute('webkitdirectory', '');
  }, []);

  // 파일/폴더 선택 공통 처리 — webkitRelativePath가 있으면 상대경로로, 없으면 파일명으로 업로드한다.
  const handleFiles = (files: FileList | null) => {
    if (!files || files.length === 0) return;
    upload.mutate(filesToItems(Array.from(files)));
  };

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
      if (items.length > 0) upload.mutate(items);
    })();
  };

  // 부분 실패한 항목 목록 — 있으면 배너 + 재시도(실패건만 재업로드)를 노출한다.
  const failedItems = upload.data?.failedItems ?? [];

  const items = data?.pages.flatMap((p) => p.objects) ?? [];

  return (
    <div className="space-y-4 p-2">
      {/* 업로드 드롭존: 클릭 시 파일 선택, 드롭 시 즉시 업로드 */}
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
            : '파일·폴더를 드래그하거나 클릭하여 업로드'}
        <input
          ref={inputRef}
          type="file"
          multiple
          className="hidden"
          onChange={(e) => handleFiles(e.target.files)}
        />
      </div>

      {/* 폴더 선택: webkitdirectory 인풋으로 디렉터리 전체(하위 구조 포함)를 선택해 업로드한다. */}
      <div className="text-center text-sm">
        <button
          type="button"
          onClick={() => folderInputRef.current?.click()}
          className="rounded-md border px-3 py-1 text-muted-foreground hover:bg-accent"
        >
          또는 폴더 선택
        </button>
        <input
          ref={folderInputRef}
          type="file"
          multiple
          className="hidden"
          onChange={(e) => handleFiles(e.target.files)}
        />
      </div>

      {/* 부분 실패 배너: 일부 업로드 실패 시 실패 건수 표시 + 실패건만 재시도. 재시도 중엔 숨김. */}
      {!upload.isPending && failedItems.length > 0 && (
        <div className="flex items-center justify-between rounded-md border border-destructive/50 bg-destructive/10 px-4 py-2 text-sm text-destructive">
          <span>
            {upload.data!.total}개 중 {failedItems.length}개 업로드 실패
          </span>
          <button
            type="button"
            onClick={() => upload.mutate(failedItems)}
            className="rounded-md border border-destructive/50 px-3 py-1 hover:bg-destructive/20"
          >
            실패건 재시도
          </button>
        </div>
      )}

      {isLoading ? (
        <div className="p-6 text-muted-foreground">불러오는 중…</div>
      ) : items.length === 0 ? (
        <div className="p-6 text-muted-foreground">오브젝트가 없습니다.</div>
      ) : (
        <>
          {/* S3 스타일 목록: 이름 / 크기 / 수정일 / 다운로드. 이름 클릭은 열기(inline), 아이콘은 저장(attachment).
              차단이어도 4번째 칸은 비워 둔다 — 행마다 열 폭이 달라져 크기·수정일 정렬이 흔들리지 않게.
              모바일(<sm)은 수정일 칸을 빼 이름 칸이 0폭으로 눌리지 않게 한다. */}
          <div className="overflow-hidden rounded-md border">
            <div className="grid grid-cols-[1fr_5rem_2.5rem] gap-4 border-b bg-muted/50 px-4 py-2 text-xs font-medium text-muted-foreground sm:grid-cols-[1fr_6rem_12rem_2.5rem]">
              <span>이름</span>
              <span className="text-right">크기</span>
              <span className="hidden text-right sm:block">수정일</span>
              <span aria-hidden="true" />
            </div>
            {items.map((o) => (
              <div
                key={o.key}
                className="grid min-h-10 grid-cols-[1fr_5rem_2.5rem] items-center gap-4 border-b px-4 text-sm last:border-b-0 hover:bg-accent sm:grid-cols-[1fr_6rem_12rem_2.5rem]"
              >
                <button
                  type="button"
                  onClick={() => openObjectInNewTab(datasetId, o.key, 'inline')}
                  title={o.key}
                  className="truncate py-2 text-left hover:underline"
                >
                  {o.name}
                </button>
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
