import { useQuery } from '@tanstack/react-query';
import { Plus, X } from 'lucide-react';
import { useId, useRef, useState } from 'react';

import { usersApi } from '@/api/users';
import { CreateAccountDialog } from '@/components/CreateAccountDialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { useDebounceValue } from '@/hooks/useDebounceValue';
import { MAX_SEARCH_RESULTS, MIN_QUERY_LENGTH } from '@/lib/search-limits';
import type { PlatformUserResponse } from '@/types/platform';

interface OwnerPickerProps {
  value: PlatformUserResponse | null;
  onChange: (user: PlatformUserResponse | null) => void;
  /**
   * 호출자가 `formState.errors.ownerUserId?.message` 를 그대로 내려준다.
   * 이 컴포넌트는 검증하지 않는다 — 검증 권위는 zod 하나다.
   */
  error?: string;
  /**
   * 검색 결과가 없을 때 "새 계정 만들기" 를 보일지(WD-46). 호출자가 `platform:tenant:create` 보유 여부를 내려준다 —
   * 이 컴포넌트는 권한을 직접 읽지 않는다(순수 표현 컴포넌트 유지).
   */
  canCreateAccount?: boolean;
}

/** 검색어가 이메일 형태인지 — 그렇다면 새 계정 이메일로 미리 채운다. 엄밀한 검증은 다이얼로그의 zod 가 한다. */
const EMAIL_LIKE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * 초기 Owner 선택기. **순수 표현 컴포넌트**다 — react-hook-form 을 import 하지 않고
 * 자체 검증 상태도 갖지 않는다. 선택/해제를 `onChange` 로 알리기만 하고, 폼 값 반영은
 * 호출자(TenantCreatePage)가 `setValue` 로 한다.
 *
 * `searchable-select` 를 쓰지 않는 이유: 그것은 정적 옵션 전용이고 `cmdk` 의존성을 끌어온다.
 * 여기 필요한 것은 디바운스 서버 검색이라 Input + listbox 를 직접 짜는 편이 작고 정확하다.
 *
 * 서버는 결과를 20건으로 자른다 — "더 보기"를 만들지 않는다. 못 찾으면 검색어를 좁히는 것이
 * 이 화면의 의도된 사용법이다.
 */
export function OwnerPicker({ value, onChange, error, canCreateAccount = false }: OwnerPickerProps) {
  const inputId = useId();
  const labelId = useId();
  const listId = useId();
  const [keyword, setKeyword] = useState('');
  const debounced = useDebounceValue(keyword, 300);
  const enabled = debounced.trim().length >= MIN_QUERY_LENGTH && value === null;
  const [createOpen, setCreateOpen] = useState(false);
  // 새 계정을 Owner 로 고르면 "새 계정 만들기" 행이 사라진다 — 다이얼로그가 닫힐 때 포커스를 선택 해제 버튼으로 돌린다.
  const clearButtonRef = useRef<HTMLButtonElement | null>(null);
  // prefill 은 실제로 검색한 값(디바운스·trim) 기준이다 — 입력 중인 글자가 아니라 "결과 없음" 을 만든 검색어.
  const searched = debounced.trim();
  const prefillEmail = EMAIL_LIKE.test(searched) ? searched : undefined;

  const {
    data: results,
    isFetching,
    isError,
  } = useQuery({
    queryKey: ['platform-user-search', debounced],
    queryFn: () => usersApi.search(debounced.trim()).then((r) => r.data),
    enabled,
  });

  // 두 분기가 같은 도움말을 그린다. 분기마다 복사해 두면 한쪽만 고쳐 조용히 갈라지므로
  // 분기 **밖에서 한 번만** 만든다(문구는 설계서 §3.2 확정 카피).
  const help = (
    <p className="text-sm text-muted-foreground">
      이 테넌트의 첫 소유자가 될 사용자입니다. 소유자 없이는 아무도 이 테넌트에 로그인할 수 없습니다.
    </p>
  );

  /**
   * Task 5 검색 엔드포인트는 결과를 20건으로 자르고 잘렸다는 신호를 주지 않는다.
   * 이 정적 문구가 없으면 넓은 검색어로 20명이 뜰 때 "없어서 20명이 다"인지
   * "더 있는데 20명만 보인다"인지 조작자가 구별할 수 없다(Task 5 인계 요구사항).
   */
  const capNotice = (
    <p className="text-sm text-muted-foreground">
      검색 결과는 최대 {MAX_SEARCH_RESULTS}건까지 표시됩니다. 찾는 사용자가 없으면 검색어를 더 좁혀보세요.
    </p>
  );

  /**
   * 새 계정 다이얼로그. 두 분기(선택 전·후) 어디서든 같은 인스턴스가 그려져야 한다 — 완료 시 onChange 로 선택 분기로
   * 바뀌는데, 선택 전 분기에만 두면 결과 화면이 닫히기 전에 언마운트된다.
   */
  const createDialog = canCreateAccount && (
    <CreateAccountDialog
      open={createOpen}
      onOpenChange={setCreateOpen}
      initialEmail={prefillEmail}
      finishLabel="Owner로 선택하고 닫기"
      restoreFocusRef={clearButtonRef}
      onCreated={(account) => {
        // 만든 계정을 Owner 검색 결과와 같은 3필드 모양으로 넘긴다(PlatformUserResponse).
        onChange({ id: account.id, email: account.email, name: account.name });
        setKeyword('');
      }}
    />
  );

  if (value) {
    return (
      <div className="space-y-1.5">
        {/*
          `div` 는 labelable 요소가 아니라 `<Label htmlFor>` 로는 접근 가능 이름이 생기지 않는다
          (라벨은 렌더되지만 어떤 요소에도 붙지 않는다). 그래서 Label 에 id 를 주고
          컨테이너가 role="group" + aria-labelledby 로 그것을 가리킨다 —
          이러면 선택 상태를 `getByRole('group', { name: '초기 Owner' })` 로 도달할 수 있다.
        */}
        <Label id={labelId}>초기 Owner</Label>
        <div
          role="group"
          aria-labelledby={labelId}
          className="flex items-center gap-2 rounded-md border px-3 py-2 text-sm"
        >
          <span>
            {value.name} · {value.email ?? '이메일 없음'}
          </span>
          <Button
            ref={clearButtonRef}
            type="button"
            variant="ghost"
            size="icon-sm"
            className="ml-auto"
            aria-label="Owner 선택 해제"
            onClick={() => {
              onChange(null);
              setKeyword('');
            }}
          >
            <X className="h-3.5 w-3.5" />
          </Button>
        </div>
        {help}
        {createDialog}
      </div>
    );
  }

  return (
    <div className="space-y-1.5">
      <Label htmlFor={inputId}>초기 Owner</Label>
      <Input
        id={inputId}
        role="combobox"
        aria-expanded={Boolean(results && results.length > 0)}
        aria-controls={listId}
        autoComplete="off"
        placeholder="이름 또는 이메일로 검색 (2자 이상)"
        value={keyword}
        onChange={(e) => setKeyword(e.target.value)}
      />

      {/*
        조회 실패("불러오지 못함")와 빈 결과("없음")를 반드시 구별한다 — 같은 화면이면
        조작자가 일시적 장애를 영구적인 "그런 사람 없음"으로 오해한다(Task 7 재발 패턴).
      */}
      {enabled && !isFetching && isError && (
        <p className="text-sm text-destructive">검색 결과를 불러오지 못했습니다.</p>
      )}

      {enabled && !isFetching && !isError && results && (
        <ul id={listId} role="listbox" aria-label="사용자 검색 결과" className="rounded-md border">
          {results.length === 0 ? (
            <li className="px-3 py-2 text-sm text-muted-foreground">검색 결과가 없습니다.</li>
          ) : (
            results.map((u) => (
              <li key={u.id}>
                <button
                  type="button"
                  role="option"
                  aria-selected={false}
                  className="w-full px-3 py-2 text-left text-sm hover:bg-accent hover:text-accent-foreground"
                  onClick={() => onChange(u)}
                >
                  {u.name} · {u.email ?? '이메일 없음'}
                </button>
              </li>
            ))
          )}
        </ul>
      )}

      {/*
        결과 없음 → 새 계정 만들기(WD-46). listbox 안에 두지 않는다 — option 이 아닌 버튼이 listbox 자식이면 잘못된 ARIA 다.
        결과 없음 문구 바로 아래 같은 테두리 묶음처럼 보이게 둔다.
      */}
      {canCreateAccount && enabled && !isFetching && !isError && results && results.length === 0 && (
        <button
          type="button"
          className="w-full rounded-md border border-dashed px-3 py-2 text-left text-sm text-primary hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring focus-visible:outline-none"
          onClick={() => setCreateOpen(true)}
        >
          <Plus className="mr-1 inline h-4 w-4 align-[-2px]" aria-hidden="true" />
          {prefillEmail ? `"${prefillEmail}"으로 새 계정 만들기` : '새 계정 만들기'}
        </button>
      )}

      {error && <p className="text-sm text-destructive">{error}</p>}
      {help}
      {capNotice}
      {canCreateAccount && (
        <p className="text-sm text-muted-foreground">찾는 사용자가 없으면 검색 후 새 계정을 만들 수 있습니다.</p>
      )}
      {createDialog}
    </div>
  );
}
