import { Plus, Trash2 } from 'lucide-react';
import { useId } from 'react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  extraHeaderNameInputId,
  type ExtraHeaderRow,
  extraHeaderValueInputId,
  isExtraHeaderValueError,
  keptStoredMasked,
  MAX_EXTRA_HEADERS,
  newExtraHeaderRow,
} from '@/lib/api-connection-extra-headers';

interface ExtraHeadersFieldsProps {
  rows: ExtraHeaderRow[];
  /** 행 목록 전체를 바꿔 돌려준다 — 값·오류 상태는 화면(만들기 대화상자·상세 편집)이 들고 있다. */
  onChange: (rows: ExtraHeaderRow[]) => void;
  /** 행 id → 오류 문구(extraHeaderRowErrors). 고친 행의 오류 지우기는 화면이 onChange 에서 한다. */
  errors?: Record<string, string>;
}

/**
 * API 연결 추가 인증 헤더 입력 칸(WD-753).
 *
 * 네이버 API HUB 처럼 인증 헤더가 둘 이상 필요한 API 를 위해 메인 인증 칸 아래에 헤더 이름·값 행을 여러 개(최대 10개) 둔다.
 * 값은 비밀이라 가린 입력(password)이고, 저장된 행은 값을 비운 채 「저장된 값 유지 (가린 값)」 자리표시로 보인다 —
 * 비워 두면 화면이 가린 값을 되돌려 보내 서버가 저장 원문을 유지한다(extraHeadersToConfig).
 * 파이프라인 API 스텝의 키값 편집기와는 비밀 값·저장값 유지 규칙이 달라 공용으로 빼지 않고 연결 전용으로 둔다.
 */
export function ExtraHeadersFields({ rows, onChange, errors = {} }: ExtraHeadersFieldsProps) {
  const baseId = useId();
  const titleId = `${baseId}-title`;
  const helpId = `${baseId}-help`;

  /** 한 행만 고친 새 목록을 돌려준다. */
  const updateRow = (id: string, patch: Partial<ExtraHeaderRow>) => {
    onChange(rows.map((row) => (row.id === id ? { ...row, ...patch } : row)));
  };

  return (
    <div role="group" aria-labelledby={titleId} aria-describedby={helpId} className="space-y-2">
      <p id={titleId} className="text-sm leading-none font-medium">
        추가 헤더
      </p>
      <p id={helpId} className="text-xs text-muted-foreground">
        인증 헤더가 둘 이상 필요한 API(예: 네이버 API HUB 키 ID)에 씁니다. 값은 암호화해 저장하고 화면에는 가려 보입니다.
      </p>
      {rows.map((row, index) => {
        const n = index + 1;
        const error = errors[row.id];
        const errorId = `${row.id}-error`;
        // 오류 문구는 그 오류가 난 칸(값 빠짐 → 값, 이름 빠짐·겹침 → 이름)에만 잇는다
        const valueError = isExtraHeaderValueError(error);
        const nameInvalid = !!error && !valueError;
        // 지금 이름으로 유지할 수 있는 저장값(가린 값) — 이름을 바꾸면 없다
        const kept = keptStoredMasked(row);
        return (
          <div key={row.id} className="space-y-1">
            <div className="flex items-start gap-2">
              <Input
                id={extraHeaderNameInputId(row.id)}
                aria-label={`추가 헤더 이름 ${n}`}
                aria-invalid={nameInvalid || undefined}
                aria-describedby={nameInvalid ? errorId : undefined}
                autoComplete="off"
                placeholder="X-Header-Name"
                className="flex-1 font-mono"
                value={row.name}
                onChange={(e) =>
                  // 저장된 행의 이름을 바꾸면 다른 헤더가 된다 — 가린 값은 원래 이름의 저장값이라 새 이름으로 되돌릴 수 없다(서버가 거절).
                  // 가린 값·원래 이름은 행에 그대로 두고, 유지 여부는 keptStoredMasked 가 이름으로 판단한다 — 이름을 원래대로
                  // 되돌리면 다시 「저장된 값 유지」가 된다(WD-753 리뷰).
                  updateRow(row.id, { name: e.target.value })
                }
              />
              <Input
                id={extraHeaderValueInputId(row.id)}
                type="password"
                aria-label={`추가 헤더 값 ${n}`}
                aria-invalid={valueError || undefined}
                aria-describedby={valueError ? errorId : undefined}
                autoComplete="new-password"
                placeholder={kept ? `저장된 값 유지 (${kept})` : '값을 입력하세요'}
                className="flex-1"
                value={row.value}
                onChange={(e) => updateRow(row.id, { value: e.target.value })}
              />
              <Button
                type="button"
                variant="ghost"
                size="icon"
                aria-label={`추가 헤더 ${n} 삭제`}
                onClick={() => onChange(rows.filter((r) => r.id !== row.id))}
              >
                <Trash2 className="h-4 w-4" />
              </Button>
            </div>
            {error && (
              <p id={errorId} className="text-sm text-destructive">
                {error}
              </p>
            )}
          </div>
        );
      })}
      <Button
        type="button"
        variant="outline"
        size="sm"
        // 버튼은 행 수(빈 행 포함)로 막아 빈 행을 끝없이 더하지 못하게 한다. 저장 검사는 보낼 행 수로 본다 —
        // 보낼 행 ≤ 행이라 버튼으로는 상한을 넘지 못하고, 재조회로 늘어난 초과는 행 오류로 보인다(WD-804 반려)
        disabled={rows.length >= MAX_EXTRA_HEADERS}
        onClick={() => onChange([...rows, newExtraHeaderRow()])}
      >
        <Plus className="h-4 w-4" />
        헤더 추가
      </Button>
    </div>
  );
}
