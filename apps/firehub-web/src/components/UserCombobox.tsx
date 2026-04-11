import { Check, ChevronsUpDown, X } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';

import type { RecipientResponse } from '@/api/proactive';
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
import { useRecipientSearch } from '@/hooks/queries/useProactiveMessages';
import { useDebounceValue } from '@/hooks/useDebounceValue';
import { cn } from '@/lib/utils';

interface UserComboboxProps {
  selectedUserIds: number[];
  onChange: (userIds: number[]) => void;
  placeholder?: string;
  disabled?: boolean;
}

export default function UserCombobox({
  selectedUserIds,
  onChange,
  placeholder = '사용자 검색...',
  disabled = false,
}: UserComboboxProps) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const debouncedSearch = useDebounceValue(search, 300);
  // 선택된 사용자 정보를 캐시하여 검색 결과가 바뀌어도 Badge 유지.
  // useState로 관리해 렌더 단계에서 ref를 변경하는 패턴을 제거한다.
  const [userCache, setUserCache] = useState<Map<number, RecipientResponse>>(new Map());

  const { data: users = [] } = useRecipientSearch(debouncedSearch);

  // 검색 결과가 바뀌면 캐시에 새 사용자를 병합 (effect로 격리하여 렌더 단계 ref 변이를 방지)
  useEffect(() => {
    if (users.length === 0) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setUserCache((prev) => {
      const next = new Map(prev);
      for (const u of users) next.set(u.userId, u);
      return next;
    });
  }, [users]);

  const handleToggle = useCallback((userId: number) => {
    if (selectedUserIds.includes(userId)) {
      onChange(selectedUserIds.filter((id) => id !== userId));
    } else {
      onChange([...selectedUserIds, userId]);
    }
  }, [selectedUserIds, onChange]);

  const handleRemove = (userId: number, e: React.MouseEvent) => {
    e.stopPropagation();
    onChange(selectedUserIds.filter((id) => id !== userId));
  };

  const selectedUsers = selectedUserIds
    .map((id) => userCache.get(id))
    .filter((u): u is RecipientResponse => u != null);

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
            {selectedUsers.length === 0 ? (
              <span className="text-muted-foreground">{placeholder}</span>
            ) : (
              selectedUsers.map((u) => (
                <Badge key={u.userId} variant="secondary" className="flex items-center gap-1">
                  {u.name}
                  <span
                    role="button"
                    tabIndex={0}
                    className="cursor-pointer rounded-full hover:bg-muted"
                    onClick={(e) => handleRemove(u.userId, e)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        onChange(selectedUserIds.filter((id) => id !== u.userId));
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
        <Command shouldFilter={false}>
          <CommandInput
            placeholder="이름 또는 이메일로 검색..."
            value={search}
            onValueChange={setSearch}
          />
          <CommandList>
            {debouncedSearch.length === 0 ? (
              <CommandEmpty>검색어를 입력하세요</CommandEmpty>
            ) : users.length === 0 ? (
              <CommandEmpty>사용자를 찾을 수 없습니다.</CommandEmpty>
            ) : (
              <CommandGroup>
                {users.map((u) => (
                  <CommandItem
                    key={u.userId}
                    value={String(u.userId)}
                    onSelect={() => handleToggle(u.userId)}
                  >
                    <Check
                      className={cn(
                        'h-4 w-4 mr-2',
                        selectedUserIds.includes(u.userId) ? 'opacity-100' : 'opacity-0',
                      )}
                    />
                    <div>
                      <span className="font-medium">{u.name}</span>
                      <span className="ml-2 text-xs text-muted-foreground">{u.email}</span>
                    </div>
                  </CommandItem>
                ))}
              </CommandGroup>
            )}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}
