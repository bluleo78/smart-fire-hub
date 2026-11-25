import { type ReactNode, useState } from 'react';

import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { OnceAlertDialogAction } from '@/components/ui/once-alert-dialog-action';
import { stripEdgeSpace } from '@/lib/text-rules';

interface TransferOwnershipDialogProps {
  /** 확인 창 제목에 보일 대상 이름(name 없으면 username) */
  targetName: string;
  /** 사용자가 입력해야 하는 워크스페이스 이름(확인 입력의 정답) */
  workspaceName: string;
  /**
   * 확인 버튼을 눌렀을 때 — 입력한(trim 한) 워크스페이스 이름을 넘긴다.
   * 요청·성공/오류 안내는 호출처가 맡는다: 확인 버튼이 창을 닫으면서 이 창 내용이 언마운트되므로,
   * 여기서 요청을 들고 있으면 응답 전에 콜백이 버려진다(공용 OnceAlertDialogAction 과 같은 패턴).
   */
  onConfirm: (confirmName: string) => void;
  /** 확인 창을 여는 버튼 */
  trigger: ReactNode;
}

/**
 * 소유권 넘기기 확인 창(WD-1132). 되돌리려면 새 소유자가 다시 넘겨야 하는 큰 변경이라
 * 워크스페이스 이름을 직접 입력해야 확인 버튼이 켜진다(입력을 trim 한 값이 이름과 정확히 같을 때만 — 서버도 같은 규칙으로 다시 검증).
 * 입력 상태는 창 내용 안쪽 컴포넌트에 둬서 창이 닫히면 함께 사라진다 — 다시 열면 빈 입력으로 시작한다.
 */
export function TransferOwnershipDialog({
  targetName,
  workspaceName,
  onConfirm,
  trigger,
}: TransferOwnershipDialogProps) {
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>{trigger}</AlertDialogTrigger>
      <AlertDialogContent>
        <TransferOwnershipBody
          targetName={targetName}
          workspaceName={workspaceName}
          onConfirm={onConfirm}
        />
      </AlertDialogContent>
    </AlertDialog>
  );
}

/** 창 내용 — 이름 입력 상태와 요청을 가진다. 창이 열려 있는 동안만 마운트된다. */
function TransferOwnershipBody({
  targetName,
  workspaceName,
  onConfirm,
}: Omit<TransferOwnershipDialogProps, 'trigger'>) {
  const [input, setInput] = useState('');
  // 정확 비교(대소문자 구분)이되 앞뒤 공백만 허용한다 — 서버 규칙과 같다
  // 저장 이름도 trim 해 비교한다(WD-1132 리뷰) — 이름이 " Acme " 처럼 공백을 가져도 공백 없이 입력해 통과할 수 있어야 한다. 서버도 같은 규칙.
  // WD-1158: 입력이 공백뿐이면(trim 후 빈 문자열) 이름이 공백뿐인 워크스페이스에서 빈 입력이 「일치」로 통과하므로 따로 막는다.
  // 같은 입력을 두 번 다듬지 않게 한 번만 계산한다.
  const trimmedInput = stripEdgeSpace(input);
  const matches = trimmedInput !== '' && trimmedInput === stripEdgeSpace(workspaceName);

  return (
    <>
      <AlertDialogHeader>
        <AlertDialogTitle>{targetName}에게 소유권을 넘길까요?</AlertDialogTitle>
        <AlertDialogDescription asChild>
          <ul className="list-disc space-y-1 pl-4 text-left">
            <li>새 소유자에게 ADMIN 역할이 없으면 부여합니다</li>
            <li>나는 소유자에서 빠지지만 ADMIN 역할은 유지합니다</li>
            <li>되돌리려면 새 소유자가 다시 넘겨야 합니다</li>
            <li>감사 기록에 남습니다</li>
          </ul>
        </AlertDialogDescription>
      </AlertDialogHeader>
      <div className="space-y-2">
        <Label htmlFor="transfer-ownership-confirm">확인하려면 워크스페이스 이름을 입력하세요</Label>
        <Input
          id="transfer-ownership-confirm"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder={workspaceName}
          autoComplete="off"
        />
      </div>
      <AlertDialogFooter>
        <AlertDialogCancel>취소</AlertDialogCancel>
        <OnceAlertDialogAction variant="destructive" disabled={!matches} onClick={() => onConfirm(trimmedInput)}>
          소유권 넘기기
        </OnceAlertDialogAction>
      </AlertDialogFooter>
    </>
  );
}
