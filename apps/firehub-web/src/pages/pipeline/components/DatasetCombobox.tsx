import { Check, ChevronsUpDown, Lock, X } from 'lucide-react';
import { useState } from 'react';

import { Badge } from '@/components/ui/badge';
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

interface DatasetOption {
  id: number;
  name: string;
  tableName: string;
}

interface SingleProps {
  mode: 'single';
  datasets: DatasetOption[];
  value: number | null;
  onChange: (value: number | null) => void;
  placeholder?: string;
  disabled?: boolean;
  /** 트리거 버튼의 id. 호출처의 `<Label htmlFor>` 과 짝을 이룬다 (#432). */
  id?: string;
  /** `datasets` 로드 완료 여부 — 숨김 판정 조건(아래 hiddenIds 참고). 필수로 둬 호출처가 빠뜨리지 못하게 한다. */
  datasetsLoaded: boolean;
}

interface MultiProps {
  mode: 'multi';
  datasets: DatasetOption[];
  value: number[];
  onChange: (value: number[]) => void;
  placeholder?: string;
  disabled?: boolean;
  /** 트리거 버튼의 id. 호출처의 `<Label htmlFor>` 과 짝을 이룬다 (#432). */
  id?: string;
  /** `datasets` 로드 완료 여부 — 숨김 판정 조건(아래 hiddenIds 참고). 필수로 둬 호출처가 빠뜨리지 못하게 한다. */
  datasetsLoaded: boolean;
}

type DatasetComboboxProps = SingleProps | MultiProps;

/**
 * 숨김 참조 잠금 표시 — 조회자가 볼 수 없는 데이터셋(보안 등급)은 이름 대신 "열람 권한 없음"만 보인다(스펙 §5-4, 위젯 잠금 상태와 같은 문구).
 * 존재는 알리되 이름은 숨긴다: 값이 있는데 아무것도 안 보이면 사용자가 설정이 빠졌다고 오해해 덮어쓴다. 오류색·토스트 없음.
 */
function RestrictedLabel({ count = 1 }: { count?: number }) {
  return (
    <>
      <Lock className="h-3 w-3 shrink-0" aria-hidden="true" />
      열람 권한 없음{count > 1 ? ` ${count}개` : ''}
    </>
  );
}

export default function DatasetCombobox(props: DatasetComboboxProps) {
  // id 는 single/multi 두 분기 모두의 트리거 버튼에 전달해야 한다 —
  // single 분기가 early-return 이라 한쪽만 걸면 조용히 절반만 연결된다 (#432).
  const { mode, datasets, placeholder = '데이터셋 선택', disabled = false, id, datasetsLoaded } = props;
  const [open, setOpen] = useState(false);

  if (mode === 'single') {
    const { value, onChange } = props;
    const selected = datasets.find((d) => d.id === value) ?? null;
    // 숨김 = 값은 있는데 (로드가 끝난) 볼 수 있는 목록에 없다. 로딩 중엔 판정하지 않는다(잠금 표시 깜빡임 방지).
    const hidden = datasetsLoaded && value != null && !selected;

    const handleSelect = (id: number) => {
      if (value === id) {
        onChange(null);
      } else {
        onChange(id);
      }
      setOpen(false);
    };

    return (
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button
            id={id}
            variant="outline"
            role="combobox"
            aria-expanded={open}
            disabled={disabled}
            className="w-full justify-between font-normal"
          >
            {hidden ? (
              <span className="flex items-center gap-1 text-muted-foreground" data-testid="dataset-restricted">
                <RestrictedLabel />
              </span>
            ) : (
              <span className={cn('truncate', !selected && 'text-muted-foreground')}>
                {selected ? `${selected.name} (${selected.tableName})` : placeholder}
              </span>
            )}
            <ChevronsUpDown className=" h-4 w-4 shrink-0 opacity-50" />
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-full p-0" align="start">
          <Command>
            <CommandInput placeholder="데이터셋 검색..." />
            <CommandList>
              <CommandEmpty>데이터셋을 찾을 수 없습니다.</CommandEmpty>
              <CommandGroup>
                {datasets.map((ds) => (
                  <CommandItem
                    key={ds.id}
                    value={`${ds.name} ${ds.tableName}`}
                    onSelect={() => handleSelect(ds.id)}
                  >
                    <Check
                      className={cn(
                        'h-4 w-4',
                        value === ds.id ? 'opacity-100' : 'opacity-0',
                      )}
                    />
                    {ds.name} ({ds.tableName})
                  </CommandItem>
                ))}
              </CommandGroup>
            </CommandList>
          </Command>
        </PopoverContent>
      </Popover>
    );
  }

  // Multi mode
  const { value, onChange } = props;

  const selectedDatasets = datasets.filter((d) => value.includes(d.id));
  // 볼 수 없는 선택값 개수 — 이름을 모르므로 id 별 칩 대신 잠금 칩 하나로 합산한다. 로딩 중엔 0(깜빡임 방지).
  // 다른 칩 제거·추가는 value 기준 filter/append 라 이 id 들을 떨어뜨리지 않는다(왕복 보존).
  const hiddenCount = datasetsLoaded ? value.filter((v) => !datasets.some((d) => d.id === v)).length : 0;

  const handleToggle = (id: number) => {
    if (value.includes(id)) {
      onChange(value.filter((v) => v !== id));
    } else {
      onChange([...value, id]);
    }
    // Keep popover open in multi mode
  };

  const handleRemove = (id: number, e: React.MouseEvent) => {
    e.stopPropagation();
    onChange(value.filter((v) => v !== id));
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          id={id}
          variant="outline"
          role="combobox"
          aria-expanded={open}
          disabled={disabled}
          className="w-full justify-between font-normal h-auto min-h-9"
        >
          <div className="flex flex-wrap gap-1 flex-1 text-left">
            {selectedDatasets.length === 0 && hiddenCount === 0 ? (
              <span className="text-muted-foreground">{placeholder}</span>
            ) : (
              selectedDatasets.map((ds) => (
                <Badge key={ds.id} variant="secondary" className="flex items-center gap-1">
                  {ds.name}
                  <span
                    role="button"
                    tabIndex={0}
                    className="cursor-pointer rounded-full hover:bg-muted"
                    onClick={(e) => handleRemove(ds.id, e)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        onChange(value.filter((v) => v !== ds.id));
                      }
                    }}
                  >
                    <X className="h-3 w-3" />
                  </span>
                </Badge>
              ))
            )}
            {/* 숨김 선택값 잠금 칩 — 제거(X) 버튼 없음: 무엇을 지우는지 모르는 채 지우는 조작을 막는다 */}
            {hiddenCount > 0 && (
              <Badge
                variant="outline"
                className="flex items-center gap-1 text-muted-foreground"
                data-testid="dataset-restricted"
              >
                <RestrictedLabel count={hiddenCount} />
              </Badge>
            )}
          </div>
          <ChevronsUpDown className=" h-4 w-4 shrink-0 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-full p-0" align="start">
        <Command>
          <CommandInput placeholder="데이터셋 검색..." />
          <CommandList>
            <CommandEmpty>데이터셋을 찾을 수 없습니다.</CommandEmpty>
            <CommandGroup>
              {datasets.map((ds) => (
                <CommandItem
                  key={ds.id}
                  value={`${ds.name} ${ds.tableName}`}
                  onSelect={() => handleToggle(ds.id)}
                >
                  <Check
                    className={cn(
                      'h-4 w-4',
                      value.includes(ds.id) ? 'opacity-100' : 'opacity-0',
                    )}
                  />
                  {ds.name} ({ds.tableName})
                </CommandItem>
              ))}
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}
