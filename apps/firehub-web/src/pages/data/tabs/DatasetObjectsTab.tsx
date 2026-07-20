import { useRef, useState } from 'react';

import { useObjectList, useUploadObjects } from '../../../hooks/queries/useObjects';
import { ObjectThumbnail } from '../components/ObjectThumbnail';

/** FILE 데이터셋 오브젝트 브라우저 탭 — 업로드(드래그앤드롭) + presigned 썸네일 그리드 + 무한스크롤. */
export function DatasetObjectsTab({ datasetId }: { datasetId: number }) {
  const { data, isLoading, hasNextPage, fetchNextPage, isFetchingNextPage } =
    useObjectList(datasetId);
  const upload = useUploadObjects(datasetId);
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragOver, setDragOver] = useState(false);

  // 파일 선택/드롭 공통 처리 — 1개 이상일 때만 업로드 시작.
  const handleFiles = (files: FileList | null) => {
    if (files && files.length > 0) upload.mutate(Array.from(files));
  };

  // 부분 실패한 파일 목록 — 있으면 배너 + 재시도(실패건만 재업로드)를 노출한다.
  const failedFiles = upload.data?.failedFiles ?? [];

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
        onDrop={(e) => {
          e.preventDefault();
          setDragOver(false);
          handleFiles(e.dataTransfer.files);
        }}
        className={`cursor-pointer rounded-md border border-dashed p-6 text-center text-sm ${
          dragOver ? 'border-primary bg-accent' : 'text-muted-foreground'
        }`}
      >
        {upload.isPending
          ? '업로드 중…'
          : upload.isError
            ? '업로드 실패 — 다시 시도하세요'
            : '파일을 드래그하거나 클릭하여 업로드'}
        <input
          ref={inputRef}
          type="file"
          multiple
          className="hidden"
          onChange={(e) => handleFiles(e.target.files)}
        />
      </div>

      {/* 부분 실패 배너: 일부 파일 업로드 실패 시 실패 건수 표시 + 실패건만 재시도. 재시도 중엔 숨김. */}
      {!upload.isPending && failedFiles.length > 0 && (
        <div className="flex items-center justify-between rounded-md border border-destructive/50 bg-destructive/10 px-4 py-2 text-sm text-destructive">
          <span>
            {upload.data!.total}개 중 {failedFiles.length}개 업로드 실패
          </span>
          <button
            type="button"
            onClick={() => upload.mutate(failedFiles)}
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
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-6">
            {items.map((o) => (
              <ObjectThumbnail key={o.key} datasetId={datasetId} objectKey={o.key} size={o.size} />
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
