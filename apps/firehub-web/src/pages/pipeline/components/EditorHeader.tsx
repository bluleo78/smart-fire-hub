import { ArrowLeft, Ban, Pencil, Play, RotateCcw, Save, X } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { usePipelinePermissions } from '@/hooks/queries/usePipelines';

import type { EditorAction,PipelineEditorState } from '../hooks/usePipelineEditor';
import type { ExecutionCancelState } from '../utils/execution-cancel';

interface EditorHeaderProps {
  state: PipelineEditorState;
  dispatch: React.Dispatch<EditorAction>;
  readOnly: boolean;
  isEditing: boolean;
  isExecutionMode: boolean;
  /** 실행 상세 모드에서 현재 실행이 종료(FAILED/COMPLETED/CANCELLED)되었는지 — true일 때 재실행 버튼 노출 */
  canRerun?: boolean;
  onSave: () => Promise<void> | void;
  onCancelEdit: () => void;
  onEdit: () => void;
  onExecute: () => void;
  /** 실행 상세에서 "같은 입력으로 다시 실행" 버튼 핸들러 (이슈 #84) */
  onRerun?: () => void;
  /** 실행 상세 모드에서 현재 실행의 취소 버튼 상태(WD-153) — 진행 중일 때만 버튼이 보인다. */
  cancelState?: ExecutionCancelState;
  /** 「실행 취소」 클릭 — 확인 창을 연다(실제 취소는 창에서 확정할 때). */
  onCancelExecution?: () => void;
  /** 취소 요청을 보내는 중 — 중복 클릭 방지 */
  isCancelling?: boolean;
  isSaving: boolean;
  isExecuting: boolean;
  pipelineId: number | null;
}

export function EditorHeader({
  state,
  dispatch,
  readOnly,
  isEditing,
  isExecutionMode,
  canRerun = false,
  onSave,
  onCancelEdit,
  onEdit,
  onExecute,
  onRerun,
  cancelState = 'hidden',
  onCancelExecution,
  isCancelling = false,
  isSaving,
  isExecuting,
  pipelineId,
}: EditorHeaderProps) {
  const [executeDialogOpen, setExecuteDialogOpen] = useState(false);
  // 권한이 없으면 실행·재실행·실행 취소·수정 버튼을 숨긴다(WD-219) — 예전엔 조회 전용 사용자에게도 보여 눌러야 403 을 받았다.
  // 데이터셋 상세(WD-139)·API 연결 목록과 같은 숨김 방식. 서버 근거는 usePipelinePermissions 주석 참고.
  const { canExecutePipeline, canWritePipeline } = usePipelinePermissions();

  function handleExecuteClick() {
    if (state.isDirty) {
      setExecuteDialogOpen(true);
    } else {
      onExecute();
    }
  }

  async function handleSaveAndExecute() {
    setExecuteDialogOpen(false);
    await onSave();
    onExecute();
  }

  return (
    // 모바일(375~430px)에서도 버튼이 화면 안에 들어오게 한다(WD-342) — 바깥 편집기 영역이 overflow-hidden 이라
    // 머리글이 넘치면 「저장」·「수정」이 잘려 누를 수 없었다. 좁은 화면에서는 이름 칸이 줄어들고 버튼은 아이콘만 보인다
    // (글자는 sr-only 로 남겨 스크린리더·접근 이름은 그대로).
    <header className="h-14 border-b flex items-center px-2 gap-2 sm:px-4 sm:gap-3">
      {/* Back button */}
      <Link to="/pipelines">
        <Button variant="ghost" size="icon" aria-label="파이프라인 목록으로">
          <ArrowLeft className="h-4 w-4" />
        </Button>
      </Link>

      {/* Pipeline name */}
      <div className="flex min-w-0 flex-col justify-center">
        {readOnly ? (
          <span className="font-semibold text-lg min-w-0 sm:min-w-[200px] max-w-[400px] truncate">
            {state.name || '파이프라인 이름'}
          </span>
        ) : (
          <Input
            value={state.name}
            onChange={(e) =>
              dispatch({ type: 'SET_META', payload: { name: e.target.value } })
            }
            // Ctrl+A가 ReactFlow 등 부모로 전파되지 않도록 차단 — 인풋 내 텍스트만 선택되게 제한
            onKeyDown={(e) => {
              if ((e.ctrlKey || e.metaKey) && e.key === 'a') {
                e.stopPropagation();
              }
            }}
            placeholder="파이프라인 이름"
            maxLength={100}
            className="font-semibold text-lg border-transparent hover:border-input focus:border-input min-w-0 sm:min-w-[200px] max-w-[400px] h-8 px-2"
          />
        )}
        {state.isDirty && (
          <span className="text-xs text-muted-foreground flex items-center gap-1 px-2">
            <span className="text-muted-foreground">●</span>
            미저장 변경사항
          </span>
        )}
      </div>

      {/* Spacer */}
      <div className="flex-1" />

      {/*
        재실행 버튼 — 실행 상세 모드에서 종료된 실행(FAILED/COMPLETED/CANCELLED)일 때만 표시.
        클릭 시 같은 파이프라인을 동일 입력으로 다시 실행하고, 새 실행 ID로 라우팅한다 (이슈 #84).
      */}
      {pipelineId !== null && isExecutionMode && canRerun && canExecutePipeline && (
        <Button
          variant="outline"
          disabled={isExecuting}
          onClick={onRerun}
          aria-label="같은 입력으로 다시 실행"
        >
          <RotateCcw className="h-4 w-4" />
          <span className="sr-only sm:not-sr-only">재실행</span>
        </Button>
      )}

      {/*
        실행 취소 버튼(WD-153) — 실행 상세 모드에서 진행 중(PENDING/RUNNING)인 실행에만 표시. 재실행과 자리를 나눠 쓴다
        (재실행은 종료된 실행에만 보이므로 둘이 동시에 보이지 않는다). 취소 요청이 이미 기록됐으면 실행기가 멈출 때까지
        「취소 요청됨」으로 비활성 표시해 같은 요청을 다시 보내지 않게 한다.
      */}
      {pipelineId !== null && isExecutionMode && cancelState !== 'hidden' && canExecutePipeline && (
        <Button
          variant="outline"
          disabled={cancelState === 'requested' || isCancelling}
          onClick={onCancelExecution}
        >
          <Ban className="h-4 w-4" />
          <span className="sr-only sm:not-sr-only">
            {cancelState === 'requested' ? '취소 요청됨' : isCancelling ? '취소 중…' : '실행 취소'}
          </span>
        </Button>
      )}

      {/* Execute button — 조회/편집 모두에서 표시 (실행 상세에서는 숨김). pipeline:execute 가 없으면 숨김(WD-219) */}
      {pipelineId !== null && !isExecutionMode && canExecutePipeline && (
        <>
          {state.isDirty ? (
            <AlertDialog open={executeDialogOpen} onOpenChange={setExecuteDialogOpen}>
              <AlertDialogTrigger asChild>
                <Button
                  variant="outline"
                  disabled={isExecuting}
                  onClick={handleExecuteClick}
                >
                  <Play className="h-4 w-4" />
                  <span className="sr-only sm:not-sr-only">실행</span>
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>저장하지 않은 변경사항</AlertDialogTitle>
                  <AlertDialogDescription>
                    저장하지 않은 변경사항이 있습니다. 저장 후 실행하시겠습니까?
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                  <AlertDialogCancel>취소</AlertDialogCancel>
                  <AlertDialogAction onClick={handleSaveAndExecute}>
                    저장 후 실행
                  </AlertDialogAction>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          ) : (
            <Button
              variant="outline"
              disabled={isExecuting}
              onClick={onExecute}
            >
              <Play className="h-4 w-4" />
              <span className="sr-only sm:not-sr-only">실행</span>
            </Button>
          )}
        </>
      )}

      {/* 수정 버튼 — 조회 모드에서만 표시 (실행 상세에서는 숨김). pipeline:write 가 없으면 숨김(WD-219) */}
      {!isEditing && !isExecutionMode && pipelineId !== null && canWritePipeline && (
        <Button variant="outline" onClick={onEdit}>
          <Pencil className="h-4 w-4" />
          <span className="sr-only sm:not-sr-only">수정</span>
        </Button>
      )}

      {/* 저장 + 취소 버튼 — 편집 모드에서만 표시 */}
      {isEditing && (
        <>
          <Button
            variant="outline"
            onClick={onCancelEdit}
            disabled={isSaving}
          >
            <X className="h-4 w-4" />
            <span className="sr-only sm:not-sr-only">취소</span>
          </Button>
          <Button
            onClick={onSave}
            disabled={!state.isDirty || isSaving}
          >
            <Save className="h-4 w-4" />
            <span className="sr-only sm:not-sr-only">저장</span>
          </Button>
        </>
      )}
    </header>
  );
}
