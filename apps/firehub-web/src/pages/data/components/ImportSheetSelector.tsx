import { useEffect, useRef } from 'react';

import { FormField } from '../../../components/ui/form-field';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../../../components/ui/select';
import type { ImportSheetInfo } from '../../../types/dataImport';

interface ImportSheetSelectorProps {
  /** 통합 문서의 모든 시트(문서 순서). CSV 면 null/undefined — 아무것도 그리지 않는다 */
  sheets: ImportSheetInfo[] | null | undefined;
  /** 마지막 미리보기에서 실제로 읽은 시트 */
  sheetName: string | null | undefined;
  /** 미리보기 재요청·검증 중이면 선택을 막는다 */
  disabled: boolean;
  onSheetChange: (sheetName: string) => void;
}

/**
 * 엑셀 임포트에서 「어느 시트를 가져오는지」를 보여 주고 바꿀 수 있게 하는 영역 (WD-253).
 * 예전에는 숨김 시트라도 무조건 첫 시트를 읽으면서 화면에는 시트 이름이 전혀 나오지 않았다.
 * - 시트가 2장 이상: 「가져올 시트」 선택 상자(숨김 시트는 「(숨김)」 표시) — 바꾸면 미리보기를 다시 받는다
 * - 시트가 1장: 읽기 전용 안내 한 줄
 * - CSV(시트 정보 없음): 표시하지 않음
 */
export function ImportSheetSelector({ sheets, sheetName, disabled, onSheetChange }: ImportSheetSelectorProps) {
  const triggerRef = useRef<HTMLButtonElement>(null);
  const wasDisabled = useRef(disabled);

  // 미리보기 재요청 동안 트리거가 비활성화되면 포커스가 body 로 빠진다 — 끝나면 트리거로 돌려 놓아
  // 키보드·스크린리더 사용자가 위치를 잃지 않게 한다. (다른 곳으로 포커스를 옮긴 경우는 건드리지 않는다)
  useEffect(() => {
    if (wasDisabled.current && !disabled && document.activeElement === document.body) {
      triggerRef.current?.focus();
    }
    wasDisabled.current = disabled;
  }, [disabled]);

  if (!sheets || sheets.length === 0) return null;

  if (sheets.length === 1) {
    return <p className="text-xs text-muted-foreground">가져올 시트: {sheets[0].name}</p>;
  }

  return (
    // 라벨(htmlFor)이 트리거(button)의 이름이 된다 — 선택값만 읽히지 않도록(#331 과 같은 원칙)
    <FormField label="가져올 시트" htmlFor="import-sheet-select">
      <Select value={sheetName ?? undefined} onValueChange={onSheetChange} disabled={disabled}>
        <SelectTrigger ref={triggerRef} id="import-sheet-select" className="w-full sm:w-64">
          <SelectValue placeholder="시트를 선택하세요" />
        </SelectTrigger>
        <SelectContent>
          {sheets.map((sheet) => (
            <SelectItem key={sheet.name} value={sheet.name}>
              {sheet.hidden ? `${sheet.name} (숨김)` : sheet.name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </FormField>
  );
}
