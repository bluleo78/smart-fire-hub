import { ArrowLeft, Database, FileText } from 'lucide-react';
import { useState } from 'react';

import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../../../components/ui/dialog';

export interface DatasetTypeSelection {
  storageType: 'TABLE' | 'DOCUMENT';
  originType: 'SOURCE' | 'DERIVED';
}

interface DatasetTypeModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 선택 완료 시 호출 — 생성 폼으로 이동하는 콜백 */
  onSelect: (selection: DatasetTypeSelection) => void;
}

/** 데이터셋 생성 1단계: 저장 방식(테이블/문서) → (테이블 한정) 출처(원본/파생) 선택 */
export function DatasetTypeModal({ open, onOpenChange, onSelect }: DatasetTypeModalProps) {
  // step 1: 저장 방식 선택, step 2: 출처 선택(테이블만)
  const [step, setStep] = useState<1 | 2>(1);

  // 모달이 닫히면 단계 초기화
  const handleOpenChange = (next: boolean) => {
    if (!next) setStep(1);
    onOpenChange(next);
  };

  // 1단계 선택 처리
  const handleStorage = (storageType: 'TABLE' | 'DOCUMENT') => {
    if (storageType === 'DOCUMENT') {
      // 문서는 출처 단계를 건너뛰고 기본 SOURCE로 즉시 완료
      onSelect({ storageType: 'DOCUMENT', originType: 'SOURCE' });
      handleOpenChange(false);
    } else {
      setStep(2);
    }
  };

  // 2단계(테이블 출처) 선택 처리
  const handleOrigin = (originType: 'SOURCE' | 'DERIVED') => {
    onSelect({ storageType: 'TABLE', originType });
    handleOpenChange(false);
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-[480px]">
        <DialogHeader>
          <DialogTitle>{step === 1 ? '어떤 데이터셋을 만드시나요?' : '출처를 선택하세요'}</DialogTitle>
        </DialogHeader>

        {step === 1 ? (
          <div className="grid grid-cols-2 gap-4 py-2">
            <button
              type="button"
              onClick={() => handleStorage('TABLE')}
              className="flex flex-col items-center gap-2 rounded-lg border-2 border-border p-6 text-center transition-colors hover:border-primary hover:bg-accent"
            >
              <Database className="h-8 w-8 text-primary" />
              <span className="font-semibold">테이블</span>
              <span className="text-xs text-muted-foreground">행·열 구조 데이터<br />CSV, DB, API 등</span>
            </button>
            <button
              type="button"
              onClick={() => handleStorage('DOCUMENT')}
              className="flex flex-col items-center gap-2 rounded-lg border-2 border-border p-6 text-center transition-colors hover:border-primary hover:bg-accent"
            >
              <FileText className="h-8 w-8 text-primary" />
              <span className="font-semibold">문서</span>
              <span className="text-xs text-muted-foreground">PDF, Word,<br />텍스트 파일 등</span>
            </button>
          </div>
        ) : (
          <div className="py-2">
            <div className="grid grid-cols-2 gap-4">
              <button
                type="button"
                onClick={() => handleOrigin('SOURCE')}
                className="flex flex-col items-center gap-2 rounded-lg border-2 border-border p-6 text-center transition-colors hover:border-primary hover:bg-accent"
              >
                <span className="text-2xl">🗃️</span>
                <span className="font-semibold">원본</span>
                <span className="text-xs text-muted-foreground">직접 수집</span>
              </button>
              <button
                type="button"
                onClick={() => handleOrigin('DERIVED')}
                className="flex flex-col items-center gap-2 rounded-lg border-2 border-border p-6 text-center transition-colors hover:border-primary hover:bg-accent"
              >
                <span className="text-2xl">🔀</span>
                <span className="font-semibold">파생</span>
                <span className="text-xs text-muted-foreground">가공/집계</span>
              </button>
            </div>
            <button
              type="button"
              onClick={() => setStep(1)}
              className="mt-4 flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
            >
              <ArrowLeft className="h-4 w-4" /> 이전
            </button>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
