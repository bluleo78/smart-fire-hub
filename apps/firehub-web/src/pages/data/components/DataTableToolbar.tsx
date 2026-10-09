import { Download, Globe,Plus, Terminal, Upload } from 'lucide-react';

import { Button } from '../../../components/ui/button';
import { ExportBlockedTooltip } from '../../../components/ui/ExportBlockedTooltip';
import { SearchInput } from '../../../components/ui/search-input';

interface DataTableToolbarProps {
  dataSearch: string;
  onSearchChange: (value: string) => void;
  sqlEditorOpen: boolean;
  onToggleSqlEditor: () => void;
  onAddRow: () => void;
  onImport: () => void;
  onExport: () => void;
  onApiImport?: () => void;
  /** 조회자 기준 내보내기 가능 — false 면 「내보내기」를 비활성하고 사유 툴팁을 띄운다(S4 UI 수준 차단). */
  exportAllowed: boolean;
}

export function DataTableToolbar({
  dataSearch,
  onSearchChange,
  sqlEditorOpen,
  onToggleSqlEditor,
  onAddRow,
  onImport,
  onExport,
  onApiImport,
  exportAllowed,
}: DataTableToolbarProps) {
  return (
    <div className="flex items-center gap-3">
      <SearchInput
        placeholder="데이터 검색..."
        value={dataSearch}
        onChange={onSearchChange}
      />
      <Button
        variant={sqlEditorOpen ? 'default' : 'outline'}
        onClick={onToggleSqlEditor}
      >
        <Terminal className="h-4 w-4" />
        SQL
      </Button>
      <Button variant="outline" onClick={onAddRow}>
        <Plus className="h-4 w-4" />
        행 추가
      </Button>
      <Button variant="outline" onClick={onImport}>
        <Upload className="h-4 w-4" />
        임포트
      </Button>
      {onApiImport && (
        <Button variant="outline" onClick={onApiImport}>
          <Globe className="h-4 w-4" />
          API 가져오기
        </Button>
      )}
      {/* 주 내보내기 버튼은 숨기지 않고 비활성+툴팁 — 사용자가 왜 못 내보내는지 알 수 있게(스펙 §5-4) */}
      <ExportBlockedTooltip blocked={!exportAllowed}>
        <Button variant="outline" onClick={onExport}>
          <Download className="h-4 w-4" />
          내보내기
        </Button>
      </ExportBlockedTooltip>
    </div>
  );
}
