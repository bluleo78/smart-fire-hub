import { useObjectList } from '../../../hooks/queries/useObjects';
import { ObjectThumbnail } from '../components/ObjectThumbnail';

/** FILE 데이터셋 오브젝트 브라우저 탭 — presigned 썸네일 그리드 + 더보기(무한스크롤). */
export function DatasetObjectsTab({ datasetId }: { datasetId: number }) {
  const { data, isLoading, hasNextPage, fetchNextPage, isFetchingNextPage } =
    useObjectList(datasetId);

  if (isLoading) return <div className="p-6 text-muted-foreground">불러오는 중…</div>;
  const items = data?.pages.flatMap((p) => p.objects) ?? [];
  if (items.length === 0)
    return <div className="p-6 text-muted-foreground">오브젝트가 없습니다.</div>;

  return (
    <div className="space-y-4 p-2">
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
    </div>
  );
}
