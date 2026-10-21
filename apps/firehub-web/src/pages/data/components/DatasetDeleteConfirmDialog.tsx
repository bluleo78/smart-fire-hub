import { AlertTriangle } from 'lucide-react';
import { Fragment, type ReactNode, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';

import { DeleteConfirmDialog } from '@/components/ui/delete-confirm-dialog';
import { InlineBanner } from '@/components/ui/inline-banner';
import { useDatasetReferences } from '@/hooks/queries/useDatasets';
import type { DatasetReferencesResponse } from '@/types/dataset';

/**
 * 참조 조회 상태 — 안내 분기와 삭제 버튼 활성 여부를 이 값 하나로 정한다.
 * - loading: 조회 중 → 삭제 막음(쓰는 곳을 보기 전에 지우지 않게)
 * - error: 실패(시간 초과·404 포함) → 안내 + 삭제 허용. 참조는 "막는 조건"이 아니라 "알리는 정보"이고(출력 데이터셋은 서버가 409 로
 *   막는다), 조회 장애가 핵심 삭제 기능을 막으면 안 된다 — 분석 쿼리·차트 삭제 확인 창(WD-86)과 같은 결정.
 * - ready: 결과 있음 → 쓰는 곳이 있으면 경고 상자, 없으면 "확인된 곳 없음" 한 줄
 */
type Status = 'loading' | 'error' | 'ready';

/** 종류별 이름을 다 나열하면 창이 길어지므로 앞의 몇 개만 링크로 보이고 나머지는 "외 n개"로 줄인다. */
const MAX_NAMES = 3;

/** 목록 한 줄에 들어갈 항목 — 표시 이름과 이동할 화면. */
interface RefLink {
  key: string;
  label: string;
  to: string;
}

/**
 * 종류 한 줄 — "파이프라인 2개: 일일 적재, 주간 집계 외 1개". 이름은 해당 화면 링크라 지우기 전에 바로 열어 볼 수 있다.
 * 창 안의 링크를 누르면 라우트가 바뀌어 목록 화면(과 이 창)이 언마운트된다 — 별도로 닫을 필요가 없다.
 * hiddenCount: 조회자가 볼 수 없는 항목 수 — 서버가 이름을 주지 않으므로 링크 없이 "볼 수 없는 대시보드 n개"로만 센다(WD-86 과 같은 표기).
 */
function RefLine({ noun, items, hiddenCount = 0 }: { noun: string; items: RefLink[]; hiddenCount?: number }) {
  const total = items.length + hiddenCount;
  if (total === 0) return null;
  const shown = items.slice(0, MAX_NAMES);
  const rest = items.length - shown.length;
  return (
    <li>
      {noun} {total}개:{' '}
      {shown.map((item, i) => (
        <Fragment key={item.key}>
          {i > 0 && ', '}
          <Link to={item.to} className="underline underline-offset-2 hover:text-primary">
            {item.label}
          </Link>
        </Fragment>
      ))}
      {rest > 0 && ` 외 ${rest}개`}
      {hiddenCount > 0 && `${shown.length > 0 ? ', ' : ''}볼 수 없는 ${noun} ${hiddenCount}개`}
    </li>
  );
}

/** 서버 응답을 종류별 링크 목록으로 바꾼다 — 라우트는 App.tsx 기준(트리거는 자기 화면이 없어 소속 파이프라인으로 보낸다). */
function toLines(data: DatasetReferencesResponse) {
  return [
    {
      noun: '파이프라인',
      items: data.pipelines.map((p) => ({ key: `p${p.id}`, label: p.name, to: `/pipelines/${p.id}` })),
    },
    {
      noun: '대시보드',
      items: data.dashboards.map((d) => ({ key: `d${d.id}`, label: d.name, to: `/analytics/dashboards/${d.id}` })),
      hiddenCount: data.hiddenDashboardCount,
    },
    {
      noun: '스마트 작업',
      items: data.proactiveJobs.map((j) => ({ key: `j${j.id}`, label: j.name, to: `/ai-insights/jobs/${j.id}` })),
    },
    {
      noun: '트리거',
      items: data.triggers.map((t) => ({
        key: `t${t.id}`,
        label: `${t.name} (${t.pipelineName})`,
        to: `/pipelines/${t.pipelineId}`,
      })),
    },
  ];
}

/** 경고 상자 — 디자인 시스템 06 인라인 배너. 되돌릴 수 없는 결과(입력 연결 삭제·위젯 고장)라 WD-86 과 같은 destructive 를 쓴다. */
function ReferencesBanner({ title, children }: { title: string; children: ReactNode }) {
  return (
    <InlineBanner
      variant="destructive"
      icon={<AlertTriangle />}
      title={<span className="text-destructive">{title}</span>}
      // break-keep: 한국어 이름 목록이 어절 중간에서 잘리지 않게 한다.
      className="break-keep"
      data-testid="dataset-references"
    >
      {children}
    </InlineBanner>
  );
}

/**
 * 참조 안내 — 창 내용 안에서 렌더되어 창이 열려 있을 때만 마운트·조회되고, 닫히면(애니메이션 뒤) 언마운트된다.
 * 삭제 버튼은 창 바닥(공용 다이얼로그)에 있으므로 상태를 onStatus 로 올린다. 언마운트 때 loading 으로 되돌려,
 * 다시 열었을 때 첫 화면부터 삭제 버튼이 꺼져 있게 한다.
 */
function ReferencesDetails({ datasetId, onStatus }: { datasetId: number; onStatus: (s: Status) => void }) {
  const { data, isPending, isError } = useDatasetReferences(datasetId);
  const status: Status = isPending ? 'loading' : isError ? 'error' : 'ready';

  useEffect(() => onStatus(status), [status, onStatus]);
  useEffect(() => () => onStatus('loading'), [onStatus]);

  switch (status) {
    case 'loading':
      return (
        <p role="status" aria-live="polite" className="text-sm text-muted-foreground">
          이 데이터셋을 쓰는 곳을 확인하는 중…
        </p>
      );
    case 'error':
      return (
        <ReferencesBanner title="참조 정보를 불러오지 못했습니다">
          이 데이터셋을 쓰는 파이프라인·대시보드가 있다면 삭제 후 동작하지 않거나 입력 연결이 빠질 수 있습니다.
        </ReferencesBanner>
      );
    case 'ready': {
      if (!data || data.totalCount === 0) {
        // "없음"이라 단정하지 않는다 — 서버는 연결 정보(FK)와 SQL 본문(저장 쿼리·파이프라인 SQL 스텝)을 보지만, 파이썬 스텝처럼 SQL 이
        // 아닌 곳이나 파싱할 수 없는 SQL 은 찾지 못한다.
        return (
          <p className="text-sm text-muted-foreground" data-testid="dataset-references-none">
            이 데이터셋을 쓰는 곳이 확인되지 않았습니다.
          </p>
        );
      }
      return (
        <ReferencesBanner title={`이 데이터셋을 쓰는 곳 ${data.totalCount}곳`}>
          {/* "빠질 수 있습니다" — 이 데이터셋이 파이프라인 출력이면 서버가 삭제를 409 로 막아 아무것도 빠지지 않는다. 단정하지 않는다. */}
          <p>삭제하면 아래 항목이 동작하지 않거나 입력 연결이 빠질 수 있습니다.</p>
          <ul className="mt-1 list-disc space-y-0.5 pl-4">
            {toLines(data).map((line) => (
              <RefLine key={line.noun} noun={line.noun} items={line.items} hiddenCount={line.hiddenCount} />
            ))}
          </ul>
        </ReferencesBanner>
      );
    }
  }
}

interface DatasetDeleteConfirmDialogProps {
  datasetId: number;
  itemName: string;
  onConfirm: () => void;
  trigger: ReactNode;
}

/**
 * 데이터셋 삭제 확인 창(WD-143). 데이터셋을 지우면 파이프라인 입력 연결(pipeline_step_input)이 CASCADE 로 빠지고, 이 데이터셋을 쓰는
 * 대시보드 위젯은 원본을 잃는다 — 그래서 창을 열 때 서버 참조 집계를 받아 쓰는 곳을 보여 주고 확인받는다(막지는 않는다: AI 챗
 * delete_dataset 규칙과 같은 정책). 공용 DeleteConfirmDialog 를 그대로 쓰고(제목·문구·destructive 확인 버튼 불변식 유지)
 * details 슬롯에 안내만 끼운다 — 분석 쿼리·차트의 AnalyticsDeleteConfirmDialog(WD-86)와 같은 구성.
 */
export function DatasetDeleteConfirmDialog({ datasetId, itemName, onConfirm, trigger }: DatasetDeleteConfirmDialogProps) {
  const [status, setStatus] = useState<Status>('loading');

  return (
    <DeleteConfirmDialog
      entityName="데이터셋"
      itemName={itemName}
      onConfirm={onConfirm}
      trigger={trigger}
      details={<ReferencesDetails datasetId={datasetId} onStatus={setStatus} />}
      confirmDisabled={status === 'loading'}
    />
  );
}
