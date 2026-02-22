import { Button } from '../../../components/ui/button';
import { SearchInput } from '../../../components/ui/search-input';
import { Download, Upload, Terminal, Plus, Globe } from 'lucide-react';

interface DataTableToolbarProps {
  dataSearch: string;
  onSearchChange: (value: string) => void;
  sqlEditorOpen: boolean;
  onToggleSqlEditor: () => void;
  onAddRow: () => void;
  onImport: () => void;
  onExport: () => void;
  onApiImport?: () => void;
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
        <Terminal className="mr-2 h-4 w-4" />
        SQL
      </Button>
      <Button variant="outline" onClick={onAddRow}>
        <Plus className="mr-2 h-4 w-4" />
        행 추가
      </Button>
      <Button variant="outline" onClick={onImport}>
        <Upload className="mr-2 h-4 w-4" />
        임포트
      </Button>
      {onApiImport && (
        <Button variant="outline" onClick={onApiImport}>
          <Globe className="mr-2 h-4 w-4" />
          API 가져오기
        </Button>
      )}
      <Button variant="outline" onClick={onExport}>
        <Download className="mr-2 h-4 w-4" />
        CSV 내보내기
      </Button>
    </div>
  );
}
