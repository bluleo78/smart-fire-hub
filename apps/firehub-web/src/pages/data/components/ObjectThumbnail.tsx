import { usePresignedUrl } from '../../../hooks/queries/useObjects';

// 이미지로 간주할 확장자 — 해당 확장자만 presigned URL로 <img> 렌더링을 시도한다.
const IMAGE_EXT = /\.(jpe?g|png|gif|webp|bmp)$/i;

/** 오브젝트 1건 썸네일. 이미지면 presigned URL로 표시, 아니면 파일명 카드. */
export function ObjectThumbnail({
  datasetId,
  objectKey,
  size,
}: {
  datasetId: number;
  objectKey: string;
  size: number;
}) {
  const isImage = IMAGE_EXT.test(objectKey);
  // 이미지가 아닌 오브젝트는 썸네일을 렌더링하지 않으므로 presigned URL 요청 자체가 불필요하다.
  // 훅은 항상 호출하되(Hooks 규칙 준수) enabled=isImage 로 쿼리 실행 자체를 막아 네트워크 낭비를 없앤다.
  const { data: url } = usePresignedUrl(datasetId, objectKey, isImage);
  const name = objectKey.split('/').pop() ?? objectKey;

  return (
    <div className="overflow-hidden rounded-md border">
      {isImage && url ? (
        <img src={url} alt={name} className="aspect-square w-full object-cover" loading="lazy" />
      ) : (
        <div className="flex aspect-square items-center justify-center bg-muted p-2 text-center text-xs text-muted-foreground">
          {name}
        </div>
      )}
      <div className="truncate px-2 py-1 text-xs" title={objectKey}>
        {name} · {(size / 1024).toFixed(0)}KB
      </div>
    </div>
  );
}
