import { Lock, RotateCcw, Trash2 } from 'lucide-react';
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
 * 번들 안에서 <b>비어 있는 항목</b>임을 알리는 정적 노트(디자인 스펙 §1-1 신설 어휘).
 *
 * 배지가 아니라 노트인 이유: 배지 문자열은 어휘를 영구히 넓히는 부담을 만들지만, 노트는 그
 * 화면 안에 머문다. 빈 입력창은 시각적으로 "아직 안 채운 칸"과 구별되지 않으므로 이 텍스트가
 * 유일한 전달 경로다 — 그래서 각 입력의 `aria-describedby` 에 포함한다.
 *
 * <b>원래 `SmtpSettingsTab` 안의 지역 컴포넌트였다.</b> "이 화면 안에 머문다"는 전제가 AI 자격증명
 * 번들이 생기면서 깨졌다 — 같은 서버 규칙(번들 원자 해석)이 두 탭에 같은 화면 문제를 만든다.
 * 복사하면 문구가 두 벌이 되어 한쪽만 고치는 사고가 나므로 공용 자리로 올린다.
 */
export function EmptyInBundleNote({
  id,
  show,
  extra,
}: {
  id: string;
  show: boolean;
  /**
   * 비어 있을 때 <b>실제로 적용되는 값</b>이 따로 있는 키만 채운다. SMTP 포트가 그렇다 —
   * 소비자 3곳이 전부 빈 포트를 587 로 대체하므로, 이 문장이 없으면 "포트가 없어서 못 나간다"로
   * 읽힌다.
   */
  extra?: string;
}) {
  if (!show) return null;
  return (
    <p id={id} className="text-sm text-muted-foreground">
      이 항목은 비어 있습니다 — 플랫폼 값이 사용되지 않습니다.{extra ? ` ${extra}` : ''}
    </p>
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
 * 넓힌다. 지금 문구를 넘기는 호출부는 셋이다 — SMTP 연결 그룹 해제, AI 자격증명 그룹 해제,
 * AI "저장된 OAuth 토큰 삭제". 아무것도 넘기지 않는 개별 키 해제(AI 의 `ai.model`·숫자 필드,
 * SMTP 의 `smtp.from_address`)는 기본 문구로 동작이 그대로다.
 *
 * <b>`destructive` 가 <u>선택</u> prop 인 이유(#390 item 5)</b>: 위 문단이 넓힌 세 호출부 중
 * "저장된 OAuth 토큰 삭제" 하나만은 <b>되돌릴 수 없다</b> — 서버가 평문을 절대 내려주지 않아
 * 화면에 다시 칠 원본이 없고, 빈 값 PUT 이 끝나면 옛 토큰은 어디에도 남지 않는다. 그런데 그
 * 호출부는 `confirmLabel` 만 넘겨서 <b>되돌리기 아이콘(`RotateCcw`) + 비파괴 확인 버튼</b>을
 * 그대로 달고 있었다 — 복구 불가 조작이 "실행 취소"처럼 보이는 <b>거짓 어포던스</b>다.
 *
 * 그렇다고 이 표현을 기본값으로 만들 수는 없다. 나머지 호출부(SMTP 개별/그룹 해제, AI 개별/그룹
 * 해제)는 <b>전부 되돌릴 수 있는</b> 재정의 해제라, 파괴적 표현을 기본으로 깔면 위 "destructive
 * 색을 쓰지 않는다 — 되돌릴 수 있는 동작이다" 판단이 통째로 뒤집힌다. 그래서 <b>기본값 false 의
 * opt-in</b> 이고, 넘기지 않는 호출부의 렌더 결과는 글자 하나 바뀌지 않는다.
 *
 * <b>`settingKey` 를 받지 않는 이유(#390 item 6)</b>: 예전 시그니처는 `settingKey: string` 을 받아
 * `onConfirm(settingKey)` 로 되돌려 줬다. 그런데 번들 해제에는 되돌려 줄 <b>단일 키가 존재하지
 * 않아</b> 호출부가 `"smtp.connection"` 이라는 <b>실재하지 않는 키</b>를 넘기고 있었고, 그것이
 * 동작한 이유는 그 핸들러가 인자를 무시했기 때문뿐이다. prop 이름은 "실재하는 설정 키"를 약속하는데
 * 값은 허구였다 — 타입은 통과하고 사람만 속는 형태다. 인자 없는 `onConfirm` 으로 좁히면 <b>허구를
 * 넘길 자리 자체가 사라진다</b>. 어느 키를 지울지는 호출부가 이미 알고 있으므로 클로저로 묶으면 되고,
 * 그 편이 "이 버튼이 무엇을 지우는가"를 호출부에서 읽게 만든다.
 */
export function ClearOverrideButton({
  onConfirm,
  disabled,
  label = '재정의 해제',
  dialogTitle = '재정의 해제',
  dialogDescription = '이 항목의 테넌트 설정이 삭제되고 플랫폼 기본값으로 즉시 전환됩니다. 지금 입력된 값은 사라지며, 필요하면 언제든 다시 재정의할 수 있습니다.',
  confirmLabel = '되돌리기',
  destructive = false,
}: {
  /** 확인 다이얼로그를 지난 뒤 실행할 동작. 무엇을 지우는지는 호출부가 클로저로 묶는다. */
  onConfirm: () => void;
  disabled?: boolean;
  /** 버튼에 표시할 문구. 번들 해제는 범위를 라벨에 박아 누르기 전에 알린다. */
  label?: string;
  dialogTitle?: string;
  dialogDescription?: ReactNode;
  /**
   * 확인 버튼 문구. 기본값 '되돌리기' 는 <b>상속으로 되돌리는</b> 해제에만 맞다 — AI 탭의
   * "저장된 OAuth 토큰 삭제"는 상속으로 돌아가는 것이 아니라 테넌트 값을 빈 값으로 덮는
   * 조작이라(번들은 그대로 재정의 상태로 남는다) '되돌리기' 가 거짓이 된다. 그 한 단어 때문에
   * 다이얼로그 컴포넌트를 복사하면 확인 동작이 다시 두 벌이 된다 — 그래서 문구만 넓힌다.
   */
  confirmLabel?: string;
  /**
   * 되돌릴 수 없는 조작임을 <b>표현</b>으로도 알린다 — 휴지통 아이콘 + 위험색 트리거 +
   * `destructive` 확인 버튼. 문구(`confirmLabel`)만 '삭제' 로 바꾸고 표현을 그대로 두면,
   * 복구 불가 조작이 되돌리기 아이콘과 비파괴 버튼을 달고 나온다.
   *
   * <b>기본값이 false 여야 한다</b>: 재정의 해제는 언제든 다시 재정의할 수 있는 <b>되돌릴 수
   * 있는</b> 조작이라 위험색을 쓰면 안 된다(아래 확인 버튼 주석 참고). 이 prop 을 넘기지 않는
   * 호출부는 SMTP 개별·그룹 해제와 AI 개별·그룹 해제이고, 그 넷의 렌더 결과는 변하지 않는다.
   */
  destructive?: boolean;
}) {
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={disabled}
          className={destructive ? 'text-destructive hover:text-destructive' : undefined}
        >
          {/* 아이콘도 갈린다 — 색은 색각 이상 사용자에게 전달되지 않으므로, 위험색 하나로는
              "되돌리기"와 "삭제"가 같은 모양으로 보인다(`10-accessibility.md` 색상 단독 전달 금지). */}
          {destructive ? <Trash2 className="h-3.5 w-3.5" /> : <RotateCcw className="h-3.5 w-3.5" />}
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
          {/* 기본은 destructive 색을 쓰지 않는다 — 재정의 해제는 되돌릴 수 있는 동작이다.
              `destructive` 를 켠 호출부(복구 불가 삭제)만 위험색을 받는다. */}
          <AlertDialogAction variant={destructive ? 'destructive' : 'default'} onClick={onConfirm}>
            {confirmLabel}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
