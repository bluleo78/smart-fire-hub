import { Lock } from 'lucide-react';
import type { ReactNode } from 'react';

import { Badge } from '../../components/ui/badge';
import { InlineBanner } from '../../components/ui/inline-banner';
import { Label } from '../../components/ui/label';
import type { SettingFieldState } from '../../lib/settings-fields';

/**
 * 설정 필드의 상속/재정의/잠금 상태 배지.
 *
 * 네 상태 모두 배지를 "항상" 렌더한다 — 배지의 있음/없음으로 상태를 표현하면 사용자가
 * "표시가 없는 것"과 "상속 중"을 구별할 수 없기 때문이다. 대비는 텍스트가 만든다.
 * 잠금은 색이 아니라 아이콘+텍스트+비활성 입력+고정 안내문 네 겹으로 전달해
 * `10-accessibility.md` 의 "색상 단독 전달 금지"를 충족한다.
 */
export function SettingStateBadge({ state }: { state: SettingFieldState }) {
  if (state === 'locked') {
    return (
      <Badge variant="outline" aria-label="플랫폼 전용: 이 테넌트에서 편집할 수 없음">
        <Lock className="h-3 w-3" /> 플랫폼 전용
      </Badge>
    );
  }
  if (state === 'overridden') {
    return <Badge variant="info">테넌트 재정의 적용됨</Badge>;
  }
  if (state === 'no-default') {
    // "기본값 사용 중"과 구분한다 — 뒤에 실제 값이 적용되고 있는 것처럼 오해하면 안 된다.
    // 이 키는 플랫폼에도 시드된 행이 없다.
    return <Badge variant="outline">기본값 없음</Badge>;
  }
  return <Badge variant="outline">기본값 사용 중</Badge>;
}

/**
 * 라벨 + 상태 배지(+ 우측 액션) 한 줄.
 * `action` 은 재정의 해제 버튼처럼 상태와 함께 붙는 조작만 넣는다.
 */
export function SettingFieldLabel({
  htmlFor,
  state,
  action,
  children,
}: {
  htmlFor: string;
  state: SettingFieldState;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Label htmlFor={htmlFor}>{children}</Label>
      <SettingStateBadge state={state} />
      {action}
    </div>
  );
}

/**
 * 잠긴 필드 아래에 붙는 고정 안내문.
 * 툴팁이 아니라 정적 텍스트인 이유: 호버할 수 없는 사용자와 스크린리더도 잠금 사유를 알아야 한다.
 */
export function PlatformLockedNote() {
  return <p className="text-sm text-muted-foreground">플랫폼 운영자만 변경할 수 있는 항목입니다.</p>;
}

/**
 * 탭 전체가 플랫폼 전용일 때(이메일 6/6, 임베딩 4/4) 쓰는 배너.
 * 탭 상단과 하단(원래 저장 버튼 자리) 두 곳에 배치해, 스크롤 위치와 무관하게 편집 불가를 알린다.
 *
 * "다른 화면에서 변경하세요"/"관리자에게 문의" 류 문구는 넣지 않는다 — 운영자 전용 화면도,
 * 문의 경로도 아직 정의되어 있지 않아 없는 기능을 가리키게 된다.
 */
export function PlatformLockedBanner({ children }: { children: ReactNode }) {
  return (
    <InlineBanner variant="info" icon={<Lock />} title="플랫폼 전용 설정">
      {children}
    </InlineBanner>
  );
}
