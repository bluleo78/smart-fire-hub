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
import { useDeleteTrigger, useToggleTrigger,useTriggers } from '@/hooks/queries/usePipelines';
import { formatDate } from '@/lib/formatters';
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

  const [addDialogOpen, setAddDialogOpen] = useState(false);
  const [editTrigger, setEditTrigger] = useState<TriggerResponse | null>(null);
  const [deletingTrigger, setDeletingTrigger] = useState<TriggerResponse | null>(null);

  const handleDelete = async () => {
    if (!deletingTrigger) return;
    try {
      await deleteTrigger.mutateAsync(deletingTrigger.id);
      toast.success(`트리거 "${deletingTrigger.name}"이(가) 삭제되었습니다.`);
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
          ? `트리거 "${trigger.name}"이(가) 비활성화되었습니다.`
          : `트리거 "${trigger.name}"이(가) 활성화되었습니다.`,
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
          <h3 className="text-lg font-semibold">트리거</h3>
          <Button size="sm" onClick={() => setAddDialogOpen(true)}>
            <Plus className="mr-1 h-4 w-4" />
            트리거 추가
          </Button>
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
                      {trigger.description && (
                        <p className="text-xs text-muted-foreground mt-0.5 truncate">
                          {trigger.description}
                        </p>
                      )}
                      {trigger.nextFireTime && (
                        <p className="text-xs text-muted-foreground mt-0.5">
                          다음 실행: {formatDate(trigger.nextFireTime)}
                        </p>
                      )}
                    </div>
                  </div>

                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button variant="ghost" size="icon" className="h-8 w-8 shrink-0">
                        <MoreHorizontal className="h-4 w-4" />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                      <DropdownMenuItem onClick={() => setEditTrigger(trigger)}>
                        <Pencil className="mr-2 h-4 w-4" />
                        편집
                      </DropdownMenuItem>
                      <DropdownMenuItem onClick={() => handleToggle(trigger)}>
                        <Power className="mr-2 h-4 w-4" />
                        {trigger.isEnabled ? '비활성화' : '활성화'}
                      </DropdownMenuItem>
                      <DropdownMenuItem
                        className="text-destructive"
                        onClick={() => setDeletingTrigger(trigger)}
                      >
                        <Trash2 className="mr-2 h-4 w-4" />
                        삭제
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
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
              <AlertDialogAction onClick={handleDelete}>삭제</AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </ScrollArea>
  );
}
