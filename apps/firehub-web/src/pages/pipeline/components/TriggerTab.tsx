import axios from 'axios';
import { Clock, Code, Database, Globe, Link, MoreHorizontal, Pencil, Plus, Power,Trash2 } from 'lucide-react';
import { useRef, useState } from 'react';
import { toast } from 'sonner';

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
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { useDeleteTrigger, usePipelinePermissions, useToggleTrigger,useTriggers } from '@/hooks/queries/usePipelines';
import { extractApiError, isNotFoundError } from '@/lib/api-error';
import { formatDate } from '@/lib/formatters';
import { iGa } from '@/lib/utils';
import type { ErrorResponse } from '@/types/auth';
import type { TriggerResponse, TriggerType } from '@/types/pipeline';

import { AddTriggerDialog } from './AddTriggerDialog';
import { EditTriggerDialog } from './EditTriggerDialog';
import TriggerEventLog from './TriggerEventLog';

function getTriggerIcon(type: TriggerType) {
  switch (type) {
    case 'SCHEDULE':
      return <Clock className="h-4 w-4" />;
    case 'API':
      return <Code className="h-4 w-4" />;
    case 'PIPELINE_CHAIN':
      return <Link className="h-4 w-4" />;
    case 'WEBHOOK':
      return <Globe className="h-4 w-4" />;
    case 'DATASET_CHANGE':
      return <Database className="h-4 w-4" />;
  }
}

function getTriggerTypeLabel(type: TriggerType) {
  const labels: Record<TriggerType, string> = {
    SCHEDULE: '스케줄',
    API: 'API',
    PIPELINE_CHAIN: '연쇄',
    WEBHOOK: '웹훅',
    DATASET_CHANGE: '데이터 변경',
  };
  return labels[type];
}

interface TriggerTabProps {
  pipelineId: number;
}

export default function TriggerTab({ pipelineId }: TriggerTabProps) {
  const { data: triggers, isLoading, isError, error } = useTriggers(pipelineId);
  // 열린 상세 화면에서 다른 사람이 파이프라인을 지운 뒤 다시 불러오면 서버가 404 를 준다(WD-244). 예전엔 빈 목록이라
  // 「트리거가 없습니다」로 보였다 — 이제는 파이프라인 없음 안내를 보이고, 쓸 수 없는 추가 버튼·이벤트 목록은 숨긴다.
  const pipelineMissing = isError && isNotFoundError(error);
  const deleteTrigger = useDeleteTrigger(pipelineId);
  const toggleTrigger = useToggleTrigger(pipelineId);
  // 트리거 쓰기 진입점은 서버 권한과 같은 기준으로 숨긴다(WD-219) — 생성·편집·켜고 끄기 trigger:write, 삭제 trigger:delete.
  // 예전엔 trigger:read 만 있는 사용자에게도 「트리거 추가」·⋯ 메뉴가 보여 눌러야 403 토스트를 받았다.
  const { canWriteTrigger, canDeleteTrigger } = usePipelinePermissions();

  const [addDialogOpen, setAddDialogOpen] = useState(false);
  const [editTrigger, setEditTrigger] = useState<TriggerResponse | null>(null);
  const [deletingTrigger, setDeletingTrigger] = useState<TriggerResponse | null>(null);
  // WD-407: ⋯ 메뉴 항목으로 편집·삭제 창을 열면 메뉴가 닫히며 항목이 사라져, 창을 닫을 때 Radix 가 포커스를 돌려줄 요소를 잃고
  // body 로 떨어진다. 행마다 ⋯ 버튼을 기억해 두었다가 창이 닫힌 뒤 그 버튼으로 포커스를 돌려준다.
  const menuButtonRefs = useRef(new Map<number, HTMLElement>());
  const restoreFocusId = useRef<number | null>(null);
  const restoreMenuFocus = (event: Event) => {
    const el = restoreFocusId.current != null ? menuButtonRefs.current.get(restoreFocusId.current) : undefined;
    if (!el) return;
    event.preventDefault();
    el.focus();
  };

  const handleDelete = async () => {
    if (!deletingTrigger) return;
    try {
      await deleteTrigger.mutateAsync(deletingTrigger.id);
      toast.success(`트리거 "${deletingTrigger.name}"${iGa(deletingTrigger.name)} 삭제되었습니다.`);
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '트리거 삭제에 실패했습니다.');
      } else {
        toast.error('트리거 삭제에 실패했습니다.');
      }
    } finally {
      setDeletingTrigger(null);
    }
  };

  const handleToggle = async (trigger: TriggerResponse) => {
    try {
      // 화면이 본 상태의 반대를 원하는 상태로 보낸다(WD-435) — 그사이 다른 사람이 이미 바꿨어도 누른 대로 설정되고, 토스트도 그 결과와 맞는다.
      await toggleTrigger.mutateAsync({ triggerId: trigger.id, enabled: !trigger.isEnabled });
      toast.success(
        trigger.isEnabled
          ? `트리거 "${trigger.name}"${iGa(trigger.name)} 비활성화되었습니다.`
          : `트리거 "${trigger.name}"${iGa(trigger.name)} 활성화되었습니다.`,
      );
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '트리거 상태 변경에 실패했습니다.');
      } else {
        toast.error('트리거 상태 변경에 실패했습니다.');
      }
    }
  };

  return (
    <ScrollArea className="h-full">
      <div className="p-6 space-y-6">
        {/* Header */}
        <div className="flex items-center justify-between">
          <h3 className="text-xl leading-7 font-semibold">트리거</h3>
          {canWriteTrigger && !pipelineMissing && (
            <Button size="sm" onClick={() => setAddDialogOpen(true)}>
              <Plus className="h-4 w-4" />
              트리거 추가
            </Button>
          )}
        </div>

        {/* Trigger list */}
        {isLoading ? (
          <div className="text-sm text-muted-foreground">로딩 중...</div>
        ) : isError ? (
          // 조회 실패는 「트리거 없음」과 구분한다 — 404 면 서버의 「파이프라인을 찾을 수 없습니다…」 문구를 그대로 보인다(WD-244).
          <div role="alert" className="flex flex-col items-center justify-center py-12 text-center">
            <Clock className="h-12 w-12 text-muted-foreground/30 mb-4" />
            <p className="text-muted-foreground text-sm">
              {extractApiError(
                error,
                pipelineMissing
                  ? '파이프라인을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다.'
                  : '트리거 목록을 불러오지 못했습니다.',
              )}
            </p>
          </div>
        ) : !triggers || triggers.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-center">
            <Clock className="h-12 w-12 text-muted-foreground/30 mb-4" />
            <p className="text-muted-foreground text-sm">
              트리거가 없습니다.
            </p>
            <p className="text-muted-foreground text-xs mt-1">
              트리거를 추가하면 파이프라인이 자동으로 실행됩니다.
            </p>
          </div>
        ) : (
          // 열을 minmax(0,1fr) 하나로 고정한다(WD-406) — 열 지정이 없으면 암시적 열이 카드 내용의 최소 폭(이름·배지·⋯ 버튼)만큼 커져
          // 휴대폰 폭에서 카드가 목록보다 넓어지고, 오른쪽 끝의 ⋯ 메뉴 버튼이 잘려 누를 수 없었다.
          <div className="grid grid-cols-1 gap-3">
            {triggers.map((trigger) => (
              <Card key={trigger.id}>
                <CardContent className="flex items-center justify-between gap-2 p-4">
                  <div className="flex items-center gap-3 min-w-0">
                    <div className="shrink-0 text-muted-foreground">
                      {getTriggerIcon(trigger.triggerType)}
                    </div>
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        {/* min-w-0 — flex 항목의 기본 최소 폭(내용 폭) 때문에 truncate 가 듣지 않고 긴 이름이 배지를 밀어내지 않게(WD-406) */}
                        <span className="font-medium text-sm truncate min-w-0">{trigger.name}</span>
                        <Badge variant="outline" className="text-xs shrink-0">
                          {getTriggerTypeLabel(trigger.triggerType)}
                        </Badge>
                        <Badge
                          variant={trigger.isEnabled ? 'default' : 'secondary'}
                          className="text-xs shrink-0"
                        >
                          {trigger.isEnabled ? '활성' : '비활성'}
                        </Badge>
                      </div>
                      {/* 선행 파이프라인이 없는 연쇄 트리거는 꺼진 이유·무동작 이유를 보인다(WD-218) — 예전엔 이유 없는 「비활성」만 보였다. */}
                      {trigger.upstreamMissing && (
                        <p className="text-xs text-destructive mt-0.5">
                          {trigger.isEnabled
                            ? '선행 파이프라인이 없어 실행되지 않습니다. 편집에서 다른 파이프라인을 고르세요.'
                            : '선행 파이프라인이 삭제되어 꺼졌습니다. 편집에서 다른 파이프라인을 고르세요.'}
                        </p>
                      )}
                      {/* 감시 데이터셋이 모두 삭제된 데이터 변경 트리거는 꺼진 이유를 보인다(WD-221) — 예전엔 이유 없는 「비활성」만 보였다. */}
                      {trigger.watchedDatasetsMissing && (
                        <p className="text-xs text-destructive mt-0.5">
                          감시하던 데이터셋이 모두 삭제되어 꺼졌습니다. 편집에서 감시할 데이터셋을 고르세요.
                        </p>
                      )}
                      {trigger.description && (
                        <p className="text-xs text-muted-foreground mt-0.5 truncate">
                          {trigger.description}
                        </p>
                      )}
                      {/* 비활성 트리거는 실행되지 않으므로 「다음 실행」을 보이지 않는다(WD-114) */}
                      {trigger.isEnabled && trigger.nextFireTime && (
                        <p className="text-xs text-muted-foreground mt-0.5">
                          다음 실행: {formatDate(trigger.nextFireTime)}
                        </p>
                      )}
                    </div>
                  </div>

                  {/* 쓰기·삭제 권한이 모두 없으면 메뉴에 남는 항목이 없으므로 ⋯ 버튼 자체를 그리지 않는다(WD-219) — 빈 메뉴가 열리지 않게. */}
                  {(canWriteTrigger || canDeleteTrigger) && (
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <Button
                          ref={(el: HTMLButtonElement | null) => {
                            if (el) menuButtonRefs.current.set(trigger.id, el);
                            else menuButtonRefs.current.delete(trigger.id);
                          }}
                          variant="ghost"
                          size="icon"
                          className="h-8 w-8 shrink-0"
                          aria-label={`트리거 "${trigger.name}" 작업`}
                        >
                          <MoreHorizontal className="h-4 w-4" />
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end">
                        {canWriteTrigger && (
                          <DropdownMenuItem onClick={() => { restoreFocusId.current = trigger.id; setEditTrigger(trigger); }}>
                            <Pencil className="h-4 w-4" />
                            편집
                          </DropdownMenuItem>
                        )}
                        {/* 선행이 없거나(WD-218) 감시 데이터셋이 모두 삭제되면(WD-221) 켜도 발화할 대상이 없다 — 켜기만 막고 끄기는 늘 허용한다. 서버도 켜기를 거부한다. */}
                        {canWriteTrigger && (
                          <DropdownMenuItem
                            disabled={
                          !trigger.isEnabled && (!!trigger.upstreamMissing || !!trigger.watchedDatasetsMissing)
                        }
                            onClick={() => handleToggle(trigger)}
                          >
                            <Power className="h-4 w-4" />
                            {trigger.isEnabled ? '비활성화' : '활성화'}
                          </DropdownMenuItem>
                        )}
                        {canDeleteTrigger && (
                          <DropdownMenuItem
                            className="text-destructive"
                            onClick={() => { restoreFocusId.current = trigger.id; setDeletingTrigger(trigger); }}
                          >
                            <Trash2 className="h-4 w-4" />
                            삭제
                          </DropdownMenuItem>
                        )}
                      </DropdownMenuContent>
                    </DropdownMenu>
                  )}
                </CardContent>
              </Card>
            ))}
          </div>
        )}

        {/* Trigger events — 파이프라인이 없으면(WD-244) 같은 404 안내가 겹치지 않게 숨긴다 */}
        {!pipelineMissing && (
          <>
            <Separator />
            <div className="space-y-3">
              <h4 className="text-sm font-medium">최근 트리거 이벤트</h4>
              <TriggerEventLog pipelineId={pipelineId} />
            </div>
          </>
        )}

        {/* Add dialog */}
        <AddTriggerDialog
          open={addDialogOpen}
          onOpenChange={setAddDialogOpen}
          pipelineId={pipelineId}
        />

        {/* Edit dialog */}
        {editTrigger && (
          <EditTriggerDialog
            open={!!editTrigger}
            onOpenChange={(open) => { if (!open) setEditTrigger(null); }}
            pipelineId={pipelineId}
            trigger={editTrigger}
            onCloseAutoFocus={restoreMenuFocus}
          />
        )}

        {/* Delete confirmation */}
        <AlertDialog open={!!deletingTrigger} onOpenChange={(open) => { if (!open) setDeletingTrigger(null); }}>
          <AlertDialogContent onCloseAutoFocus={restoreMenuFocus}>
            <AlertDialogHeader>
              <AlertDialogTitle>트리거 삭제</AlertDialogTitle>
              <AlertDialogDescription>
                &quot;{deletingTrigger?.name}&quot; 트리거를 정말 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>취소</AlertDialogCancel>
              <AlertDialogAction variant="destructive" onClick={handleDelete}>삭제</AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </ScrollArea>
  );
}
