import { useCallback, useState } from 'react';
import { toast } from 'sonner';

import { settingsApi } from '../api/settings';
import type { SettingFieldState } from '../lib/settings-fields';
import type { SettingsOverrideForm } from './useSettingsOverrideForm';
import { useSettingsOverrideForm } from './useSettingsOverrideForm';

// `interface` 가 아니라 `type` 인 이유는 `AISettingsForm` 과 같다 — 훅의 제약을 만족시키는
// 암묵적 인덱스 시그니처는 타입 별칭에만 붙는다. keyof 는 그대로 6개 리터럴이다.
export type SmtpForm = {
  'smtp.host': string;
  'smtp.port': string;
  'smtp.username': string;
  'smtp.password': string;
  'smtp.starttls': string;
  'smtp.from_address': string;
};

// 조회 전 초기값. starttls 만 'true' 인 이유: Switch 는 빈 문자열을 표현할 수 없어 조회 전에도
// 켜짐/꺼짐 중 하나를 그려야 하고, 플랫폼 기본값이 켜짐이다.
const EMPTY: SmtpForm = {
  'smtp.host': '',
  'smtp.port': '',
  'smtp.username': '',
  'smtp.password': '',
  'smtp.starttls': 'true',
  'smtp.from_address': '',
};

// 저장이 거부된 필드를 이름으로 지목하는 데 쓴다 — "어떤 필드가 문제인지" 말해주지 않으면
// 사용자가 무엇을 고쳐야 할지 알 수 없다. 6키 전부를 담는다(빠진 키는 토스트에 undefined 가 찍힌다).
const FIELD_LABELS: Record<keyof SmtpForm, string> = {
  'smtp.host': 'SMTP 호스트',
  'smtp.port': '포트',
  'smtp.username': '사용자 이름',
  'smtp.password': '비밀번호',
  'smtp.starttls': 'STARTTLS 사용',
  'smtp.from_address': '발신자 주소',
};

/**
 * 빈 값으로 저장해도 되는 키의 <b>화이트리스트</b>.
 *
 * 나머지 키를 비운 채 저장하면 거부한다 — 값의 유효성 문제가 아니라 <b>의도 불일치</b>다.
 * 비운 사람은 대개 "플랫폼 값으로 되돌리기"를 뜻하는데, 빈 값을 그냥 페이로드에서 빼고 저장하면
 * "저장했다"면서 아무것도 쓰지 않고 dirty 까지 지워, 사용자는 반영된 줄 알고 떠나는데 옛 오버라이드가
 * 그대로 적용된다. 되돌리기의 정식 조작은 "재정의 해제"(DELETE)다.
 *
 * <b>username/password 만 예외인 이유</b>: 인증 없는 릴레이가 합법적 최종 상태라 "비어 있음"이
 * 사용자가 원하는 결과일 수 있다. "SMTP 는 전부 예외"로 뭉뚱그리면 `smtp.host` 를 실수로 비웠을 때도
 * 조용히 통과한다.
 *
 * <b>`smtp.starttls` 를 넣지 않는 이유</b>: Switch 라 값이 항상 'true'/'false' 이고 빈 값이 될 수
 * 없다 — 목록에 넣으면 도달 불가한 방어 코드가 된다.
 * <b>`smtp.port` 를 넣지 않는 이유</b>: 비운 채 저장하면 위 거부 경로로 가는 것이 맞다. 범위 검증은
 * `validate()` 가 따로 담당한다.
 */
const BLANK_ALLOWED_KEYS: ReadonlySet<keyof SmtpForm> = new Set(['smtp.username', 'smtp.password']);

/**
 * SMTP <b>연결 번들</b> 5키 — 백엔드 `SettingsService.SMTP_CONNECTION_KEYS` 와 같은 집합이다.
 *
 * `{호스트, 포트, 사용자 이름, 비밀번호, STARTTLS}` 는 <b>한 서버에 대한 한 벌의 접속 정보</b>라
 * 서버가 이 5키를 <b>원자적으로</b> 해석한다: 하나라도 재정의되면 5키 전부가 테넌트 평면에서
 * 해석되고, 행이 없는 키는 플랫폼 값이 아니라 빈 값이 된다. 키 단위로 상속하면 A 서버의 주소와
 * B 서버의 자격증명이 섞여 <b>전 테넌트 공용 SMTP 계정</b>이 테넌트가 지정한 호스트로 나간다.
 *
 * <b>화면이 이 목록을 갖는 이유는 해석이 아니라 배치다.</b> 해석의 권위는 전적으로 서버 플래그이고
 * (아래 `resolveConnectionGroupState` 는 서버가 내려준 `overridden` 만 읽는다), 이 상수가 정하는
 * 것은 "어느 필드가 그룹 테두리 안에 들어가는가" 뿐이다. `smtp.from_address` 는 접속과 무관한
 * 표시 값이라 번들이 아니며, 그룹 밖에서 개별 배지·개별 해제 버튼을 유지한다.
 */
export const SMTP_CONNECTION_KEYS: (keyof SmtpForm)[] = [
  'smtp.host',
  'smtp.port',
  'smtp.username',
  'smtp.password',
  'smtp.starttls',
];

// 포트 범위는 백엔드 `SettingsService.validateSmtpPort`(1~65535)와 반드시 같아야 한다 —
// 어긋나면 한쪽이 통과시킨 값을 다른 쪽이 거부해 "저장했는데 400" 또는 그 반대가 된다.
export const PORT_MIN = 1;
export const PORT_MAX = 65535;

/**
 * 연결 번들의 그룹 상태. 해석이 번들 단위인데 배지가 필드 단위면 배지가 거짓말을 한다 —
 * 호스트가 재정의된 상태에서 `비밀번호` 옆의 `기본값 사용 중` 은 "그 플랫폼 비밀번호는 쓰이지
 * 않는다"는 사실과 정면으로 어긋난다.
 *
 * <b>`locked` 가 하나라도 섞이면 그룹 전체가 `locked` 다(fail-closed).</b> `SettingsOverridePolicy`
 * 가 6키를 함께 열었으므로 오늘 이 조합은 오지 않지만, 서버가 5키 중 일부만
 * `tenantEditable=false` 로 내려주는 모순 상태에서 나머지 4키를 편집 가능하게 그리면 사용자가
 * 저장할 수 없는 폼을 채우게 된다. 모호하면 잠그는 쪽이다.
 *
 * <b>`overridden` 판정은 서버 플래그만 읽는다.</b> 서버가 번들 재정의 상태에서 5키 전부를
 * `overridden=true` + 행 없는 키는 `value=''` 로 내려주므로("이 키는 테넌트 평면에서 해석된다"가
 * 플래그의 뜻이다), 화면이 값의 빈 여부로 상태를 다시 추론할 일이 없다. 서버가 단일 권위여야
 * web 이 파생을 틀려도 거짓말이 나가지 않는다.
 *
 * <b>모듈 레벨 순수 함수인 이유</b>: 훅의 `resolveState` 로 넘기려면 렌더마다 새 참조가 되지
 * 않아야 한다.
 */
function resolveConnectionGroupState(
  fieldState: (key: keyof SmtpForm) => SettingFieldState,
): 'locked' | 'overridden' | 'inherited' {
  if (SMTP_CONNECTION_KEYS.some((key) => fieldState(key) === 'locked')) return 'locked';
  if (SMTP_CONNECTION_KEYS.some((key) => fieldState(key) === 'overridden')) return 'overridden';
  return 'inherited';
}

/**
 * 공통 훅에 넘기는 상태 치환기 — 연결 5키는 그룹 상태, 나머지는 개별 상태다.
 * 배지·disabled·저장 대상·dirty 가 <b>모두</b> 이 하나를 거친다. 두 갈래를 호출부마다 다시
 * 조합하면 "화면은 잠갔는데 저장은 보낸다" 같은 어긋남이 생긴다.
 */
function resolveSmtpState(
  key: keyof SmtpForm,
  fieldState: (k: keyof SmtpForm) => SettingFieldState,
): SettingFieldState {
  return SMTP_CONNECTION_KEYS.includes(key)
    ? resolveConnectionGroupState(fieldState)
    : fieldState(key);
}

/** 이메일(SMTP) 탭이 그리는 데 필요한 전부 — 공통 폼 상태 기계 + 번들 레이어. */
export interface SmtpSettingsFormState {
  /** 공통 폼 상태 기계. 번들 레이어는 이 위에 얹힌다. */
  base: SettingsOverrideForm<SmtpForm>;
  isSaving: boolean;
  staleNotice: string | null;
  connectionGroupState: 'locked' | 'overridden' | 'inherited';
  bundleTransitionPending: boolean;
  isEmptyInBundle: (key: keyof SmtpForm) => boolean;
  testNotice: string | null;
  handleSave: () => Promise<void>;
  handleClearConnectionBundle: () => Promise<void>;
}

/**
 * 이메일(SMTP) 탭의 <b>번들 레이어</b> — 공통 폼 상태 기계 위에 연결 5키 원자성을 얹는다.
 *
 * <b>왜 컴포넌트가 아니라 여기서 상태를 만드는가</b>: 이 훅의 인스턴스는 `SettingsPage` 가
 * 소유한다. Radix `TabsContent` 는 비활성 탭을 언마운트하므로, 탭 컴포넌트가 상태를 소유하면
 * 탭을 바꾸는 순간 미저장 편집이 경고 없이 사라진다 — AI 탭은 상태를 페이지가 소유해 살아남는
 * 비대칭이었고, 잃는 쪽만 P7-c1 이 새로 연 탭이었다(#390-2b).
 *
 * <b>`staleNotice` 도 여기 있다.</b> 값만 살리고 "화면이 낡았다" 안내를 탭에 남겨 두면, 탭을
 * 왕복한 사용자에게 값은 보이는데 경고만 사라진다 — 이 밴드 계열이 계속 잡아 온 "화면이 조용히
 * 거짓말한다"의 또 다른 판본이다.
 *
 * <b>해제 진행 표시는 공통 훅의 `isClearing` 하나를 쓴다.</b> 번들 해제는 개념상 훅 밖이지만
 * 화면에서 잠그는 버튼은 개별 해제와 <b>같은 것들</b>이라, 별도 플래그를 두면 두 상태가 갈라져
 * "해제 중인데 해제 버튼이 눌린다"가 생긴다. 그래서 공통 훅이 `setIsClearing` 을 내준다.
 */
export function useSmtpSettingsForm(): SmtpSettingsFormState {
  const [isSaving, setIsSaving] = useState(false);
  /**
   * <b>"지금 화면이 서버 상태와 다를 수 있다"</b>를 알리는 지속 안내. 토스트로 끝내지 않는 이유:
   * 둘 다 사용자가 다시 조작해야 하는 상태인데 토스트는 사라지고 스크린리더 사용자가 놓칠 수
   * 있다(§6). 두 흐름이 한 슬롯을 공유한다 — 번들 해제 **부분 실패**와 저장 후 **재조회 실패**.
   */
  const [staleNotice, setStaleNotice] = useState<string | null>(null);

  // 안내를 지우는 자리를 한 곳으로 묶는다 — 화면이 실제로 새로워진 그 지점(훅의 메타 갱신
  // 직후)에서만 지운다. 호출부마다 지우면 하나를 빠뜨리는데, 실제로 빠뜨렸다: 예전에는
  // `handleClearOverride` 와 최초 조회가 안 지워서 "새로고침하세요" 가 성공적인 단일 키 해제 +
  // 재조회 뒤에도 살아남았다. 조건이 사라진 뒤에도 남는 안내는 "화면이 조용히 거짓말한다"의
  // 또 다른 판본이다.
  const clearStaleNotice = useCallback(() => setStaleNotice(null), []);

  /**
   * 이 탭의 <b>필드 단위</b> 폼 상태 기계 — AI 탭과 같은 훅이다.
   *
   * <b>번들(연결 5키) 개념은 그 훅에 없다.</b> 훅이 여는 유일한 구멍이 `resolveState` 이고,
   * 거기에 `resolveSmtpState` 를 끼워 "연결 5키는 그룹 상태로 판정"을 얹는다. 그룹 배지·그룹
   * 해제 버튼·번들 전환 재시드·낡음 안내·연결 테스트 안내는 전부 이 파일에 남아 훅의 반환값
   * <b>위에 얹히는 레이어</b>가 된다.
   */
  const base = useSettingsOverrideForm<SmtpForm>({
    prefix: 'smtp',
    defaults: EMPTY,
    blankAllowed: BLANK_ALLOWED_KEYS,
    resolveState: resolveSmtpState,
    onMetaRefreshed: clearStaleNotice,
  });

  const {
    settings,
    form,
    original,
    resyncFromServer,
    setErrors,
    setIsClearing,
    fieldState,
    effectiveState,
    hasChanges,
    buildChangedPayload,
    commitSaved,
    refreshMeta,
  } = base;

  /**
   * 연결 5키의 폼 값을 <b>서버 해석</b>으로 다시 시드하는 updater 를 만든다.
   *
   * 저장 후(번들 전환)와 번들 해제 후, 두 경로가 이 하나를 공유한다. 예전에는 글자까지 같은
   * 클로저가 두 벌이었고 한쪽 주석이 "두 경로가 다른 방식으로 폼을 맞추면 한쪽만 고쳐지는 사고가
   * 난다"고 적어 두고 있었다 — <b>주석으로 동기화하는 중복은 이미 어긋난 중복</b>이다.
   */
  // 그룹 배지·그룹 해제 버튼·그룹 설명문이 읽는 값. 훅에 `resolveState` 로 넘긴 것과 <b>같은</b>
  // 함수를 쓴다 — 표시용 그룹 상태와 저장/dirty 를 지배하는 그룹 상태가 갈라질 자리를 없앤다.
  const connectionGroupState = resolveConnectionGroupState(fieldState);

  /**
   * 번들이 재정의됐는데 이 키에는 테넌트 행이 없어 <b>빈 값으로 해석되는</b> 상태.
   * 키 단위 모델에는 대응하는 상태가 없다(예전이라면 `기본값 사용 중` 이었다).
   *
   * 폼 값이 아니라 <b>서버가 내려준 값</b>을 본다 — 사용자가 지금 타이핑한 내용은 아직 저장되지
   * 않았고, 이 노트가 말하는 것은 "지금 실제로 적용 중인 해석"이다.
   *
   * <b>멤버십을 스스로 확인한다.</b> 지금 호출부가 전부 연결 키만 넘기는 것은 사실이지만, 정합을
   * 호출 규율에만 두면 누가 `smtp.from_address` 에 이 노트를 다는 순간 거짓말이 된다 —
   * 그 필드는 의도적으로 번들 <b>밖</b>이고 키 단위로 상속되므로, 번들이 재정의된 상태에서
   * 비어 있다고 해서 "플랫폼 값이 사용되지 않습니다"가 참이 되지 않는다.
   */
  const isEmptyInBundle = (key: keyof SmtpForm) =>
    SMTP_CONNECTION_KEYS.includes(key) &&
    connectionGroupState === 'overridden' &&
    (settings[key]?.value ?? '') === '';

  // 저장 전 예고(§2): 아직 상속 중인데 연결 5키 중 하나라도 손댔다면, 저장이 5키 전부를 테넌트
  // 평면으로 옮긴다는 사실을 미리 말한다. 배지는 이 시점에도 `기본값 사용 중` 이다 — 저장 전에는
  // 서버에 행이 없고 실제로 아직 플랫폼 값으로 메일이 나가므로, 배지를 미리 뒤집으면 거짓이면서
  // 반증도 안 되는 화면이 된다.
  const bundleTransitionPending =
    connectionGroupState === 'inherited' &&
    SMTP_CONNECTION_KEYS.some((key) => form[key] !== original[key]);

  /**
   * 입력값 규칙 검증. 지금은 포트 범위 하나뿐이다.
   *
   * <b>비어 있으면 검사하지 않는다</b> — AI 탭의 숫자 규칙은 빈 값도 오류로 보지만, SMTP 는
   * V42 가 6키를 전부 `''` 로 시드해서 <b>미설정 플랫폼의 기본 상태가 빈 포트</b>다. 빈 값을
   * 오류로 만들면 `smtp.host` 하나만 고치려는 테넌트가 자기가 건드리지도 않은 빈 포트 때문에
   * 영원히 저장하지 못한다. 사용자가 <b>직접 비운</b> 경우는 오류가 아니라 `BLANK_ALLOWED_KEYS`
   * 판정으로 넘어가 저장이 거부된다.
   */
  const validate = (): boolean => {
    const newErrors: Partial<Record<keyof SmtpForm, string>> = {};
    const port = form['smtp.port'].trim();
    if (port !== '') {
      const n = Number(port);
      if (isNaN(n) || !Number.isInteger(n) || n < PORT_MIN || n > PORT_MAX) {
        newErrors['smtp.port'] = `${PORT_MIN}~${PORT_MAX} 사이의 정수를 입력하세요`;
      }
    }
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSave = async () => {
    if (!validate()) {
      toast.error('입력값을 확인하세요.');
      return;
    }

    // 페이로드 조립(잠긴 키 제외·미편집 키 제외·빈 값 거부 목록)은 공통 훅이 한다 — 이 규칙이
    // AI 탭과 갈라지면 한쪽만 고치는 사고가 난다.
    const { payload, droppedChangedKeys } = buildChangedPayload();
    if (droppedChangedKeys.length > 0) {
      const names = droppedChangedKeys.map((key) => FIELD_LABELS[key]).join(', ');
      // 탈출구 안내는 **지금 화면에 실제로 있는 것**을 가리켜야 한다. "재정의 해제"는 그 필드가
      // 재정의 상태일 때만 존재한다 — 연결 키는 그룹이 `overridden` 일 때만 그룹 머리에 버튼이
      // 뜨고, 상속 중이면 지울 오버라이드도 버튼도 없다. 그 상태에서 "재정의 해제를 쓰세요"는
      // 없는 컨트롤을 가리키는 셈이고, 실제 탈출구는 dirty 인 동안 항상 있는 "되돌리기"다.
      const anyInherited = droppedChangedKeys.some((key) => effectiveState(key) !== 'overridden');
      toast.error(
        anyInherited
          ? `${names}을(를) 비워 둔 채로는 저장할 수 없습니다. 입력을 취소하려면 "되돌리기"를 사용하세요.`
          : `${names}을(를) 비워 둔 채로는 저장할 수 없습니다. 플랫폼 기본값으로 되돌리려면 "재정의 해제"를 사용하세요.`,
      );
      return;
    }

    // 저장이 번들을 상속 → 재정의로 <b>전환</b>시켰는지 판정하려면 저장 **전** 상태가 필요하다.
    const wasInherited = connectionGroupState === 'inherited';

    setIsSaving(true);
    try {
      await settingsApi.update({ settings: payload });
      commitSaved();
      toast.success('설정이 저장되었습니다.');
      // 저장한 키는 이제 테넌트 재정의 상태이므로 배지를 다시 읽어 맞춘다.
      refreshMeta()
        .then((byKey) => {
          // 번들 전환이 일어났다면 **연결 5키의 값도** 다시 시드해야 한다. `refreshMeta` 는
          // `settings`(플래그)만 갱신하고 `form` 은 건드리지 않으므로, 그냥 두면 입력창은 옛
          // 플랫폼 값(포트 587·플랫폼 사용자 이름·플랫폼 마스크·스위치)을 계속 보여주는데 그
          // 아래 노트는 "이 항목은 비어 있습니다"라고 말한다.
          //
          // 전환하지 않은 저장에서는 재시드하지 않는다. 값이 어차피 같아 화면은 변하지 않으면서,
          // 저장 중에 사용자가 다른 필드에 입력한 내용을 덮어쓸 창만 넓어진다.
          if (!wasInherited) return;
          const nowOverridden = SMTP_CONNECTION_KEYS.some((key) => byKey[key]?.overridden === true);
          if (!nowOverridden) return;
          resyncFromServer(SMTP_CONNECTION_KEYS, byKey);
        })
        .catch(() => {
          // **저장은 성공했고 다시 그리기가 실패했다.** 이 둘을 뭉뚱그리면 안 된다:
          // 바깥 catch 로 넘겨 "저장 실패" 토스트를 띄우면 실제로 저장된 값을 사용자가 되돌리려
          // 들고, 그냥 삼키면 화면이 조용히 거짓말을 한다 — 배지는 `기본값 사용 중`, 그룹 문구는
          // "플랫폼 기본값을 쓰고 있습니다", 폼은 플랫폼 사용자 이름·마스크를 계속 보여주는데
          // 서버는 이미 그 테넌트를 **빈 자격증명 번들**로 옮긴 상태다. 경고 배너도 함께 사라진다
          // (setOriginal 이 이미 돌아 dirty 가 풀렸다). 그래서 저장 성공은 성공대로 두고,
          // 무엇이 실패했고 무엇을 해야 하는지를 따로 말한다.
          const message =
            '저장은 완료됐지만 화면을 다시 읽지 못했습니다. 지금 보이는 값은 서버 상태와 다를 수 있습니다 — 새로고침하세요.';
          setStaleNotice(message);
          toast.error(message);
        });
    } catch {
      toast.error('설정 저장에 실패했습니다.');
    } finally {
      setIsSaving(false);
    }
  };

  /**
   * 연결 5키 <b>전체</b>의 재정의를 해제한다 — 그룹 머리의 버튼 하나가 5번의 DELETE 를 발행한다.
   *
   * <b>번들 삭제 엔드포인트는 없다.</b> 그래서 부분 실패가 실재하고, 반드시 화면에 그려야 한다:
   * 그 중간 상태는 원자 해석 아래에서 <b>안전하지만</b> (행이 하나라도 남으면 5키가 전부 테넌트
   * 평면에서 해석되고 행 없는 키는 빈 값이다) 사용자가 보기엔 "해제했는데 아직 재정의 배지"다.
   * 안전하다는 사실과 아직 안 끝났다는 사실을 둘 다 말한다.
   *
   * 5키를 <b>조건 없이</b> 지운다. 서버가 번들 재정의 상태에서 5키 전부를 `overridden=true` 로
   * 내려주므로 화면은 어느 키에 실제 행이 있는지 알 수 없고, 알 필요도 없다 — `clearOverride` 는
   * 행이 없으면 아무 일도 하지 않는 멱등한 성공이다.
   */
  const handleClearConnectionBundle = async () => {
    setIsClearing(true);
    // 여기서 안내를 지우지 않는다 — 성공적인 재조회가 공통 훅 안에서 지우고(onMetaRefreshed),
    // 실패하면 아래에서 새 안내를 세운다. 시작 시점에 한 번 더 지우면 "지우는 자리"가 다시
    // 여러 곳이 된다.
    const failedLabels: string[] = [];
    for (const key of SMTP_CONNECTION_KEYS) {
      try {
        await settingsApi.clearOverride(key);
      } catch {
        failedLabels.push(FIELD_LABELS[key]);
      }
    }

    try {
      // 성공·실패 어느 쪽이든 서버에서 다시 읽는다 — 화면 상태가 실제 행 상태에서 파생되므로
      // 부분 실패도 자동으로 올바르게 그려진다.
      const byKey = await refreshMeta();
      resyncFromServer(SMTP_CONNECTION_KEYS, byKey);

      if (failedLabels.length > 0) {
        const message =
          '일부 항목만 해제되었습니다. 남은 항목은 아직 우리 조직 값으로 적용됩니다 — 다시 시도하세요.';
        setStaleNotice(message);
        toast.error(message);
      } else {
        toast.success('플랫폼 기본값으로 되돌렸습니다.');
      }
    } catch {
      // 재조회가 실패하면 화면이 지금 어느 상태인지 알 수 없다 — 성공이라고 말하지 않는다.
      const message = '재정의 해제 결과를 확인하지 못했습니다. 새로고침 후 다시 확인하세요.';
      setStaleNotice(message);
      toast.error(message);
    } finally {
      setIsClearing(false);
    }
  };

  /**
   * 번들이 재정의됐는데 <b>저장된</b> 값이 비어 있는 <b>자격증명</b> 키의 이름들. 비어 있는 키를
   * 실제로 나열해야 "무엇을 채우면 되는가"가 화면에 있다.
   *
   * <b>연결 5키 전부가 아니라 자격증명 2키만 보는 이유</b>: 이 안내가 하는 말은 "인증 없이 접속을
   * 시도한다"이고, 그 원인이 될 수 있는 것은 `사용자 이름`·`비밀번호` 뿐이다. 나머지 셋을 넣으면
   * 거짓이 된다 — 빈 `포트` 는 인증과 무관하고 소비자가 587 로 대체하며(그 사실은 포트 필드의
   * 노트가 따로 말한다), 빈 `호스트` 는 인증이 아니라 발송 자체가 실패하는 다른 문제이고,
   * `STARTTLS` 는 애초에 빈 값이 될 수 없다(번들 채움이 'true' 로 채운다, RULING F).
   */
  const emptyConnectionLabels = (['smtp.username', 'smtp.password'] as const)
    .filter((key) => isEmptyInBundle(key))
    .map((key) => FIELD_LABELS[key]);

  /**
   * 연결 테스트 옆 안내 <b>한 줄</b>. 세 조건이 <b>배타적</b>이고 우선순위가 있다 — 그래서 셋을
   * 마크업 세 벌이 아니라 <b>문자열 하나</b>로 고른다.
   *
   * 우선순위:
   * 1. <b>빈 호스트</b> — 접속을 <b>시도조차 하지 않는</b> 상태라 가장 앞이다(#390 item 4).
   *    여기서 자격증명 안내를 띄우면 실패 원인을 잘못 지목한다: 서버는 호스트가 비면
   *    `POST /settings/smtp/test` 에서 "SMTP 호스트가 설정되지 않았습니다" 로 즉시 돌아오고 인증은
   *    시도되지도 않는데, 사용자는 있지도 않은 인증 문제를 고치려 사용자 이름·비밀번호를 채운다.
   * 2. <b>빈 자격증명</b> — 접속은 하되 인증 없이 한다.
   * 3. <b>dirty</b> — 무엇으로 테스트하는지.
   *
   * 버튼은 어느 경우에도 막지 않는다: 무인증 릴레이는 합법적 최종 상태라 그 구성에서 테스트를
   * 못 하게 막으면 정당한 설정을 검증할 길이 사라진다.
   */
  const testNotice = isEmptyInBundle('smtp.host')
    ? 'SMTP 호스트가 비어 있어 접속을 시도하지 않습니다 — 호스트를 입력하고 저장한 뒤 다시 테스트하세요.'
    : emptyConnectionLabels.length > 0
      ? `${emptyConnectionLabels.join('·')}이(가) 비어 있어 인증 없이 접속을 시도합니다. 인증이 필요한 서버라면 실패가 정상입니다 — 값을 입력하고 저장한 뒤 다시 테스트하세요.`
      : hasChanges
        ? '저장 전 값이 아니라 마지막 저장값으로 테스트합니다'
        : null;

  return {
    base,
    isSaving,
    staleNotice,
    connectionGroupState,
    bundleTransitionPending,
    isEmptyInBundle,
    testNotice,
    handleSave,
    handleClearConnectionBundle,
  };
}
