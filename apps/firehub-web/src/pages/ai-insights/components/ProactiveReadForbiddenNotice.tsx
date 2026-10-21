import { RestrictedNotice } from '@/components/ui/restricted-notice';

/**
 * ProactiveReadForbiddenNotice — 스마트 작업 조회 권한(proactive:read)이 없어 조회가 403 일 때 보이는 자물쇠 안내 (WD-137).
 *
 * 조회 실패를 「리포트 없음」·「찾을 수 없습니다」·「잠시 후 다시 시도」로 그리면 사용자는 권한 문제인 줄 모른다 —
 * 다시 시도해도 같으므로 재시도 대신 권한 안내를 보인다(WD-122·WD-126 선례). 읽기 제한이라 RestrictedNotice 를 쓰고,
 * 여러 화면이 같은 문구를 쓰므로 쓰기 안내(ProactiveWriteNotice)처럼 여기 한 곳에 둔다.
 */
interface ProactiveReadForbiddenNoticeProps {
  /** 안내의 주어 — 화면이 보여 주려던 대상 */
  subject: '스마트 작업' | '리포트 양식' | '리포트' | '실행 결과' | '실행 이력';
  testId: string;
}

/** 주어별 목적격 조사 — 받침 유무로 「을/를」이 갈린다. */
const OBJECT_PARTICLE: Record<ProactiveReadForbiddenNoticeProps['subject'], string> = {
  '스마트 작업': '을',
  '리포트 양식': '을',
  리포트: '를',
  '실행 결과': '를',
  '실행 이력': '을',
};

export function ProactiveReadForbiddenNotice({ subject, testId }: ProactiveReadForbiddenNoticeProps) {
  return (
    <div className="flex justify-center py-12">
      <RestrictedNotice
        message={`${subject}${OBJECT_PARTICLE[subject]} 보려면 스마트 작업 조회 권한이 필요합니다. 관리자에게 권한을 요청하세요.`}
        className="max-w-md"
        data-testid={testId}
      />
    </div>
  );
}
