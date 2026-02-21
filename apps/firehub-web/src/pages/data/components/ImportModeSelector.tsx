import { RadioGroup, RadioGroupItem } from '../../../components/ui/radio-group';
import { AlertTriangle, KeyRound } from 'lucide-react';
import type { ImportMode } from '../../../types/dataImport';

interface ImportModeSelectorProps {
  importMode: ImportMode;
  hasPrimaryKey: boolean;
  onModeChange: (mode: ImportMode) => void;
}

export function ImportModeSelector({ importMode, hasPrimaryKey, onModeChange }: ImportModeSelectorProps) {
  return (
    <div className="space-y-2">
      <h3 className="text-sm font-semibold">임포트 모드</h3>
      <RadioGroup
        value={importMode}
        onValueChange={(value) => onModeChange(value as ImportMode)}
        className="space-y-2"
      >
        <div className="flex items-start space-x-3 rounded-md border p-3">
          <RadioGroupItem value="APPEND" id="mode-append" className="mt-0.5" />
          <div className="space-y-0.5">
            <label htmlFor="mode-append" className="text-sm font-medium cursor-pointer">추가 (Append)</label>
            <p className="text-xs text-muted-foreground">파일의 모든 행을 기존 데이터에 추가합니다</p>
          </div>
        </div>
        <div className={`flex items-start space-x-3 rounded-md border p-3 ${!hasPrimaryKey ? 'opacity-50' : ''}`}>
          <RadioGroupItem value="UPSERT" id="mode-upsert" className="mt-0.5" disabled={!hasPrimaryKey} />
          <div className="space-y-0.5">
            <label htmlFor="mode-upsert" className={`text-sm font-medium ${hasPrimaryKey ? 'cursor-pointer' : 'cursor-not-allowed'}`}>
              업서트 (Upsert)
              {!hasPrimaryKey && <KeyRound className="inline h-3 w-3 ml-1 text-muted-foreground" />}
            </label>
            <p className="text-xs text-muted-foreground">
              {hasPrimaryKey
                ? '기본 키 기준으로 기존 데이터를 업데이트하고, 새 데이터를 추가합니다'
                : '데이터셋에 기본 키가 설정되어 있지 않습니다'}
            </p>
          </div>
        </div>
        <div className="flex items-start space-x-3 rounded-md border p-3">
          <RadioGroupItem value="REPLACE" id="mode-replace" className="mt-0.5" />
          <div className="space-y-0.5">
            <label htmlFor="mode-replace" className="text-sm font-medium cursor-pointer">교체 (Replace)</label>
            <p className="text-xs text-muted-foreground">기존 데이터가 모두 삭제된 후 새로 입력됩니다</p>
          </div>
        </div>
      </RadioGroup>
      {importMode === 'REPLACE' && (
        <div className="flex items-start gap-2 rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-800">
          <AlertTriangle className="h-4 w-4 mt-0.5 flex-shrink-0" />
          <p className="text-xs">기존 데이터가 모두 삭제됩니다. 이 작업은 되돌릴 수 없습니다.</p>
        </div>
      )}
    </div>
  );
}
