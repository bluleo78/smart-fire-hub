import { RestrictedNotice } from '@/components/ui/restricted-notice';
import { REPORT_LEVEL_BLOCKED_MESSAGE } from '@/lib/api-error';

/**
 * ReportLevelBlockedNotice — 리포트 등급이 작성자의 현재 보안 등급보다 높아 열 수 없을 때(403 REPORT_SECURITY_LEVEL_BLOCKED) 보이는 안내 (WD-198).
 *
 * 조회 권한 부족 안내(ProactiveReadForbiddenNotice)와 구분한다 — 권한을 요청할 문제가 아니라, 리포트를 만들 때 읽은 데이터의 등급을
 * 지금은 볼 수 없다는 뜻이다. 다시 시도해도 같으므로 오류색·재시도 없이 자물쇠 안내만 보인다(RestrictedNotice).
 */
export function ReportLevelBlockedNotice({ testId }: { testId: string }) {
  return (
    <div className="flex justify-center py-12">
      <RestrictedNotice message={REPORT_LEVEL_BLOCKED_MESSAGE} className="max-w-md" data-testid={testId} />
    </div>
  );
}
