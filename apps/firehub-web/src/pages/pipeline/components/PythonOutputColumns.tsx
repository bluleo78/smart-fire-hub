import { AlertCircle, Plus, Trash2 } from 'lucide-react';
import { useMemo } from 'react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';

const COLUMN_TYPES = ['TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIMESTAMP'];

interface OutputColumn {
  name: string;
  type: string;
}

interface PythonOutputColumnsProps {
  columns: OutputColumn[];
  onChange: (columns: OutputColumn[]) => void;
  readOnly?: boolean;
}

function getColumnError(col: OutputColumn, index: number, all: OutputColumn[]): string | undefined {
  if (col.name === '') return undefined; // 빈 이름은 별도 처리 안 함 (저장 시 검증)
  if (!/^[a-z][a-z0-9_]*$/.test(col.name)) {
    return '소문자, 숫자, 밑줄(_)만 사용 가능하며 소문자로 시작해야 합니다';
  }
  const duplicate = all.findIndex((c, i) => i !== index && c.name === col.name);
  if (duplicate !== -1) {
    return '중복된 컬럼명입니다';
  }
  return undefined;
}

export default function PythonOutputColumns({
  columns,
  onChange,
  readOnly = false,
}: PythonOutputColumnsProps) {
  const errors = useMemo(
    () => columns.map((col, i) => getColumnError(col, i, columns)),
    [columns],
  );

  const hasErrors = errors.some((e) => e !== undefined);

  const addColumn = () => {
    onChange([...columns, { name: '', type: 'TEXT' }]);
  };

  const removeColumn = (i: number) => {
    onChange(columns.filter((_, idx) => idx !== i));
  };

  const updateColumn = (i: number, field: 'name' | 'type', value: string) => {
    onChange(columns.map((col, idx) => (idx === i ? { ...col, [field]: value } : col)));
  };

  return (
    <section aria-label="출력 컬럼 정의" className="space-y-2">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-1.5 text-sm font-medium">
          <span>출력 컬럼 정의</span>
          {hasErrors && (
            <AlertCircle className="h-3.5 w-3.5 text-destructive" aria-label="컬럼 오류 있음" />
          )}
        </div>
        {!readOnly && (
          <Button
            variant="outline"
            size="sm"
            className="h-7 text-xs px-2"
            onClick={addColumn}
            aria-label="출력 컬럼 추가"
          >
            <Plus className="h-3 w-3 mr-1" aria-hidden="true" />
            컬럼 추가
          </Button>
        )}
      </div>

      {/* Column list or empty state */}
      {columns.length === 0 ? (
        <div
          className="flex flex-col items-center justify-center py-5 border border-dashed rounded-md gap-1.5"
          role="status"
          aria-label="출력 컬럼 없음"
        >
          <Plus className="h-4 w-4 text-muted-foreground/40" aria-hidden="true" />
          <span className="text-xs text-muted-foreground">
            {readOnly ? '정의된 출력 컬럼이 없습니다' : '컬럼 추가 버튼으로 출력 컬럼을 정의하세요'}
          </span>
        </div>
      ) : (
        <div className="space-y-1.5" role="list" aria-label="출력 컬럼 목록">
          {/* Column header row */}
          <div className="grid grid-cols-[1fr_140px_auto] gap-1.5 text-xs text-muted-foreground px-0.5">
            <span id="col-header-name">컬럼명</span>
            <span id="col-header-type">타입</span>
            <span className="sr-only">삭제</span>
          </div>

          {columns.map((col, i) => {
            const error = errors[i];
            const inputId = `python-col-name-${i}`;
            const errorId = `python-col-error-${i}`;
            return (
              <div
                key={i}
                className="space-y-0.5"
                role="listitem"
                aria-label={`컬럼 ${i + 1}${col.name ? `: ${col.name}` : ''}`}
              >
                <div className="grid grid-cols-[1fr_140px_auto] gap-1.5 items-center">
                  <Input
                    id={inputId}
                    className={[
                      'h-7 text-xs font-mono',
                      error ? 'border-destructive focus-visible:ring-destructive/30' : '',
                    ]
                      .filter(Boolean)
                      .join(' ')}
                    placeholder="column_name"
                    value={col.name}
                    disabled={readOnly}
                    aria-labelledby="col-header-name"
                    aria-describedby={error ? errorId : undefined}
                    aria-invalid={!!error}
                    onChange={(e) => updateColumn(i, 'name', e.target.value)}
                  />
                  <Select
                    value={col.type}
                    disabled={readOnly}
                    onValueChange={(v) => updateColumn(i, 'type', v)}
                  >
                    <SelectTrigger
                      className="h-7 text-xs w-full"
                      aria-labelledby="col-header-type"
                      aria-label={`컬럼 ${i + 1} 타입`}
                    >
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {COLUMN_TYPES.map((t) => (
                        <SelectItem key={t} value={t}>
                          {t}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  {!readOnly ? (
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-7 w-7 shrink-0 text-muted-foreground hover:text-destructive"
                      onClick={() => removeColumn(i)}
                      aria-label={`컬럼 ${i + 1}${col.name ? ` "${col.name}"` : ''} 삭제`}
                    >
                      <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
                    </Button>
                  ) : (
                    <span className="h-7 w-7 shrink-0" aria-hidden="true" />
                  )}
                </div>

                {/* Inline validation error */}
                {error && (
                  <p
                    id={errorId}
                    className="text-[0.75rem] text-destructive pl-0.5"
                    role="alert"
                    aria-live="polite"
                  >
                    {error}
                  </p>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* Contextual hint — shown only when there are columns or in edit mode */}
      {(columns.length > 0 || !readOnly) && (
        <p className="text-xs text-muted-foreground leading-relaxed">
          stdout으로 출력되는 JSON의 키 이름이 여기서 정의한 컬럼명과 일치해야 합니다.{' '}
          <span className="font-medium text-foreground/70">출력 데이터셋을 지정하지 않은 경우</span>에만
          이 컬럼 정의를 기반으로 임시 데이터셋이 자동 생성됩니다.
        </p>
      )}
    </section>
  );
}
