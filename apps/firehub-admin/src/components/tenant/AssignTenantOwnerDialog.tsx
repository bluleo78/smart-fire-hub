import { useState } from 'react';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { stripEdgeSpace } from '@/lib/text-rules';
import type { TenantMemberResponse } from '@/types/platform';

interface AssignTenantOwnerDialogProps {
  /** 소유자로 지정할 멤버. null 이면 닫힌다. */
  target: TenantMemberResponse | null;
  /** 확인 입력과 비교할 테넌트 이름 — 엉뚱한 테넌트에서 실행하는 실수를 막는 확인 장치다. */
  tenantName: string;
  /** 닫기(취소·ESC·확인 후)를 요청한다. */
  onClose: () => void;
  /** 확인 버튼. 요청과 성공/실패 처리는 호출자가 맡는다 — 이 창은 닫히며 언마운트되므로 결과 콜백을 여기에 두지 않는다. */
  onConfirm: (userId: number, confirmName: string) => void;
}

/**
 * 「소유자로 지정」 확인 창(WD-1132).
 *
 * 소유권 변경은 되돌리기 어려워 테넌트 이름을 직접 입력해야 확인 버튼이 켜진다(서버도 같은 값을 다시 검증한다).
 * 입력은 내용 컴포넌트가 상태로 갖고, 창이 닫히면 함께 사라져 다음에 열 때 항상 빈 칸이다.
 */
export function AssignTenantOwnerDialog({
  target,
  tenantName,
  onClose,
  onConfirm,
}: AssignTenantOwnerDialogProps) {
  return (
    <AlertDialog open={target !== null} onOpenChange={(open) => !open && onClose()}>
      <AlertDialogContent>
        {target && <Body target={target} tenantName={tenantName} onConfirm={onConfirm} />}
      </AlertDialogContent>
    </AlertDialog>
  );
}

/** 입력 상태를 가진 본문 — 열릴 때마다 새로 마운트되어 입력이 초기화된다. */
function Body({
  target,
  tenantName,
  onConfirm,
}: {
  target: TenantMemberResponse;
  tenantName: string;
  onConfirm: (userId: number, confirmName: string) => void;
}) {
  const [input, setInput] = useState('');
  // 앞뒤 공백만 허용한다(서버와 같은 규칙) — 대소문자는 구분한다.
  // 저장 이름도 trim 해 비교한다(WD-1132 리뷰) — 이름이 " Acme " 처럼 공백을 가져도 공백 없이 입력해 통과할 수 있어야 한다. 서버도 같은 규칙.
  // WD-1158: 빈 입력(공백뿐 포함)은 이름과 같아 보여도 확인으로 치지 않는다 — 이름이 공백뿐인 테넌트에서 아무것도 안 쳐도 통과하던 구멍.
  // 같은 입력을 두 번 다듬지 않게 한 번만 계산한다.
  const trimmedInput = stripEdgeSpace(input);
  const matches = trimmedInput !== '' && trimmedInput === stripEdgeSpace(tenantName);

  return (
    <>
      <AlertDialogHeader>
        <AlertDialogTitle>{`${target.username}을(를) 소유자로 지정할까요?`}</AlertDialogTitle>
        <AlertDialogDescription>
          {`"${tenantName}" 워크스페이스의 소유자가 ${target.username}(으)로 바뀝니다. 기존 소유자는 ADMIN 으로 내려갑니다.`}
        </AlertDialogDescription>
      </AlertDialogHeader>
      <div className="space-y-2">
        <Label htmlFor="assign-owner-confirm">확인하려면 테넌트 이름을 입력하세요</Label>
        <Input
          id="assign-owner-confirm"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          autoComplete="off"
          placeholder={tenantName}
        />
      </div>
      <AlertDialogFooter>
        <AlertDialogCancel>취소</AlertDialogCancel>
        <AlertDialogAction
          variant="destructive"
          disabled={!matches}
          onClick={() => onConfirm(target.userId, trimmedInput)}
        >
          소유자로 지정
        </AlertDialogAction>
      </AlertDialogFooter>
    </>
  );
}
