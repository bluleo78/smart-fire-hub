import { ArrowLeft, Pencil, Play, Save, X } from 'lucide-react';
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

import type { EditorAction,PipelineEditorState } from '../hooks/usePipelineEditor';

interface EditorHeaderProps {
  state: PipelineEditorState;
  dispatch: React.Dispatch<EditorAction>;
  readOnly: boolean;
  isEditing: boolean;
  isExecutionMode: boolean;
  onSave: () => Promise<void> | void;
  onCancelEdit: () => void;
  onEdit: () => void;
  onExecute: () => void;
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
  onSave,
  onCancelEdit,
  onEdit,
  onExecute,
  isSaving,
  isExecuting,
  pipelineId,
}: EditorHeaderProps) {
  const [executeDialogOpen, setExecuteDialogOpen] = useState(false);

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
    <header className="h-14 border-b flex items-center px-4 gap-3">
      {/* Back button */}
      <Link to="/pipelines">
        <Button variant="ghost" size="icon" aria-label="파이프라인 목록으로">
          <ArrowLeft className="h-4 w-4" />
        </Button>
      </Link>

      {/* Pipeline name */}
      <div className="flex flex-col justify-center">
        {readOnly ? (
          <span className="font-semibold text-lg min-w-[200px] max-w-[400px] truncate">
            {state.name || '파이프라인 이름'}
          </span>
        ) : (
          <Input
            value={state.name}
            onChange={(e) =>
              dispatch({ type: 'SET_META', payload: { name: e.target.value } })
            }
            placeholder="파이프라인 이름"
            className="font-semibold text-lg border-transparent hover:border-input focus:border-input min-w-[200px] max-w-[400px] h-8 px-2"
          />
        )}
        {state.isDirty && (
          <span className="text-xs text-muted-foreground flex items-center gap-1 px-2">
            <span className="text-gray-500">●</span>
            미저장 변경사항
          </span>
        )}
      </div>

      {/* Spacer */}
      <div className="flex-1" />

      {/* Execute button — 조회/편집 모두에서 표시 (실행 상세에서는 숨김) */}
      {pipelineId !== null && !isExecutionMode && (
        <>
          {state.isDirty ? (
            <AlertDialog open={executeDialogOpen} onOpenChange={setExecuteDialogOpen}>
              <AlertDialogTrigger asChild>
                <Button
                  variant="outline"
                  disabled={isExecuting}
                  onClick={handleExecuteClick}
                >
                  <Play className="h-4 w-4 mr-1" />
                  실행
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
              <Play className="h-4 w-4 mr-1" />
              실행
            </Button>
          )}
        </>
      )}

      {/* 수정 버튼 — 조회 모드에서만 표시 (실행 상세에서는 숨김) */}
      {!isEditing && !isExecutionMode && pipelineId !== null && (
        <Button variant="outline" onClick={onEdit}>
          <Pencil className="h-4 w-4 mr-1" />
          수정
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
            <X className="h-4 w-4 mr-1" />
            취소
          </Button>
          <Button
            onClick={onSave}
            disabled={!state.isDirty || isSaving}
          >
            <Save className="h-4 w-4 mr-1" />
            저장
          </Button>
        </>
      )}
    </header>
  );
}
