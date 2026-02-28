import { Check, ChevronsUpDown, X } from 'lucide-react';
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
}

interface MultiProps {
  mode: 'multi';
  datasets: DatasetOption[];
  value: number[];
  onChange: (value: number[]) => void;
  placeholder?: string;
  disabled?: boolean;
}

type DatasetComboboxProps = SingleProps | MultiProps;

export default function DatasetCombobox(props: DatasetComboboxProps) {
  const { mode, datasets, placeholder = '데이터셋 선택', disabled = false } = props;
  const [open, setOpen] = useState(false);

  if (mode === 'single') {
    const { value, onChange } = props;
    const selected = datasets.find((d) => d.id === value) ?? null;

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
            variant="outline"
            role="combobox"
            aria-expanded={open}
            disabled={disabled}
            className="w-full justify-between font-normal"
          >
            <span className={cn('truncate', !selected && 'text-muted-foreground')}>
              {selected ? `${selected.name} (${selected.tableName})` : placeholder}
            </span>
            <ChevronsUpDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
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
                        'mr-2 h-4 w-4',
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
          variant="outline"
          role="combobox"
          aria-expanded={open}
          disabled={disabled}
          className="w-full justify-between font-normal h-auto min-h-9"
        >
          <div className="flex flex-wrap gap-1 flex-1 text-left">
            {selectedDatasets.length === 0 ? (
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
          </div>
          <ChevronsUpDown className="ml-2 h-4 w-4 shrink-0 opacity-50" />
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
                      'mr-2 h-4 w-4',
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
