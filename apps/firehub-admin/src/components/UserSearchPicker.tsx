import { X } from 'lucide-react';
import { type ReactNode, type Ref, useId } from 'react';

import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import type { PlatformUserSearch } from '@/hooks/usePlatformUserSearch';
import { MAX_QUERY_LENGTH, MAX_SEARCH_RESULTS } from '@/lib/search-limits';
import type { PlatformUserResponse } from '@/types/platform';

interface UserSearchPickerProps {
  /** 고른 사용자. 있으면 검색 대신 고른 사용자 칩(+지우기)을 그린다. */
  value: PlatformUserResponse | null;
  /** 목록에서 고르면 그 사용자, 지우기를 누르면 null. 지우기는 검색어도 함께 비운다. */
  onChange: (user: PlatformUserResponse | null) => void;
  /** 검색어(디바운스 전). 상태는 호출자가 갖는다 — 새 계정 완료 등 호출자 사정으로 비울 일이 있어서다. */
  keyword: string;
  onKeywordChange: (keyword: string) => void;
  /** 호출자가 `usePlatformUserSearch(keyword, value === null)` 로 얻은 검색 상태. */
  search: PlatformUserSearch;
  /** 칸 라벨(예: 「초기 Owner」). 고른 상태에서는 group 의 접근 가능 이름이 된다. */
  label: string;
  /** 지우기 버튼 aria-label(예: 「Owner 선택 해제」). */
  clearLabel: string;
  /** 결과 listbox aria-label. */
  listLabel: string;
  /** 결과 0건 문구. */
  emptyText: string;
  /** 상한 안내의 뒷문장(예: 「찾는 사용자가 없으면 검색어를 더 좁혀보세요.」) — 화면마다 부르는 대상이 달라 호출자가 준다. */
  capNoticeHint: string;
  /** 두 상태(검색·고름) 모두에 그릴 도움말. */
  help?: ReactNode;
  /** 결과 목록 바로 아래(검색 상태에서만) 붙일 요소 — 「새 계정 만들기」·폼 오류 등. */
  afterResults?: ReactNode;
  /** 상한 안내 아래(검색 상태에서만) 붙일 요소. */
  footer?: ReactNode;
  /** 제출 중 등 지우기를 막아야 할 때. */
  disabled?: boolean;
  /** 지우기 버튼 ref — 고른 상태로 바뀐 뒤 대화상자가 닫힐 때 포커스를 돌려받을 곳. */
  clearButtonRef?: Ref<HTMLButtonElement>;
  /** 검색 입력 ref — 호출자가 「먼저 고르세요」 오류와 함께 포커스를 옮길 때. */
  inputRef?: Ref<HTMLInputElement>;
}

/**
 * 플랫폼 사용자 검색 선택기(WD-750 에서 `OwnerPicker` 의 검색 본문을 뽑음). Owner 선택기와 멤버 추가 「기존 계정」 탭이 함께 쓴다 —
 * 두 곳에 복사해 두면 디바운스·오류/빈 상태 구별·상한 안내가 한쪽만 고쳐져 갈라진다.
 *
 * **순수 표현 컴포넌트**다 — 검색어와 검색 결과(`usePlatformUserSearch`)는 호출자가 갖고 내려준다. 호출자가 「결과 없음」에 다음 행동을 붙여야
 * 해서(Owner 선택기의 새 계정 만들기) 검색 상태를 호출자도 봐야 하기 때문이다.
 *
 * `searchable-select` 를 쓰지 않는 이유: 그것은 정적 옵션 전용이고 `cmdk` 의존성을 끌어온다. 여기 필요한 것은 디바운스 서버 검색이라
 * Input + listbox 를 직접 짜는 편이 작고 정확하다. 서버는 결과를 20건으로 자른다 — 「더 보기」를 만들지 않고 검색어를 좁히게 한다.
 */
export function UserSearchPicker({
  value,
  onChange,
  keyword,
  onKeywordChange,
  search,
  label,
  clearLabel,
  listLabel,
  emptyText,
  capNoticeHint,
  help,
  afterResults,
  footer,
  disabled,
  clearButtonRef,
  inputRef,
}: UserSearchPickerProps) {
  const inputId = useId();
  const labelId = useId();
  const listId = useId();
  const { enabled, results, showError, showResults } = search;

  if (value) {
    return (
      <div className="space-y-1.5">
        {/*
          `div` 는 labelable 요소가 아니라 `<Label htmlFor>` 로는 접근 가능 이름이 생기지 않는다.
          그래서 Label 에 id 를 주고 컨테이너가 role="group" + aria-labelledby 로 그것을 가리킨다 —
          이러면 선택 상태를 `getByRole('group', { name: label })` 로 도달할 수 있다.
        */}
        <Label id={labelId}>{label}</Label>
        <div
          role="group"
          aria-labelledby={labelId}
          className="flex items-center gap-2 rounded-md border px-3 py-2 text-sm"
        >
          <span className="min-w-0 break-all">
            {value.name} · {value.email ?? '이메일 없음'}
          </span>
          <Button
            ref={clearButtonRef}
            type="button"
            variant="ghost"
            size="icon-sm"
            className="ml-auto"
            aria-label={clearLabel}
            disabled={disabled}
            onClick={() => {
              onChange(null);
              onKeywordChange('');
            }}
          >
            <X className="h-3.5 w-3.5" />
          </Button>
        </div>
        {help}
      </div>
    );
  }

  return (
    <div className="space-y-1.5">
      <Label htmlFor={inputId}>{label}</Label>
      <Input
        ref={inputRef}
        id={inputId}
        role="combobox"
        aria-expanded={Boolean(enabled && results && results.length > 0)}
        aria-controls={listId}
        autoComplete="off"
        placeholder="이름 또는 이메일로 검색 (2자 이상)"
        // 서버 상한을 넘기면 400 → 「검색 결과를 불러오지 못했습니다」로 장애처럼 보여 입력에서 막는다(WD-317).
        maxLength={MAX_QUERY_LENGTH}
        value={keyword}
        onChange={(e) => onKeywordChange(e.target.value)}
      />

      {/*
        조회 실패("불러오지 못함")와 빈 결과("없음")를 반드시 구별한다 — 같은 화면이면
        조작자가 일시적 장애를 영구적인 "그런 사람 없음"으로 오해한다.
      */}
      {showError && <p className="text-sm text-destructive">검색 결과를 불러오지 못했습니다.</p>}

      {showResults && results && (
        <ul id={listId} role="listbox" aria-label={listLabel} className="max-h-48 overflow-y-auto rounded-md border">
          {results.length === 0 ? (
            <li className="px-3 py-2 text-sm text-muted-foreground">{emptyText}</li>
          ) : (
            results.map((u) => (
              <li key={u.id}>
                {/* 서버가 id 로 찾으므로 이메일이 없는 사용자도 고를 수 있다 — 표시만 「이메일 없음」으로 한다. */}
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

      {afterResults}
      {help}
      {/*
        서버는 결과를 20건으로 자르고 잘렸다는 신호를 주지 않는다. 이 정적 문구가 없으면 넓은 검색어로 20명이 뜰 때
        "없어서 20명이 다"인지 "더 있는데 20명만 보인다"인지 조작자가 구별할 수 없다.
      */}
      <p className="text-sm text-muted-foreground">
        검색 결과는 최대 {MAX_SEARCH_RESULTS}건까지 표시됩니다. {capNoticeHint}
      </p>
      {footer}
    </div>
  );
}
