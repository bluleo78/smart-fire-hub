import { Eye } from 'lucide-react';
import type { ReactNode } from 'react';

import { cn } from '@/lib/utils';

/**
 * 남이 공유한 분석 자원(차트·저장 쿼리)을 열었을 때 상단에 두는 읽기 전용 안내(WD-196).
 *
 * 왜: 서버는 수정·삭제를 작성자에게만 허용한다. 저장 버튼을 숨기기만 하면 「왜 저장이 없지?」가 되므로, 누가 공유한 것인지와
 * 지금 할 수 있는 일(보기·실행, 쿼리는 복제)을 한 줄로 알린다. 정적 안내라 role="status" 는 붙이지 않는다(중복 낭독 방지 —
 * ChartConfigWithheldNotice 와 같은 판단). action 이 있으면(쿼리 「복제」) 안내 오른쪽에 둔다.
 */
export function SharedReadOnlyNotice({
  message,
  action,
  className,
  testId,
}: {
  /** 안내 문장 — 화면마다 할 수 있는 일이 달라 호출부가 정한다. */
  message: string;
  /** 안내 옆에 둘 버튼(예: 쿼리 복제). 없으면 안내만 보인다. */
  action?: ReactNode;
  className?: string;
  testId?: string;
}) {
  return (
    <div
      data-testid={testId}
      className={cn(
        'flex flex-wrap items-center gap-x-3 gap-y-2 rounded-md border bg-muted px-3 py-2 text-sm text-muted-foreground',
        className,
      )}
    >
      <Eye className="h-4 w-4 shrink-0" aria-hidden="true" />
      <p className="flex-1 min-w-0">{message}</p>
      {action}
    </div>
  );
}
