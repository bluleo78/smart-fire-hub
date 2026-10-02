import { Trash2 } from 'lucide-react';
import { useState } from 'react';

import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription,
  AlertDialogFooter, AlertDialogHeader, AlertDialogTitle, AlertDialogTrigger,
} from '../../../components/ui/alert-dialog';
import { Button } from '../../../components/ui/button';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '../../../components/ui/tooltip';
import type { UserDetailResponse } from '../../../types/user';

/**
 * "워크스페이스에서 제거" 위험 구역(와이어프레임 ④).
 *
 * <p>DeleteConfirmDialog 대신 AlertDialog 를 직접 쓰는 이유: 그 컴포넌트 문구는 "삭제하면 복구할 수 없다"
 * 인데, 여기서는 계정·데이터가 남고 멤버십만 지워진다 — 잘못된 경고는 오히려 위험하다.
 * 비활성 버튼의 툴팁: Radix 는 disabled 버튼에서 포인터/포커스 이벤트를 받지 못하므로 span(tabIndex=0)으로
 * 감싸 키보드·마우스 모두 이유를 읽을 수 있게 한다.
 */
export function MemberDangerZone({
  user, lockReason, onConfirm,
}: { user: UserDetailResponse; lockReason: string | null; onConfirm: () => Promise<void> }) {
  const [pending, setPending] = useState(false);

  const removeButton = (
    <Button variant="destructive" size="sm" disabled={lockReason !== null || pending}>
      <Trash2 className="h-4 w-4" aria-hidden="true" />
      제거
    </Button>
  );

  return (
    <section aria-labelledby="member-danger-title" className="space-y-3 rounded-lg border border-destructive p-6">
      <h2 id="member-danger-title" className="text-base leading-6 font-semibold text-destructive">
        워크스페이스에서 제거
      </h2>
      {/* 모바일(<sm)에선 설명이 버튼에 눌려 3줄로 쪼개지지 않게 세로로 쌓고, sm 이상에서 한 줄 배치(12-responsive). */}
      <div className="flex flex-col items-start gap-3 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
        <p className="text-sm break-keep text-muted-foreground">
          이 워크스페이스의 멤버십과 역할을 삭제합니다. 계정과 만든 데이터는 남습니다.
        </p>
        {lockReason ? (
          // AppLayout 의 Provider 는 Outlet 바깥 영역이라 페이지에선 쓸 수 없다 — 기존 관례대로 로컬 Provider.
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                {/* 포커스 가능한 래퍼라 이름이 필요하다 — role 없는 span 엔 aria-label 이 금지(ARIA 1.2)라 group 으로 이름을 단다. */}
                <span
                  tabIndex={0}
                  role="group"
                  aria-label={lockReason}
                  data-testid="remove-lock-trigger"
                  className="inline-flex rounded-md focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring">
                  {removeButton}
                </span>
              </TooltipTrigger>
              <TooltipContent>{lockReason}</TooltipContent>
            </Tooltip>
          </TooltipProvider>
        ) : (
          <AlertDialog>
            <AlertDialogTrigger asChild>{removeButton}</AlertDialogTrigger>
            <AlertDialogContent size="sm">
              <AlertDialogHeader>
                <AlertDialogTitle>워크스페이스에서 제거</AlertDialogTitle>
                <AlertDialogDescription>
                  {user.name}({user.username}) 님의 이 워크스페이스 멤버십과 역할을 삭제합니다. 계정과 만든 데이터는 남습니다.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>취소</AlertDialogCancel>
                <AlertDialogAction variant="destructive" disabled={pending}
                  onClick={() => { setPending(true); void onConfirm().finally(() => setPending(false)); }}>
                  제거
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        )}
      </div>
    </section>
  );
}
