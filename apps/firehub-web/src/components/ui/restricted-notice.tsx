import { Lock } from 'lucide-react';

import { cn } from '@/lib/utils';

interface RestrictedNoticeProps {
  /** 사용자에게 보여 줄 제한 안내 문장. */
  message: string;
  className?: string;
  'data-testid'?: string;
}

/**
 * 권한 때문에 내용을 보여 줄 수 없을 때의 안내 — 실행 기록 오류 가림(WD-27)과 지식그래프 읽기 제한(WD-28)이 같이 쓴다.
 *
 * 오류가 아니라 권한 상태이므로 오류색·role=alert·재시도·토스트를 쓰지 않는다(디자인 검토). muted 배경 + 자물쇠 + 문장만.
 * 두 화면의 시각 언어를 한곳에서 맞추려고 공용으로 뺐다 — 한쪽만 고치면 "같은 제한이 다르게 보이는" 일이 생긴다.
 */
export function RestrictedNotice({ message, className, 'data-testid': testId }: RestrictedNoticeProps) {
  return (
    <p
      className={cn('flex items-start gap-1.5 rounded bg-muted p-3 text-xs text-muted-foreground', className)}
      data-testid={testId}
    >
      <Lock className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
      {/* break-keep: 한국어 안내문이 어절 중간("포함되/어")에서 잘리지 않게 한다 — CJK 에만 작용해 라틴 긴 토큰은 기존대로 줄바꿈된다. */}
      <span className="break-keep">{message}</span>
    </p>
  );
}
