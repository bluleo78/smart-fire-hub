import { Check, ChevronsUpDown } from 'lucide-react';
import { useState } from 'react';

import { Button } from '@/components/ui/button';
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from '@/components/ui/command';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { cn } from '@/lib/utils';

/**
 * 단일 선택 검색 가능 콤보박스 (#104).
 * - 옵션이 많아 native select에서 키보드 첫글자 jump만으로 탐색이 어려운 경우 사용
 * - cmdk 기반 fuzzy 검색 (포함/대소문자 무시)
 * - 옵션 수가 5개 미만인 경우는 기존 native/shadcn Select 유지 권장
 */
export interface SearchableSelectOption {
  value: string;
  label: string;
}

interface SearchableSelectProps {
  value?: string;
  onChange: (value: string | undefined) => void;
  options: SearchableSelectOption[];
  placeholder?: string;
  searchPlaceholder?: string;
  emptyText?: string;
  disabled?: boolean;
  // 선택 해제(none) 옵션을 표시할지 여부 — true면 첫 항목으로 "선택 안 함" 노출
  allowClear?: boolean;
  clearLabel?: string;
  id?: string;
  className?: string;
}

export function SearchableSelect({
  value,
  onChange,
  options,
  placeholder = '선택',
  searchPlaceholder = '검색...',
  emptyText = '항목이 없습니다.',
  disabled = false,
  allowClear = false,
  clearLabel = '선택 안 함',
  id,
  className,
}: SearchableSelectProps) {
  const [open, setOpen] = useState(false);

  // 현재 선택된 옵션의 라벨 — 트리거 버튼에 표시
  const selectedLabel = options.find((o) => o.value === value)?.label;

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          id={id}
          type="button"
          variant="outline"
          role="combobox"
          aria-expanded={open}
          disabled={disabled}
          className={cn('w-full justify-between font-normal', className)}
        >
          <span className={selectedLabel ? '' : 'text-muted-foreground'}>
            {selectedLabel ?? placeholder}
          </span>
          <ChevronsUpDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-(--radix-popover-trigger-width) p-0" align="start">
        <Command>
          <CommandInput placeholder={searchPlaceholder} />
          <CommandList>
            <CommandEmpty>{emptyText}</CommandEmpty>
            <CommandGroup>
              {allowClear && (
                <CommandItem
                  value={clearLabel}
                  onSelect={() => {
                    onChange(undefined);
                    setOpen(false);
                  }}
                >
                  <Check
                    className={cn(
                      'h-4 w-4 mr-2',
                      value === undefined ? 'opacity-100' : 'opacity-0',
                    )}
                  />
                  <span className="text-muted-foreground">{clearLabel}</span>
                </CommandItem>
              )}
              {options.map((opt) => (
                <CommandItem
                  key={opt.value}
                  value={opt.label}
                  onSelect={() => {
                    onChange(opt.value);
                    setOpen(false);
                  }}
                >
                  <Check
                    className={cn(
                      'h-4 w-4 mr-2',
                      value === opt.value ? 'opacity-100' : 'opacity-0',
                    )}
                  />
                  {opt.label}
                </CommandItem>
              ))}
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}
