import { Lock, RotateCcw } from 'lucide-react';
import type { ReactNode } from 'react';

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '../../components/ui/alert-dialog';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
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
  if (state === 'builtin-default') {
    // "기본값 사용 중"(DB 행 상속)과 구분한다 — 이 키는 플랫폼에 시드된 행이 없고, 코드에 박힌
    // 기본값이 적용되고 있다. "기본값 없음"이라고 하면 아무 값도 적용되지 않는다는 거짓이 된다.
    return <Badge variant="outline">내장 기본값</Badge>;
  }
  if (state === 'no-default') {
    // DB 행도 코드 기본값도 없는 경우 — 정말 적용되는 값이 없다.
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
 * 탭 전체가 플랫폼 전용일 때(임베딩 4/4) 쓰는 배너.
 * 이메일 탭은 P7-c1 에서 6키가 전부 테넌트 오버라이드 허용으로 열려 이 배너를 뗐다 — 필드별
 * 배지 체계와 "탭 전체 잠금" 카피는 공존할 수 없다.
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

/**
 * 재정의 해제 버튼 + 확인 다이얼로그.
 *
 * - 배치 저장(PUT)에 얹지 않고 즉시 DELETE 를 호출한다 — 오버라이드 삭제는 "빈 문자열 저장"과
 *   다른 연산이라 PUT payload 로 표현할 수 없다.
 * - `DeleteConfirmDialog` 래퍼를 쓰지 않는 이유: 고정 문구가 "되돌릴 수 없습니다"인데 재정의
 *   해제는 언제든 다시 재정의할 수 있어 사실과 어긋난다. 그래서 원본 프리미티브로 문구를 짠다.
 * - 확인을 받는 이유: system_prompt 처럼 공들여 입력한 긴 텍스트가 즉시 사라질 수 있다.
 *
 * P7-c1 에서 `SettingsPage` 안의 로컬 함수에서 이 파일로 옮겼다 — 이메일 탭도 같은 버튼을 쓰는데,
 * 복사하면 확인 문구와 동작이 두 벌이 되어 한쪽만 고치는 사고가 난다.
 *
 * <b>문구를 prop 으로 받는 이유(Task 5)</b>: SMTP 연결 5키는 <b>번들 단위</b>로 해제되므로 고정
 * 문구의 "이 항목"(단수)이 거짓이 된다. 그렇다고 번들 전용 컴포넌트를 복사해 만들면 확인 동작이
 * 다시 두 벌이 되어, 위 문단이 경고하는 그 사고가 난다. 그래서 <b>기본값이 있는 선택 prop</b>으로
 * 넓힌다 — AI 탭 호출부는 아무것도 넘기지 않고 동작이 그대로다.
 */
export function ClearOverrideButton({
  settingKey,
  onConfirm,
  disabled,
  label = '재정의 해제',
  dialogTitle = '재정의 해제',
  dialogDescription = '이 항목의 테넌트 설정이 삭제되고 플랫폼 기본값으로 즉시 전환됩니다. 지금 입력된 값은 사라지며, 필요하면 언제든 다시 재정의할 수 있습니다.',
}: {
  settingKey: string;
  onConfirm: (key: string) => void;
  disabled?: boolean;
  /** 버튼에 표시할 문구. 번들 해제는 범위를 라벨에 박아 누르기 전에 알린다. */
  label?: string;
  dialogTitle?: string;
  dialogDescription?: ReactNode;
}) {
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button type="button" variant="ghost" size="sm" disabled={disabled}>
          <RotateCcw className="h-3.5 w-3.5" />
          {label}
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{dialogTitle}</AlertDialogTitle>
          <AlertDialogDescription>{dialogDescription}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>취소</AlertDialogCancel>
          {/* destructive 색을 쓰지 않는다 — 되돌릴 수 있는 동작이다 */}
          <AlertDialogAction onClick={() => onConfirm(settingKey)}>되돌리기</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
