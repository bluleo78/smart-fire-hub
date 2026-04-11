import { ListFilter } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

import { cn } from '../../../../lib/utils';

interface ColumnFilterDropdownProps {
  columnName: string;
  uniqueValues: string[];
  selectedValues: string[];
  onFilterChange: (values: string[]) => void;
}

export function ColumnFilterDropdown({
  columnName,
  uniqueValues,
  selectedValues,
  onFilterChange,
}: ColumnFilterDropdownProps) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
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

  const filteredValues = uniqueValues
    .slice()
    .sort((a, b) => a.localeCompare(b, 'ko'))
    .filter((v) => v.toLowerCase().includes(search.toLowerCase()));

  function toggleValue(val: string) {
    if (selectedValues.includes(val)) {
      onFilterChange(selectedValues.filter((v) => v !== val));
    } else {
      onFilterChange([...selectedValues, val]);
    }
  }

  const isActive = selectedValues.length > 0;

  return (
    <div ref={containerRef} className="relative inline-flex">
      <button
        type="button"
        aria-label={`${columnName} 필터`}
        onClick={(e) => {
          e.stopPropagation();
          setOpen((v) => !v);
        }}
        className={cn(
          'flex h-4 w-4 items-center justify-center rounded transition-colors',
          isActive
            ? 'text-primary'
            : 'text-muted-foreground/50 hover:text-muted-foreground',
        )}
      >
        <ListFilter className="h-3 w-3" />
      </button>

      {open && (
        <div className="absolute left-0 top-5 z-50 w-44 rounded-md border border-border bg-popover shadow-md">
          <div className="border-b border-border p-1.5">
            <input
              type="text"
              placeholder="검색..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              autoFocus
              className="w-full rounded border border-border bg-muted/30 px-2 py-0.5 text-xs placeholder:text-muted-foreground/50 focus:outline-none focus:ring-1 focus:ring-primary"
            />
          </div>

          <div className="max-h-40 overflow-y-auto py-1">
            {filteredValues.length === 0 ? (
              <div className="px-2 py-1.5 text-xs text-muted-foreground">값 없음</div>
            ) : (
              filteredValues.map((val) => (
                <label
                  key={val}
                  className="flex cursor-pointer items-center gap-2 px-2 py-1 text-xs hover:bg-muted"
                >
                  <input
                    type="checkbox"
                    checked={selectedValues.includes(val)}
                    onChange={() => toggleValue(val)}
                    className="h-3 w-3 accent-primary"
                  />
                  <span className="truncate">{val}</span>
                </label>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}
