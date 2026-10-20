import axios from 'axios';
import { AlertTriangle, Lock } from 'lucide-react';
import { type ReactNode, useEffect, useState } from 'react';

import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import { InlineBanner } from '@/components/ui/inline-banner';
import { useDeleteImpact } from '@/hooks/queries/useAnalytics';
import type { DeleteImpact, DeleteImpactItem } from '@/types/analytics';

type Kind = 'query' | 'chart';

/**
 * 영향 범위 조회 상태 — 안내 분기와 삭제 버튼 활성 여부를 이 값 하나로 정한다.
 * - loading: 조회 중 → 삭제 막음(내용을 보기 전에 함께 지우지 않게)
 * - notOwned: 404 → 삭제 막음(서버가 삭제와 같은 소유자 관문을 써서 삭제도 같은 404 로 실패한다)
 * - error: 그 밖의 실패(시간 초과 포함) → 경고 + 삭제 허용(조회 장애가 핵심 삭제 기능을 막지 않게)
 * - ready: 결과 있음 → 영향이 있으면 빨간 상자, 없으면 일반 확인 창
 */
type Status = 'loading' | 'notOwned' | 'error' | 'ready';

/** 종류별 문구 — 쿼리·차트가 다른 곳은 여기에만 둔다. */
const COPY = {
  query: {
    entity: '쿼리',
    errorBody: '이 쿼리로 만든 차트와 그 차트를 쓰는 대시보드 위젯이 있다면 함께 삭제됩니다.',
    widgetSubject: '그 차트를 쓰는 대시보드 위젯',
  },
  chart: {
    entity: '차트',
    errorBody: '이 차트를 쓰는 대시보드 위젯이 있다면 함께 삭제됩니다.',
    widgetSubject: '이 차트를 쓰는 대시보드 위젯',
  },
} as const;

/** 이름 목록의 명사·단위 — "볼 수 없는 차트 1개", "외 2곳". */
const LABEL = {
  chart: { noun: '차트', unit: '개' },
  dashboard: { noun: '대시보드', unit: '곳' },
} as const;

/** 본인 소유가 아니어서 지울 수 없을 때의 안내 문구 — 쿼리·차트 모두 같은 문구를 쓴다. */
const NOT_OWNER_MESSAGE = '본인이 만든 항목만 삭제할 수 있습니다.';

/** 이름을 다 나열하면 창이 길어지므로 앞의 몇 개만 보이고 나머지는 "외 n개"로 줄인다. */
const MAX_NAMES = 5;

/**
 * 이름 목록 + 볼 수 없는 항목 개수를 한 줄 괄호 안 문구로 만든다.
 * 예: "지역별 매출, 월 추이, 볼 수 없는 차트 1개" — 볼 수 없는 항목은 이름 없이 개수만(서버가 이름을 주지 않는다).
 */
function describeNames(items: DeleteImpactItem[], hiddenCount: number, { noun, unit }: (typeof LABEL)[keyof typeof LABEL]): string {
  const shown = items.slice(0, MAX_NAMES).map((i) => i.name);
  const rest = items.length - shown.length;
  const parts: string[] = [];
  if (shown.length > 0) parts.push(shown.join(', ') + (rest > 0 ? ` 외 ${rest}${unit}` : ''));
  if (hiddenCount > 0) parts.push(`볼 수 없는 ${noun} ${hiddenCount}${unit}`);
  return parts.join(', ');
}

/** 함께 삭제되는 항목이 하나라도 있는가 — 없으면 지금과 같은 일반 확인 창을 보인다(A안). */
function hasImpact(impact: DeleteImpact): boolean {
  return impact.charts.length + impact.hiddenChartCount + impact.widgetCount > 0;
}

/** 빨간 안내 상자 — 디자인 시스템 06 인라인 배너의 destructive variant. 제목은 시안대로 빨강, 본문은 무채색. */
function ImpactBanner({ title, children }: { title: string; children: ReactNode }) {
  return (
    <InlineBanner
      variant="destructive"
      icon={<AlertTriangle />}
      title={<span className="text-destructive">{title}</span>}
      // break-keep: 한국어 이름 목록이 어절 중간에서 잘리지 않게 한다(restricted-notice 와 같은 이유).
      className="break-keep"
      data-testid="delete-impact"
    >
      {children}
    </InlineBanner>
  );
}

/**
 * 영향 범위 안내 — 창 내용 안에서 렌더되어 창이 열려 있을 때만 마운트·조회되고, 닫히면(애니메이션 뒤) 언마운트된다.
 * 삭제 버튼은 창 바닥(공용 다이얼로그)에 있으므로 상태를 onStatus 로 올린다. 언마운트 때 loading 으로 되돌려,
 * 다시 열었을 때 첫 화면부터 삭제 버튼이 꺼져 있게 한다.
 */
function ImpactDetails({ kind, id, onStatus }: { kind: Kind; id: number; onStatus: (s: Status) => void }) {
  const { data, isPending, isError, error } = useDeleteImpact(kind, id);
  const status: Status = isPending
    ? 'loading'
    : isError
      ? axios.isAxiosError(error) && error.response?.status === 404
        ? 'notOwned'
        : 'error'
      : 'ready';

  useEffect(() => onStatus(status), [status, onStatus]);
  useEffect(() => () => onStatus('loading'), [onStatus]);

  const copy = COPY[kind];
  switch (status) {
    case 'loading':
      return (
        <p role="status" aria-live="polite" className="text-sm text-muted-foreground">
          함께 삭제되는 항목을 확인하는 중…
        </p>
      );
    case 'notOwned':
      return (
        <InlineBanner variant="info" icon={<Lock />} data-testid="delete-not-owner">
          {NOT_OWNER_MESSAGE}
        </InlineBanner>
      );
    case 'error':
      return <ImpactBanner title="함께 삭제되는 항목을 불러오지 못했습니다">{copy.errorBody}</ImpactBanner>;
    case 'ready': {
      if (!data || !hasImpact(data)) return null;
      const chartTotal = data.charts.length + data.hiddenChartCount;
      const dashboardTotal = data.dashboards.length + data.hiddenDashboardCount;
      return (
        <ImpactBanner title="함께 삭제되는 항목">
          <ul className="list-disc space-y-0.5 pl-4">
            {kind === 'query' && chartTotal > 0 && (
              <li>
                이 쿼리로 만든 차트 {chartTotal}개 ({describeNames(data.charts, data.hiddenChartCount, LABEL.chart)})
              </li>
            )}
            {data.widgetCount > 0 && (
              <li>
                {copy.widgetSubject} {data.widgetCount}개 (대시보드 {dashboardTotal}곳:{' '}
                {describeNames(data.dashboards, data.hiddenDashboardCount, LABEL.dashboard)})
              </li>
            )}
          </ul>
        </ImpactBanner>
      );
    }
  }
}

interface AnalyticsDeleteConfirmDialogProps {
  /** 삭제 대상 종류 — 영향 범위 API 와 안내 문구를 고른다. */
  kind: Kind;
  id: number;
  itemName: string;
  onConfirm: () => void;
  trigger: ReactNode;
}

/**
 * 분석 저장 쿼리·차트 삭제 확인 창(WD-86). 쿼리를 지우면 그 쿼리의 차트가, 차트를 지우면 그 차트를 쓰는 대시보드 위젯이 DB CASCADE 로
 * 함께 사라지므로, 창을 열 때 서버에서 영향 범위를 받아 빨간 상자로 보여 준다. 연결된 것이 없으면 지금과 같은 일반 확인 창이다.
 * 공용 DeleteConfirmDialog 를 그대로 쓰고(제목·문구·destructive 확인 버튼 불변식 유지) details 슬롯에 안내만 끼운다.
 */
export function AnalyticsDeleteConfirmDialog({ kind, id, itemName, onConfirm, trigger }: AnalyticsDeleteConfirmDialogProps) {
  const [status, setStatus] = useState<Status>('loading');

  return (
    <DeleteConfirmDialog
      entityName={COPY[kind].entity}
      itemName={itemName}
      onConfirm={onConfirm}
      trigger={trigger}
      details={<ImpactDetails kind={kind} id={id} onStatus={setStatus} />}
      confirmDisabled={status === 'loading' || status === 'notOwned'}
    />
  );
}
