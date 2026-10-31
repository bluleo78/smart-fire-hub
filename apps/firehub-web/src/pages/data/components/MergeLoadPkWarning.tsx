import { AlertTriangle } from 'lucide-react';
import { Link } from 'react-router-dom';

import { InlineBanner } from '@/components/ui/inline-banner';
import { useDatasetReferences } from '@/hooks/queries/useDatasets';

/**
 * 기본 키를 모두 해제하려는 순간(기본 키 일괄 설정에서 전부 해제 · 마지막 기본 키 필드 삭제) 이 데이터셋을 병합(MERGE) 적재 출력으로 쓰는
 * 파이프라인을 미리 알리는 경고(WD-441).
 *
 * 왜: 병합 적재는 출력 데이터셋의 기본 키가 있어야 돈다. 기본 키가 사라지면 그 파이프라인은 예약 실행까지 포함해 매 실행 FAILED 가 되고
 * 편집 저장도 400 인데, 데이터셋을 고친 사람은 이를 알 길이 없었다. 막지 않고 알린다 — 일부러 키를 바꾸는 경우도 있으므로 확인은
 * 사용자가 「적용」·「삭제」를 누르는 것으로 갈음한다.
 *
 * 경고가 필요한 때에만 마운트해서 쓴다(그때 참조를 조회한다). 조회 중·실패·해당 파이프라인 없음이면 아무것도 그리지 않는다 —
 * 근거 없는 경고로 정상 변경을 막아 세우지 않게 한다.
 */
export function MergeLoadPkWarning({ datasetId }: { datasetId: number }) {
  const { data } = useDatasetReferences(datasetId);
  const pipelines = data?.mergeOutputPipelines ?? [];
  if (pipelines.length === 0) return null;

  return (
    <InlineBanner
      variant="destructive"
      icon={<AlertTriangle />}
      title={
        <span className="text-destructive">
          병합 적재 파이프라인 {pipelines.length}개가 실행되지 않게 됩니다
        </span>
      }
      // break-keep: 한국어 이름 목록이 어절 중간에서 잘리지 않게 한다.
      className="break-keep"
      data-testid="merge-load-pk-warning"
    >
      <p>
        기본 키가 없으면 아래 파이프라인의 병합 적재가 다음 실행부터 매번 실패하고, 파이프라인을 저장할 수도 없습니다.
      </p>
      <ul className="mt-1 list-disc space-y-0.5 pl-4">
        {pipelines.map((p) => (
          <li key={p.id}>
            <Link to={`/pipelines/${p.id}`} className="underline underline-offset-2">
              {p.name}
            </Link>
          </li>
        ))}
      </ul>
    </InlineBanner>
  );
}
