import axios from 'axios';
import { Clock, Code, Database, Globe, Link, MoreHorizontal, Pencil, Plus, Power,Trash2 } from 'lucide-react';
import { useState } from 'react';
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
  const { data: triggers, isLoading } = useTriggers(pipelineId);
  const deleteTrigger = useDeleteTrigger(pipelineId);
  const toggleTrigger = useToggleTrigger(pipelineId);
  // 트리거 쓰기 진입점은 서버 권한과 같은 기준으로 숨긴다(WD-219) — 생성·편집·켜고 끄기 trigger:write, 삭제 trigger:delete.
  // 예전엔 trigger:read 만 있는 사용자에게도 「트리거 추가」·⋯ 메뉴가 보여 눌러야 403 토스트를 받았다.
  const { canWriteTrigger, canDeleteTrigger } = usePipelinePermissions();

  const [addDialogOpen, setAddDialogOpen] = useState(false);
  const [editTrigger, setEditTrigger] = useState<TriggerResponse | null>(null);
  const [deletingTrigger, setDeletingTrigger] = useState<TriggerResponse | null>(null);

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
      await toggleTrigger.mutateAsync(trigger.id);
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
          {canWriteTrigger && (
            <Button size="sm" onClick={() => setAddDialogOpen(true)}>
              <Plus className="h-4 w-4" />
              트리거 추가
            </Button>
          )}
        </div>

        {/* Trigger list */}
        {isLoading ? (
          <div className="text-sm text-muted-foreground">로딩 중...</div>
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
          <div className="grid gap-3">
            {triggers.map((trigger) => (
              <Card key={trigger.id}>
                <CardContent className="flex items-center justify-between p-4">
                  <div className="flex items-center gap-3 min-w-0">
                    <div className="shrink-0 text-muted-foreground">
                      {getTriggerIcon(trigger.triggerType)}
                    </div>
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="font-medium text-sm truncate">{trigger.name}</span>
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
                        <Button variant="ghost" size="icon" className="h-8 w-8 shrink-0" aria-label={`트리거 "${trigger.name}" 작업`}>
                          <MoreHorizontal className="h-4 w-4" />
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="end">
                        {canWriteTrigger && (
                          <DropdownMenuItem onClick={() => setEditTrigger(trigger)}>
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
                            onClick={() => setDeletingTrigger(trigger)}
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

        {/* Trigger events */}
        <Separator />
        <div className="space-y-3">
          <h4 className="text-sm font-medium">최근 트리거 이벤트</h4>
          <TriggerEventLog pipelineId={pipelineId} />
        </div>

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
          />
        )}

        {/* Delete confirmation */}
        <AlertDialog open={!!deletingTrigger} onOpenChange={(open) => { if (!open) setDeletingTrigger(null); }}>
          <AlertDialogContent>
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
