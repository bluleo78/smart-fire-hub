import { Plus } from 'lucide-react';
import { useRef, useState } from 'react';

import { CreateAccountDialog } from '@/components/CreateAccountDialog';
import { UserSearchPicker } from '@/components/UserSearchPicker';
import { usePlatformUserSearch } from '@/hooks/usePlatformUserSearch';
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
 * 검색·목록·고른 사용자 칩은 멤버 추가 대화상자와 함께 쓰는 `UserSearchPicker` 가 그린다(WD-750). 여기에는 Owner 화면에만
 * 있는 것(도움말 문구, 결과 없음 → 새 계정 만들기, 폼 오류)만 둔다.
 */
export function OwnerPicker({ value, onChange, error, canCreateAccount = false }: OwnerPickerProps) {
  const [keyword, setKeyword] = useState('');
  const search = usePlatformUserSearch(keyword, value === null);
  const [createOpen, setCreateOpen] = useState(false);
  // 새 계정을 Owner 로 고르면 "새 계정 만들기" 행이 사라진다 — 다이얼로그가 닫힐 때 포커스를 선택 해제 버튼으로 돌린다.
  const clearButtonRef = useRef<HTMLButtonElement | null>(null);
  // prefill 은 실제로 검색한 값(디바운스·trim) 기준이다 — 입력 중인 글자가 아니라 "결과 없음" 을 만든 검색어.
  const prefillEmail = EMAIL_LIKE.test(search.searched) ? search.searched : undefined;

  /**
   * 새 계정 다이얼로그. 선택 전·후 어디서든 같은 인스턴스가 그려져야 한다 — 완료 시 onChange 로 선택 상태로
   * 바뀌는데, 선택 전 상태에만 두면 결과 화면이 닫히기 전에 언마운트된다.
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

  return (
    <>
      <UserSearchPicker
        value={value}
        onChange={onChange}
        keyword={keyword}
        onKeywordChange={setKeyword}
        search={search}
        label="초기 Owner"
        clearLabel="Owner 선택 해제"
        listLabel="사용자 검색 결과"
        emptyText="검색 결과가 없습니다."
        capNoticeHint="찾는 사용자가 없으면 검색어를 더 좁혀보세요."
        clearButtonRef={clearButtonRef}
        // 문구는 설계서 §3.2 확정 카피 — 선택 전·후 두 상태가 같은 도움말을 그린다.
        help={
          <p className="text-sm text-muted-foreground">
            이 테넌트의 첫 소유자가 될 사용자입니다. 소유자 없이는 아무도 이 테넌트에 로그인할 수 없습니다.
          </p>
        }
        afterResults={
          <>
            {/*
              결과 없음 → 새 계정 만들기(WD-46). listbox 안에 두지 않는다 — option 이 아닌 버튼이 listbox 자식이면 잘못된 ARIA 다.
              결과 없음 문구 바로 아래 같은 테두리 묶음처럼 보이게 둔다.
            */}
            {canCreateAccount && search.noResults && (
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
          </>
        }
        footer={
          canCreateAccount && (
            <p className="text-sm text-muted-foreground">찾는 사용자가 없으면 검색 후 새 계정을 만들 수 있습니다.</p>
          )
        }
      />
      {createDialog}
    </>
  );
}
