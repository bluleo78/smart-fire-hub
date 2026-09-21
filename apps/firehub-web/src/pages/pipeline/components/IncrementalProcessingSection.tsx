import { useState } from 'react';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import { useCancelFullRebuild, useReserveFullRebuild } from '@/hooks/queries/usePipelines';
import { formatDateTime } from '@/lib/formatters';

interface Props {
  pipelineId: number;
  /** 저장 전 스텝은 서버 id 가 없어 예약할 수 없다 */
  stepId: number | undefined;
  lastRunAt: string | null;
  fullRebuildPending: boolean;
  /**
   * REBUILD_OUTPUT: 출력을 비우고 원천 전체로 다시 만듦 (SELECT 스텝)
   * READ_ALL: 출력은 그대로 두고 원천만 전체 재조회 (사용자 DML 스텝)
   * null: 서버가 이 스텝을 증분 처리 대상으로 보지 않음 — 예약 컨트롤을 제공하지 않는다
   */
  fullRebuildMode: 'REBUILD_OUTPUT' | 'READ_ALL' | null;
  outputDatasetName: string | undefined;
  /** 편집기에 저장하지 않은 변경사항이 있으면 예약을 막는다 — 화면의 SQL과 서버 SQL이 달라질 수 있어서 */
  isDirty: boolean;
}

/**
 * 증분 SQL 스텝의 책갈피({{last_run_at}}) 표시와 "처음부터 다시 만들기" 예약/취소.
 * 예약은 즉시 데이터를 지우지 않고, 다음 실행 시에만 적용된다.
 */
export function IncrementalProcessingSection({
  pipelineId,
  stepId,
  lastRunAt,
  fullRebuildPending,
  fullRebuildMode,
  outputDatasetName,
  isDirty,
}: Props) {
  const [confirmOpen, setConfirmOpen] = useState(false);
  const reserve = useReserveFullRebuild(pipelineId);
  const cancel = useCancelFullRebuild(pipelineId);

  // 저장 전(id 없음) 또는 편집기에 미저장 변경사항이 있으면 예약을 막는다.
  // 예약은 "실행"처럼 조회 모드에서도 쓸 수 있는 운영 동작이라 readOnly 로는 막지 않는다.
  const disabled = stepId == null || isDirty;

  // fullRebuildMode(REBUILD_OUTPUT/READ_ALL) 별 문구를 이 한 곳에서만 결정한다.
  // 버튼 라벨·대기 문구·다이얼로그 설명이 각자 분기하면, 한쪽만 고치고 나머지를 빠뜨려
  // "출력을 지운다/안 지운다"는 서로 다른 약속을 화면에 동시에 노출할 위험이 있다.
  const copy =
    fullRebuildMode === 'READ_ALL'
      ? {
          reserveLabel: '원천 전체 다시 읽기',
          pendingLabel: '다음 실행 시 원천 전체를 다시 읽습니다',
          description: (
            <>
              다음 실행 시 책갈피를 무시하고 원천 전체를 다시 읽습니다. &apos;
              {outputDatasetName ?? '출력 데이터셋'}&apos;의 기존 행은 지워지지 않으며, 이 스텝의
              로드 전략(교체/추가/병합)에 따라 반영됩니다.
            </>
          ),
        }
      : {
          reserveLabel: '처음부터 다시 만들기',
          pendingLabel: '다음 실행 시 전체 재생성 예정',
          description: (
            <>
              다음 실행 시 &apos;{outputDatasetName ?? '출력 데이터셋'}&apos;의 모든 행을 지우고
              원천 전체로 다시 만듭니다. 이 스텝이 만들지 않은 행(직접 입력·수정한 행 포함)도
              사라집니다.
            </>
          ),
        };

  return (
    <div className="space-y-2 rounded-md border p-3">
      <p className="text-sm font-medium">증분 처리</p>

      {/* {{last_run_at}} 사용법 안내 — 이 기능에 대한 유일한 사용자 문서라 사용 예시와 한계를 함께 적는다 */}
      <div className="space-y-1 text-xs text-muted-foreground">
        <p>
          {'SQL에 '}
          <code className="font-mono">{'{{last_run_at}}'}</code>
          {'를 쓰면 마지막 성공 실행 이후 바뀐 행만 다시 읽습니다. 예: '}
          <code className="font-mono">{'WHERE a._updated_at >= {{last_run_at}}'}</code>
          {' (등호를 빠뜨리면 경계값이 누락됩니다).'}
        </p>
        <p>책갈피는 이 스텝이 성공적으로 끝난 뒤에만 앞으로 이동합니다.</p>
        <p>한계: 조인 대상 테이블의 변경은 감지하지 못하고, APPEND 전략과 함께 쓰면 재실행 시 행이 중복될 수 있습니다.</p>
        <p>출력 데이터셋에 PK가 있다면 증분 SQL은 APPEND보다 MERGE와 함께 쓰는 것이 안전합니다 — 같은 행이 재실행돼도 중복되지 않고 덮어씁니다.</p>
        <p>스텝 이름과 출력 데이터셋이 같으면 원천 테이블을 바꿔도 책갈피가 그대로 이어집니다 — 원천을 바꿨다면 아래에서 재생성을 예약하세요.</p>
      </div>

      {fullRebuildMode === null ? (
        <p className="text-xs text-muted-foreground">
          저장된 SQL 기준으로는 증분 처리 대상이 아니어서 재생성 예약을 제공하지 않습니다.
        </p>
      ) : fullRebuildPending ? (
        <div className="flex items-center justify-between gap-2">
          <p className="text-xs text-muted-foreground">{copy.pendingLabel}</p>
          <Button
            size="sm"
            variant="outline"
            disabled={disabled || cancel.isPending}
            onClick={() => {
              if (stepId != null) cancel.mutate(stepId);
            }}
          >
            예약 취소
          </Button>
        </div>
      ) : (
        <div className="flex items-center justify-between gap-2">
          <p className="text-xs text-muted-foreground">
            마지막 처리 시점: {lastRunAt ? formatDateTime(lastRunAt) : '아직 실행 전'}
          </p>
          <Button size="sm" variant="outline" disabled={disabled} onClick={() => setConfirmOpen(true)}>
            {copy.reserveLabel}
          </Button>
        </div>
      )}

      {stepId == null && (
        <p className="text-xs text-muted-foreground">파이프라인을 저장한 뒤 사용할 수 있습니다.</p>
      )}
      {stepId != null && isDirty && (
        <p className="text-xs text-muted-foreground">저장 후 예약할 수 있습니다.</p>
      )}

      <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{copy.reserveLabel}</AlertDialogTitle>
            <AlertDialogDescription>{copy.description}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (stepId != null) reserve.mutate(stepId);
              }}
            >
              예약
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
