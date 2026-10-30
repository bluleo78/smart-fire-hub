import { AlertTriangle } from 'lucide-react';
import { type ReactNode, useEffect, useState } from 'react';

import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import { InlineBanner } from '@/components/ui/inline-banner';
import { usePipelineDeleteImpact } from '@/hooks/queries/usePipelines';

/**
 * 삭제 영향 조회 상태 — 안내 분기와 삭제 버튼 활성 여부를 이 값 하나로 정한다(WD-170).
 * - loading: 조회 중 → 삭제 막음(함께 지워지는 것을 보기 전에 확정하지 않게)
 * - error: 실패(시간 초과·404 포함) → 경고 + 삭제 허용. 안내는 "알리는 정보"이고 조회 장애가 핵심 삭제 기능을 막으면 안 된다
 *   (분석 쿼리·차트 WD-86, 데이터셋 WD-143 삭제 확인 창과 같은 결정).
 * - ready: 결과 있음 → 함께 지워질 임시 데이터셋이 있으면 빨간 상자, 없으면 지금과 같은 일반 확인 창
 * - blocked: 내가 볼 수 없는 임시 데이터셋이 함께 지워질 대상에 있음 → 서버가 삭제를 403 으로 거부하므로 안내하고 삭제를 막는다(WD-310)
 * - running: 대기·실행 중 실행이 있음 → 서버가 삭제를 409 로 거부하므로 안내하고 삭제를 막는다(WD-437). 서버 판정 순서(409 가 403 보다 먼저)와 맞춰 먼저 본다.
 */
type Status = 'loading' | 'error' | 'ready' | 'blocked' | 'running';

/** 빨간 안내 상자 — 디자인 시스템 06 인라인 배너의 destructive variant(되돌릴 수 없는 함께 삭제). WD-86 과 같은 구성. */
function ImpactBanner({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <InlineBanner
      variant="destructive"
      icon={<AlertTriangle />}
      title={<span className="text-destructive">{title}</span>}
      // break-keep: 한국어 문장이 어절 중간에서 잘리지 않게 한다.
      className="break-keep"
      data-testid="pipeline-delete-impact"
    >
      {children}
    </InlineBanner>
  );
}

/**
 * 삭제 영향 안내 — 창 내용 안에서 렌더되어 창이 열려 있을 때만 마운트·조회되고, 닫히면(애니메이션 뒤) 언마운트된다.
 * 삭제 버튼은 창 바닥(공용 다이얼로그)에 있으므로 상태를 onStatus 로 올린다. 언마운트 때 loading 으로 되돌려,
 * 다시 열었을 때 첫 화면부터 삭제 버튼이 꺼져 있게 한다.
 */
function ImpactDetails({ pipelineId, onStatus }: { pipelineId: number; onStatus: (s: Status) => void }) {
  const { data, isPending, isError } = usePipelineDeleteImpact(pipelineId);
  const status: Status = isPending
    ? 'loading'
    : isError
      ? 'error'
      : data && data.activeExecution !== 'NONE'
        ? 'running'
        : data?.hasHiddenTempDatasets
          ? 'blocked'
          : 'ready';

  useEffect(() => onStatus(status), [status, onStatus]);
  useEffect(() => () => onStatus('loading'), [onStatus]);

  switch (status) {
    case 'loading':
      return (
        <p role="status" aria-live="polite" className="text-sm text-muted-foreground">
          함께 삭제되는 항목을 확인하는 중…
        </p>
      );
    case 'error':
      return (
        <ImpactBanner title="함께 삭제되는 항목을 불러오지 못했습니다">
          이 파이프라인이 자동 생성한 임시 데이터셋이 있다면 함께 삭제됩니다.
        </ImpactBanner>
      );
    case 'running':
      // 서버 409 문구(PipelineExecutionActiveException)와 같은 문장 — 취소가 이미 진행 중이면 다시 취소하라고 하지 않고 기다리라고 한다.
      return (
        <ImpactBanner
          title={
            data?.activeExecution === 'CANCELLING'
              ? '실행 취소가 진행 중입니다. 취소가 끝난 뒤 다시 삭제하세요'
              : '실행 중에는 삭제할 수 없습니다. 실행을 취소한 뒤 삭제하세요'
          }
        />
      );
    case 'blocked':
      // 숨긴 데이터셋의 이름·개수는 서버가 주지 않는다 — 삭제할 수 없다는 것과 누구에게 요청할지만 알린다.
      return (
        <ImpactBanner title="볼 수 없는 임시 데이터셋이 있어 삭제할 수 없습니다">
          이 파이프라인이 자동 생성한 임시 데이터셋 중 내 열람 등급으로 볼 수 없는 것이 있습니다. 관리자에게 삭제를 요청하세요.
        </ImpactBanner>
      );
    case 'ready':
      // 0개면 문구를 보이지 않는다 — 지금과 같은 일반 확인 창(사람이 고른 A안).
      if (!data || data.tempDatasetCount === 0) return null;
      return <ImpactBanner title={`자동 생성된 임시 데이터셋 ${data.tempDatasetCount}개도 함께 삭제됩니다`} />;
  }
}

interface PipelineDeleteConfirmDialogProps {
  pipelineId: number;
  itemName: string;
  onConfirm: () => void;
  trigger: ReactNode;
}

/**
 * 파이프라인 삭제 확인 창(WD-170). 파이프라인을 지우면 그 스텝들이 만든 자동 생성 임시 데이터셋(메타데이터·물리 테이블)도 서버가 함께 지운다
 * — 다른 파이프라인 스텝이 출력으로 쓰는 것은 남긴다. 그래서 창을 열 때 서버에서 함께 지워질 개수를 받아 안내한다.
 * 공용 DeleteConfirmDialog 를 그대로 쓰고(제목·문구·destructive 확인 버튼 불변식 유지) details 슬롯에 안내만 끼운다.
 */
export function PipelineDeleteConfirmDialog({ pipelineId, itemName, onConfirm, trigger }: PipelineDeleteConfirmDialogProps) {
  const [status, setStatus] = useState<Status>('loading');

  return (
    <DeleteConfirmDialog
      entityName="파이프라인"
      itemName={itemName}
      onConfirm={onConfirm}
      trigger={trigger}
      details={<ImpactDetails pipelineId={pipelineId} onStatus={setStatus} />}
      confirmDisabled={status === 'loading' || status === 'blocked' || status === 'running'}
    />
  );
}
