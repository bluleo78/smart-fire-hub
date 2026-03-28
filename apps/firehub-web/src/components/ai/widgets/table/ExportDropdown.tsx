import { useRef, useEffect, useState } from 'react';
import { Download } from 'lucide-react';
import { cn } from '../../../../lib/utils';

interface ExportDropdownProps {
  onExport: (format: 'csv' | 'json') => void;
}

export function ExportDropdown({ onExport }: ExportDropdownProps) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;

    function handleClickOutside(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [open]);

  function handleSelect(format: 'csv' | 'json') {
    onExport(format);
    setOpen(false);
  }

  return (
    <div ref={containerRef} className="relative inline-flex">
      <button
        type="button"
        title="내보내기"
        onClick={() => setOpen((v) => !v)}
        className={cn(
          'flex items-center gap-1 rounded px-1.5 py-0.5 text-xs transition-colors',
          open
            ? 'bg-muted text-foreground'
            : 'text-muted-foreground hover:bg-muted hover:text-foreground',
        )}
      >
        <Download className="h-3.5 w-3.5" />
        내보내기
      </button>

      {open && (
        <div className="absolute right-0 top-6 z-50 w-32 rounded-md border border-border bg-popover shadow-md py-1">
          <button
            type="button"
            onClick={() => handleSelect('csv')}
            className="w-full px-3 py-1.5 text-left text-xs hover:bg-muted"
          >
            CSV 다운로드
          </button>
          <button
            type="button"
            onClick={() => handleSelect('json')}
            className="w-full px-3 py-1.5 text-left text-xs hover:bg-muted"
          >
            JSON 다운로드
          </button>
          <button
            type="button"
            disabled
            className="w-full px-3 py-1.5 text-left text-xs text-muted-foreground/50 cursor-not-allowed"
          >
            Excel (준비 중)
          </button>
        </div>
      )}
    </div>
  );
}
