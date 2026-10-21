/**
 * ProactiveWriteNotice — 스마트 작업 쓰기 권한(proactive:write)이 없는 사용자에게 보이는 한 줄 안내 (WD-133).
 *
 * 만들기·복제·편집·삭제·지금 실행 버튼을 숨긴 이유를 알려 준다. 화면 다섯 곳이 같은 문구를 쓰므로 여기 한 곳에 둔다.
 * 쓰기 제한 안내는 데이터셋 매핑 탭(WD-131)처럼 평문 한 줄로 둔다 — 자물쇠 배너(RestrictedNotice)는 읽기 제한용이다.
 */
interface ProactiveWriteNoticeProps {
  /** 안내의 주어 — 작업 화면은 「스마트 작업」, 양식 화면은 「리포트 양식」 */
  subject: '스마트 작업' | '리포트 양식';
  testId: string;
}

export function ProactiveWriteNotice({ subject, testId }: ProactiveWriteNoticeProps) {
  return (
    <p className="text-sm text-muted-foreground" data-testid={testId}>
      {subject}을 만들거나 바꾸려면 스마트 작업 쓰기 권한이 필요합니다. 관리자에게 권한을 요청하세요.
    </p>
  );
}
