import { Mail, RotateCcw, Save, Send } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';

import { settingsApi } from '../../api/settings';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { InlineBanner } from '../../components/ui/inline-banner';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { Separator } from '../../components/ui/separator';
import { Switch } from '../../components/ui/switch';
import { useTestSmtpSettings } from '../../hooks/queries/useProactiveMessages';
import { indexSettingsByKey, resolveSettingFieldState } from '../../lib/settings-fields';
import type { ResolvedSettingResponse } from '../../types/settings';
import { ClearOverrideButton, SettingFieldLabel, SettingStateBadge } from './settings-lock';

interface SmtpForm {
  'smtp.host': string;
  'smtp.port': string;
  'smtp.username': string;
  'smtp.password': string;
  'smtp.starttls': string;
  'smtp.from_address': string;
}

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
const BLANK_ALLOWED_KEYS: (keyof SmtpForm)[] = ['smtp.username', 'smtp.password'];

/**
 * SMTP <b>연결 번들</b> 5키 — 백엔드 `SettingsService.SMTP_CONNECTION_KEYS` 와 같은 집합이다.
 *
 * `{호스트, 포트, 사용자 이름, 비밀번호, STARTTLS}` 는 <b>한 서버에 대한 한 벌의 접속 정보</b>라
 * 서버가 이 5키를 <b>원자적으로</b> 해석한다: 하나라도 재정의되면 5키 전부가 테넌트 평면에서
 * 해석되고, 행이 없는 키는 플랫폼 값이 아니라 빈 값이 된다. 키 단위로 상속하면 A 서버의 주소와
 * B 서버의 자격증명이 섞여 <b>전 테넌트 공용 SMTP 계정</b>이 테넌트가 지정한 호스트로 나간다.
 *
 * <b>화면이 이 목록을 갖는 이유는 해석이 아니라 배치다.</b> 해석의 권위는 전적으로 서버 플래그이고
 * (아래 `connectionGroupState` 는 서버가 내려준 `overridden` 만 읽는다), 이 상수가 정하는 것은
 * "어느 필드가 그룹 테두리 안에 들어가는가" 뿐이다. `smtp.from_address` 는 접속과 무관한 표시
 * 값이라 번들이 아니며, 그룹 밖에서 개별 배지·개별 해제 버튼을 유지한다.
 */
const SMTP_CONNECTION_KEYS: (keyof SmtpForm)[] = [
  'smtp.host',
  'smtp.port',
  'smtp.username',
  'smtp.password',
  'smtp.starttls',
];

// 포트 범위는 백엔드 `SettingsService.validateSmtpPort`(1~65535)와 반드시 같아야 한다 —
// 어긋나면 한쪽이 통과시킨 값을 다른 쪽이 거부해 "저장했는데 400" 또는 그 반대가 된다.
const PORT_MIN = 1;
const PORT_MAX = 65535;

/**
 * 번들 안에서 <b>비어 있는 항목</b>임을 알리는 정적 노트(디자인 스펙 §1-1 신설 어휘).
 *
 * 배지가 아니라 노트인 이유: 배지 문자열은 어휘를 영구히 넓혀 AI 탭까지 따라와야 하는 부담을
 * 만들지만, 노트는 이 화면 안에 머문다. 빈 입력창은 시각적으로 "아직 안 채운 칸"과 구별되지
 * 않으므로 이 텍스트가 유일한 전달 경로다 — 그래서 각 입력의 `aria-describedby` 에 포함한다.
 */
function EmptyInBundleNote({
  id,
  show,
  extra,
}: {
  id: string;
  show: boolean;
  /**
   * 비어 있을 때 <b>실제로 적용되는 값</b>이 따로 있는 키만 채운다. 포트가 그렇다 — 소비자 3곳이
   * 전부 빈 포트를 587 로 대체하므로, 이 문장이 없으면 "포트가 없어서 못 나간다"로 읽힌다.
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
 * 이메일(SMTP) 설정 탭 — P7-c1 이후 <b>테넌트 상속/재정의 편집 화면</b>.
 *
 * SMTP 6키는 P7-b 에서 플랫폼 전용이었으나 P7-c1 이 테넌트 오버라이드 허용으로 재분류했다.
 * 그래서 이 탭은 AI 탭(`SettingsPage`)과 같은 체계를 그대로 쓴다 — 필드별 상태 배지, 재정의 해제,
 * "바꾼 키만 PUT" 저장.
 *
 * <b>읽기는 `GET /settings?prefix=smtp`(해석된 값 + 플래그) 다.</b> 예전에는 전용
 * `GET /settings/smtp` 를 썼는데 그 경로는 <b>해석기를 타지 않아</b> 플랫폼 값을 보여줬다 —
 * 테넌트가 SMTP 를 재정의하면 메일은 테넌트 값으로 나가는데 화면은 플랫폼 값을 보여주는 어긋남이
 * 생긴다. 예전 주석은 "프리픽스 조회로 갈아타면 권한이 달라 탭을 못 여는 사람이 생긴다"고
 * 경고했고, 그 경고 때문에 남아 있던 전용 읽기 엔드포인트를 이 태스크에서 삭제했다.
 *
 * <b>권한에 대한 예전 문장은 정정했다.</b> 여기에는 "`ai:settings`(V16)와 `settings:write`(V42)는
 * 둘 다 ADMIN 롤에만 부여되어 한쪽만 가진 사용자는 <b>존재할 수 없다</b>"고 적혀 있었다. 시드가
 * 그렇다는 것은 맞지만 <b>롤은 런타임에 편집 가능</b>하므로 "존재할 수 없다"는 거짓이다. 실제로
 * 그 갈라짐이 이 탭에서 관측 가능한 형태로 나타났었다 — 저장은 `ai:settings`, 바로 옆 "연결
 * 테스트"는 `settings:write` 였다. <b>지금은 이 탭의 네 라우트(조회·저장·해제·연결 테스트)가
 * 전부 `ai:settings` 하나를 쓴다</b>(`SettingsController` 참고). 도달 불가에 기대는 대신 갈라짐
 * 자체를 없앴다.
 *
 * <b>비밀번호는 서버가 마스킹(`****ab3f`)해서 주고 폼에 그대로 시드된다.</b> 사용자가 손대지 않으면
 * `form === original` 이라 페이로드에서 빠지므로 마스크가 저장될 일이 없다(백엔드의 센티널 필터는
 * 심층 방어이고, 정상 경로는 애초에 보내지 않는 것이다). 표시/숨기기 토글은 두지 않는다 — 서버가
 * 평문을 절대 내려주지 않아 눌러도 보여줄 것이 없다.
 */
export default function SmtpSettingsTab({
  onDirtyChange,
}: {
  /** 페이지 전체 이탈 가드(이슈 #86)에 이 탭의 dirty 여부를 보고한다. */
  onDirtyChange: (dirty: boolean) => void;
}) {
  const testMutation = useTestSmtpSettings();

  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [isClearing, setIsClearing] = useState(false);
  const [form, setForm] = useState<SmtpForm>(EMPTY);
  const [original, setOriginal] = useState<SmtpForm>(EMPTY);
  // 서버 응답을 키로 인덱싱해 보관한다 — 배지 상태(overridden/tenantEditable)의 근거.
  const [settings, setSettings] = useState<Record<string, ResolvedSettingResponse>>({});
  const [errors, setErrors] = useState<Partial<Record<keyof SmtpForm, string>>>({});
  /**
   * <b>"지금 화면이 서버 상태와 다를 수 있다"</b>를 알리는 지속 안내. 토스트로 끝내지 않는 이유:
   * 둘 다 사용자가 다시 조작해야 하는 상태인데 토스트는 사라지고 스크린리더 사용자가 놓칠 수
   * 있다(§6). 두 흐름이 한 슬롯을 공유한다 — 번들 해제 **부분 실패**와 저장 후 **재조회 실패**.
   */
  const [staleNotice, setStaleNotice] = useState<string | null>(null);

  /**
   * 최초 조회 실패. <b>폼을 그리지 않기 위해</b> 별도 상태로 둔다(#code-review Major 2).
   *
   * 실패를 토스트로만 알리면 `settings={}` · `form=EMPTY` 인 채로 화면이 그려지는데, 그러면 모든
   * 키가 `no-default` 로 판정돼 연결 그룹이 `inherited` 가 되고 5필드가 <b>편집 가능한 빈 칸</b>으로
   * 보인다 — "아직 아무것도 설정되지 않았다"와 시각적으로 구별되지 않는다. 거기서 호스트를 입력해
   * 저장하면 호스트만 든 번들 오버라이드가 만들어지고, 서버는 그 테넌트의 사용자 이름·비밀번호·
   * 포트를 <b>전부 빈 값으로</b> 해석하게 된다. 조회 실패가 파괴적 저장을 부르는 경로다.
   */
  const [loadError, setLoadError] = useState(false);

  const fetchSettings = useCallback(async () => {
    setIsLoading(true);
    try {
      const { data } = await settingsApi.getByPrefix('smtp');
      const byKey = indexSettingsByKey(data);
      const values = { ...EMPTY };
      (Object.keys(values) as (keyof SmtpForm)[]).forEach((key) => {
        values[key] = byKey[key]?.value ?? EMPTY[key];
      });
      setSettings(byKey);
      setForm(values);
      setOriginal(values);
      setLoadError(false);
      setStaleNotice(null);
    } catch {
      // 폼을 그리지 않는다 — 이유는 loadError 선언부 참고(빈 편집 가능 폼이 파괴적 저장을 부른다).
      setLoadError(true);
      toast.error('설정을 불러오는데 실패했습니다.');
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchSettings();
  }, [fetchSettings]);

  // 배지 상태만 다시 읽는다 — 폼 값은 건드리지 않으므로 입력 중인 내용이 사라지지 않는다.
  // 실패를 여기서 삼키지 않는 이유: 재정의 해제는 이 조회로 결과를 확정하므로, 삼키면 해제가
  // "성공처럼 보이는 무동작"이 된다. 삼킴 여부는 호출부가 정한다.
  const refreshMeta = useCallback(async () => {
    const { data } = await settingsApi.getByPrefix('smtp');
    const byKey = indexSettingsByKey(data);
    setSettings(byKey);
    // **화면이 실제로 새로워진 그 지점에서** 낡음 안내를 지운다. 호출부마다 지우면 하나를
    // 빠뜨리는데, 실제로 빠뜨렸다 — `handleClearOverride` 와 `fetchSettings` 가 안 지워서
    // "새로고침하세요" 가 성공적인 단일 키 해제 + 재조회 뒤에도 살아남았다. 조건이 사라진 뒤에도
    // 남는 안내는 이 커밋들이 없애려던 "화면이 조용히 거짓말한다"의 또 다른 판본이다.
    setStaleNotice(null);
    return byKey;
  }, []);

  /**
   * 연결 5키의 폼 값을 <b>서버 해석</b>으로 다시 시드하는 updater 를 만든다.
   *
   * 저장 후(번들 전환)와 번들 해제 후, 두 경로가 이 하나를 공유한다. 예전에는 글자까지 같은
   * 클로저가 두 벌이었고 한쪽 주석이 "두 경로가 다른 방식으로 폼을 맞추면 한쪽만 고쳐지는 사고가
   * 난다"고 적어 두고 있었다 — <b>주석으로 동기화하는 중복은 이미 어긋난 중복</b>이다.
   */
  const reseedConnection = (byKey: Record<string, ResolvedSettingResponse>) => (prev: SmtpForm) => {
    const next = { ...prev };
    SMTP_CONNECTION_KEYS.forEach((key) => {
      next[key] = byKey[key]?.value ?? EMPTY[key];
    });
    return next;
  };

  // 필드 상태 판정 — 서버 응답 1건에서 나온다.
  const fieldState = (key: keyof SmtpForm) => resolveSettingFieldState(key, settings[key]);

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
   */
  const connectionGroupState: 'locked' | 'overridden' | 'inherited' = SMTP_CONNECTION_KEYS.some(
    (key) => fieldState(key) === 'locked',
  )
    ? 'locked'
    : SMTP_CONNECTION_KEYS.some((key) => fieldState(key) === 'overridden')
      ? 'overridden'
      : 'inherited';

  /**
   * 배지·disabled·저장 대상·dirty 가 <b>모두</b> 이 한 곳을 거친다 — 연결 5키는 그룹 상태,
   * 나머지는 개별 상태다. 두 갈래를 호출부마다 다시 조합하면 "화면은 잠갔는데 저장은 보낸다" 같은
   * 어긋남이 생긴다.
   */
  const effectiveState = (key: keyof SmtpForm) =>
    SMTP_CONNECTION_KEYS.includes(key) ? connectionGroupState : fieldState(key);
  const isEditable = (key: keyof SmtpForm) => effectiveState(key) !== 'locked';

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
   * 비어 있다고 해서 "플랫폼 값이 사용되지 않습니다"가 참이 되지 않는다. 이 탭이 배지에서
   * 없앤 종류의 거짓말이 노트로 되돌아온다.
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

  const updateField = (key: keyof SmtpForm, value: string) => {
    setForm((prev) => ({ ...prev, [key]: value }));
    if (errors[key]) {
      setErrors((prev) => {
        const next = { ...prev };
        delete next[key];
        return next;
      });
    }
  };

  /**
   * 입력값 규칙 검증. 지금은 포트 범위 하나뿐이다.
   *
   * <b>비어 있으면 검사하지 않는다</b> — AI 탭의 숫자 규칙은 빈 값도 오류로 보지만, SMTP 는
   * V42 가 6키를 전부 `''` 로 시드해서 <b>미설정 플랫폼의 기본 상태가 빈 포트</b>다. 빈 값을
   * 오류로 만들면 `smtp.host` 하나만 고치려는 테넌트가 자기가 건드리지도 않은 빈 포트 때문에
   * 영원히 저장하지 못한다(P7-b 가 `session_max_tokens` 하한에서 겪은 "운영자가 저장한 값 때문에
   * 테넌트가 아무것도 저장 못 하는" 모양 그대로다). 사용자가 <b>직접 비운</b> 경우는 오류가 아니라
   * `BLANK_ALLOWED_KEYS` 판정으로 넘어가 저장이 거부된다.
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

    // 페이로드는 <b>이번에 바꾼 키만</b> 담는다. 6키를 전부 보내면 사용자가 호스트 하나를 고쳐도
    // 나머지 5키가 같은 값으로 tenant_settings 에 기록되어 상속이 조용히 끊긴다 — 그 뒤로는
    // 플랫폼이 기본값을 바꿔도 이 테넌트에는 영원히 전파되지 않는다.
    // 저장 대상 판정의 권위는 서버 플래그(fieldState)다 — web 상수로 거르면 표시와 저장이 갈라진다.
    const settingsToSave: Record<string, string> = {};
    const droppedChangedKeys: (keyof SmtpForm)[] = [];
    (Object.keys(form) as (keyof SmtpForm)[]).forEach((key) => {
      if (effectiveState(key) === 'locked') return;
      if (form[key] === original[key]) return;
      if (form[key].trim() !== '' || BLANK_ALLOWED_KEYS.includes(key)) {
        settingsToSave[key] = form[key];
      } else {
        droppedChangedKeys.push(key);
      }
    });
    if (droppedChangedKeys.length > 0) {
      const names = droppedChangedKeys.map((key) => FIELD_LABELS[key]).join(', ');
      // 탈출구 안내는 **지금 화면에 실제로 있는 것**을 가리켜야 한다. "재정의 해제"는 그 필드가
      // 재정의 상태일 때만 존재한다 — 연결 키는 그룹이 `overridden` 일 때만 그룹 머리에 버튼이
      // 뜨고, 상속 중이면 지울 오버라이드도 버튼도 없다(상속된 포트 587 이나 호스트를 비운 경우가
      // 그렇다). 그 상태에서 "재정의 해제를 쓰세요"는 없는 컨트롤을 가리키는 셈이고, 실제 탈출구는
      // dirty 인 동안 항상 있는 "되돌리기"다.
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
      await settingsApi.update({ settings: settingsToSave });
      setOriginal({ ...form });
      toast.success('설정이 저장되었습니다.');
      // 저장한 키는 이제 테넌트 재정의 상태이므로 배지를 다시 읽어 맞춘다.
      refreshMeta()
        .then((byKey) => {
          // 번들 전환이 일어났다면 **연결 5키의 값도** 다시 시드해야 한다. `refreshMeta` 는
          // `settings`(플래그)만 갱신하고 `form` 은 건드리지 않으므로, 그냥 두면 입력창은 옛
          // 플랫폼 값(포트 587·플랫폼 사용자 이름·플랫폼 마스크·스위치)을 계속 보여주는데 그
          // 아래 노트는 "이 항목은 비어 있습니다"라고 말한다 — 이 태스크가 배지에서 제거한
          // 거짓말이 **필드 값 자체로** 옮겨온 것이다(디자인 스펙 §1 위반).
          //
          // 전환하지 않은 저장에서는 재시드하지 않는다. 값이 어차피 같아 화면은 변하지 않으면서,
          // 저장 중에 사용자가 다른 필드에 입력한 내용을 덮어쓸 창만 넓어진다.
          if (!wasInherited) return;
          const nowOverridden = SMTP_CONNECTION_KEYS.some((key) => byKey[key]?.overridden === true);
          if (!nowOverridden) return;
          const seed = reseedConnection(byKey);
          setForm(seed);
          setOriginal(seed);
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
   * 재정의 해제 — DELETE 후 해당 키 하나만 서버 값으로 되돌린다.
   * 전체 폼을 다시 시드하지 않는 이유: 다른 필드에 입력 중이던 미저장 변경을 조용히 날려버린다.
   */
  const handleClearOverride = async (key: string) => {
    const formKey = key as keyof SmtpForm;
    setIsClearing(true);
    try {
      await settingsApi.clearOverride(key);
      const byKey = await refreshMeta();
      const restored = byKey[key]?.value ?? EMPTY[formKey];
      setForm((prev) => ({ ...prev, [formKey]: restored }));
      setOriginal((prev) => ({ ...prev, [formKey]: restored }));
      setErrors((prev) => {
        const next = { ...prev };
        delete next[formKey];
        return next;
      });
      toast.success('플랫폼 기본값으로 되돌렸습니다.');
    } catch {
      toast.error('재정의 해제에 실패했습니다.');
    } finally {
      setIsClearing(false);
    }
  };

  /**
   * 연결 5키 <b>전체</b>의 재정의를 해제한다 — 그룹 머리의 버튼 하나가 5번의 DELETE 를 발행한다.
   *
   * <b>번들 삭제 엔드포인트는 없다</b>(백엔드 변경은 이 태스크 범위 밖). 그래서 부분 실패가
   * 실재하고, 반드시 화면에 그려야 한다: 그 중간 상태는 원자 해석 아래에서 <b>안전하지만</b>
   * (행이 하나라도 남으면 5키가 전부 테넌트 평면에서 해석되고 행 없는 키는 빈 값이다) 사용자가
   * 보기엔 "해제했는데 아직 재정의 배지"다. 안전하다는 사실과 아직 안 끝났다는 사실을 둘 다 말한다.
   *
   * 5키를 <b>조건 없이</b> 지운다. 서버가 번들 재정의 상태에서 5키 전부를 `overridden=true` 로
   * 내려주므로 화면은 어느 키에 실제 행이 있는지 알 수 없고, 알 필요도 없다 — `clearOverride` 는
   * 행이 없으면 아무 일도 하지 않는 멱등한 성공이다(백엔드 `SettingsService.clearOverride`).
   */
  const handleClearConnectionBundle = async () => {
    setIsClearing(true);
    // 여기서 안내를 지우지 않는다 — 성공적인 재조회가 `refreshMeta` 안에서 지우고, 실패하면
    // 아래에서 새 안내를 세운다. 시작 시점에 한 번 더 지우면 "지우는 자리"가 다시 여러 곳이 된다.
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
      const seed = reseedConnection(byKey);
      setForm(seed);
      setOriginal(seed);
      setErrors((prev) => {
        const next = { ...prev };
        SMTP_CONNECTION_KEYS.forEach((key) => delete next[key]);
        return next;
      });

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

  // 재정의 중인 필드에만 해제 버튼을 붙인다 — 상속 중인 필드에는 지울 오버라이드가 없다.
  // 연결 5키는 그룹 머리의 버튼 하나가 대신하므로 여기 오지 않는다(§3).
  const clearAction = (key: keyof SmtpForm) =>
    !SMTP_CONNECTION_KEYS.includes(key) && fieldState(key) === 'overridden' ? (
      <ClearOverrideButton onConfirm={() => handleClearOverride(key)} disabled={isClearing} />
    ) : undefined;

  const handleReset = () => {
    setForm({ ...original });
    setErrors({});
  };

  // dirty 판정도 저장 대상과 같은 기준을 쓴다 — 서버가 잠갔다고 한 키는 세지 않는다.
  const hasChanges = (Object.keys(form) as (keyof SmtpForm)[]).some(
    (key) => effectiveState(key) !== 'locked' && form[key] !== original[key],
  );

  // 이탈 가드(이슈 #86)에 dirty 를 보고한다. <b>언마운트 시 반드시 해제한다</b> — 탭을 바꾸면
  // Radix 가 이 컴포넌트를 언마운트해 편집 내용도 함께 사라지는데, 합산기에 남은 true 를 지우지
  // 않으면 존재하지 않는 변경 때문에 이탈 다이얼로그가 뜬다.
  useEffect(() => {
    onDirtyChange(hasChanges);
    return () => onDirtyChange(false);
  }, [onDirtyChange, hasChanges]);

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
   * 마크업 세 벌이 아니라 <b>문자열 하나</b>로 고른다. 예전에는 중첩 삼항이 같은 `<p>` 를 두 번
   * 쓰고 세 번째 arm 만 `max-w-md` 를 빠뜨리고 있었는데, 그 드리프트가 이 모양이 부르는 것이다.
   *
   * 우선순위:
   * 1. <b>빈 호스트</b> — 접속을 <b>시도조차 하지 않는</b> 상태라 가장 앞이다(#390 item 4).
   *    여기서 자격증명 안내를 띄우면 실패 원인을 잘못 지목한다: 서버는 호스트가 비면
   *    `POST /settings/smtp/test` 에서 "SMTP 호스트가 설정되지 않았습니다" 로 즉시 돌아오고 인증은
   *    시도되지도 않는데, 사용자는 있지도 않은 인증 문제를 고치려 사용자 이름·비밀번호를 채운다.
   *    도달 경로는 평범하다 — 테넌트가 포트만("우리는 465 를 쓴다") 또는 비밀번호만 재정의하면
   *    번들이 나머지 연결 키를 빈 값으로 채워 호스트가 빈다.
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

  const handleTest = () => {
    testMutation.mutate(undefined, {
      onSuccess: (res) => {
        // 서버가 200 OK라도 success=false면 실패 (비정상 응답 처리)
        const data = res.data as { success?: boolean; message?: string } | undefined;
        if (data?.success === false) {
          toast.error(data.message ?? '연결 테스트에 실패했습니다.');
        } else {
          toast.success('SMTP 연결에 성공했습니다.');
        }
      },
      onError: () => toast.error('연결 테스트에 실패했습니다.'),
    });
  };

  if (isLoading) {
    return <div className="py-8 text-center text-muted-foreground text-sm">불러오는 중...</div>;
  }

  // 조회 실패는 **종단 상태**다. 편집 가능한 빈 폼 대신 원인과 재시도만 보여준다.
  if (loadError) {
    return (
      <div className="space-y-4 py-8 text-center">
        <p className="text-sm text-muted-foreground">
          SMTP 설정을 불러오지 못했습니다. 지금 적용 중인 값을 확인할 수 없어 편집을 열지 않습니다.
        </p>
        <Button variant="outline" onClick={fetchSettings}>
          <RotateCcw className="h-4 w-4" />
          다시 시도
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <Card className="card-hover">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Mail className="h-4 w-4" />
            SMTP 서버 설정
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          {/* 탭 전체 맥락 한 줄 — 배너가 아니라 일반 텍스트다. 필드별 배지가 상태를 말하므로
              "탭 전체가 잠겼다"고 말하던 배너 두 개는 제거했다(공존하면 서로 다른 말을 한다). */}
          <p className="text-sm text-muted-foreground">
            SMTP 설정은 플랫폼 기본값을 따르며, 필요하면 우리 조직 값으로 재정의할 수 있습니다.
          </p>

          {/* "지금 화면을 믿지 말고 다시 읽어라" 안내. 토스트 한 번으로 끝내지 않는 이유는
              사용자가 다시 조작해야 하는 상태이고 토스트는 사라지기 때문이다(§6).

              **탭 범위에 둔다.** 예전에는 연결 그룹 `fieldset` 안에 있었는데, 그 자리는 원래
              입주자(번들 해제 부분 실패 — 연결 전용 사건)에게만 맞았다. 저장 후 재조회 실패는
              폼 전체 사건이라, `smtp.from_address` 하나만 저장하고 재조회가 실패하면 번들과
              아무 상관 없는 경고가 "지금은 플랫폼 기본값을 그대로 쓰고 있습니다" 문단 밑에
              붙어 사용자가 연결 설정이 잘못됐다고 읽는다. 상태는 탭 범위가 됐는데 표시만
              그룹 범위에 남아 있었다. */}
          {staleNotice && (
            <InlineBanner variant="warning">{staleNotice}</InlineBanner>
          )}

          {/* 연결 5키는 하나의 `fieldset` 으로 묶는다. 그룹 경계를 테두리로만 전달하면 스크린리더
              사용자가 "비밀번호 필드 하나"만 만났을 때 그것이 묶음의 일부라는 사실을 듣지 못한다 —
              `legend` 는 그룹 안 어느 필드에 도착하든 함께 읽힌다(§6). */}
          <fieldset
            className="space-y-6 rounded-md border p-4"
            aria-describedby={
              bundleTransitionPending
                ? 'smtp-connection-desc smtp-connection-warning'
                : 'smtp-connection-desc'
            }
          >
            <legend className="flex flex-wrap items-center gap-2 px-1 text-sm font-medium">
              연결 설정
              {/* 범위는 배지가 아니라 이 보조 문구가 짊어진다 — 배지 문자열을 새로 만들면 AI 탭까지
                  따라와야 하는 어휘 부담이 영구히 생긴다. */}
              <span className="font-normal text-muted-foreground">5개 항목이 함께 적용됩니다</span>
            </legend>

            <div className="space-y-2">
              <div className="flex flex-wrap items-center gap-2">
                {/* 그룹 배지 하나 + 필드 배지 0개. 다섯 필드가 전부 같은 배지를 달면 오히려
                    "각각 독립적으로 그런 상태다"로 읽혀 고치려던 오해를 배지가 다시 심는다. */}
                <SettingStateBadge state={connectionGroupState} />
                {connectionGroupState === 'overridden' && (
                  <ClearOverrideButton
                    onConfirm={handleClearConnectionBundle}
                    disabled={isClearing}
                    label="연결 설정 전체 재정의 해제"
                    dialogTitle="연결 설정 재정의 해제"
                    /* 5개 항목을 이름으로 나열한다 — "이 그룹"이라고 쓰면 사용자가 그룹 경계를
                       스크롤 밖에서 추정해야 한다. 비밀번호는 화면에 평문이 없어 다시 칠 수 없으므로
                       "복구할 수 없으며" 한 마디를 번들 문구에만 더한다. */
                    dialogDescription="SMTP 호스트, 포트, 사용자 이름, 비밀번호, STARTTLS 5개 항목의 테넌트 설정이 모두 삭제되고 플랫폼 기본값으로 전환됩니다. 입력한 비밀번호는 복구할 수 없으며, 필요하면 언제든 다시 재정의할 수 있습니다."
                  />
                )}
              </div>
              <p id="smtp-connection-desc" className="text-sm text-muted-foreground">
                {connectionGroupState === 'overridden'
                  ? '이 5개 항목은 우리 조직 값으로 적용되고 있습니다. 플랫폼 기본값은 이 중 어느 항목에도 더 이상 사용되지 않습니다.'
                  : '호스트·포트·사용자 이름·비밀번호·STARTTLS 는 한 서버에 대한 한 벌의 접속 정보이므로 항상 함께 적용됩니다. 지금은 플랫폼 기본값을 그대로 쓰고 있습니다.'}
              </p>
            </div>

            {/* 저장 예고(§2). 상태 전환은 저장 후에 그리고, 지금은 무슨 일이 일어날지만 말한다. */}
            {bundleTransitionPending && (
              <InlineBanner id="smtp-connection-warning" variant="warning">
                {/* "입력하지 않은 항목은 빈 값이 된다"고 뭉뚱그리면 STARTTLS 까지 꺼진다는 뜻이
                    되는데, 번들 채움은 그 키만 켜진 채로 둔다(RULING F). 그래서 빈 값이 되는 네
                    키를 이름으로 한정한다 — 문장을 늘리지 않으면서 거짓을 없애는 쪽이다. */}
                저장하면 연결 설정 5개 항목이 모두 우리 조직 값으로 전환됩니다. 입력하지 않은
                호스트·포트·사용자 이름·비밀번호는 빈 값이 되며, 플랫폼의 사용자 이름·비밀번호는 더
                이상 사용되지 않습니다. 인증이 필요한 서버라면 지금 사용자 이름과 비밀번호도 함께
                입력하세요.
              </InlineBanner>
            )}

            {/* Host */}
            <div className="space-y-2">
              <Label htmlFor="smtp-host">SMTP 호스트</Label>
              <Input
                id="smtp-host"
                className="max-w-md"
                value={form['smtp.host']}
                disabled={!isEditable('smtp.host')}
                onChange={(e) => updateField('smtp.host', e.target.value)}
                placeholder="smtp.gmail.com"
                aria-describedby={
                  isEmptyInBundle('smtp.host') ? 'smtp-host-desc smtp-host-empty' : 'smtp-host-desc'
                }
              />
              <p id="smtp-host-desc" className="text-sm text-muted-foreground">
                발신 메일 서버 주소
              </p>
              <EmptyInBundleNote id="smtp-host-empty" show={isEmptyInBundle('smtp.host')} />
            </div>

            {/* Port */}
            <div className="space-y-2">
              <Label htmlFor="smtp-port">포트</Label>
              <Input
                id="smtp-port"
                type="number"
                min={PORT_MIN}
                max={PORT_MAX}
                className="max-w-[120px]"
                value={form['smtp.port']}
                disabled={!isEditable('smtp.port')}
                onChange={(e) => updateField('smtp.port', e.target.value)}
                placeholder="587"
                aria-describedby={isEmptyInBundle('smtp.port') ? 'smtp-port-empty' : undefined}
              />
              {errors['smtp.port'] && (
                <p className="text-sm text-destructive">{errors['smtp.port']}</p>
              )}
              <EmptyInBundleNote
                id="smtp-port-empty"
                show={isEmptyInBundle('smtp.port')}
                extra="기본 포트 587 로 접속합니다."
              />
            </div>

            {/* Username */}
            <div className="space-y-2">
              <Label htmlFor="smtp-username">사용자 이름</Label>
              <Input
                id="smtp-username"
                className="max-w-md"
                value={form['smtp.username']}
                disabled={!isEditable('smtp.username')}
                onChange={(e) => updateField('smtp.username', e.target.value)}
                placeholder="user@example.com"
                aria-describedby={
                  isEmptyInBundle('smtp.username')
                    ? 'smtp-username-desc smtp-username-empty'
                    : 'smtp-username-desc'
                }
              />
              <p id="smtp-username-desc" className="text-sm text-muted-foreground">
                인증 없는 릴레이라면 비워 둘 수 있습니다
              </p>
              <EmptyInBundleNote id="smtp-username-empty" show={isEmptyInBundle('smtp.username')} />
            </div>

            {/* Password — 마스킹된 값이 그대로 시드된다. 표시/숨기기 토글을 두지 않는 이유는
                눌러도 보여줄 평문이 서버에서 오지 않기 때문이다. */}
            <div className="space-y-2">
              <Label htmlFor="smtp-password">비밀번호</Label>
              <Input
                id="smtp-password"
                type="password"
                className="max-w-md"
                value={form['smtp.password']}
                disabled={!isEditable('smtp.password')}
                onChange={(e) => updateField('smtp.password', e.target.value)}
                aria-describedby="smtp-password-desc"
              />
              {/* 번들 재정의 상태의 빈 비밀번호는 §1-1 노트가 기존 두 분기를 **대체**한다 —
                  "설정된 비밀번호가 없습니다 (인증 없는 SMTP)"는 사용자가 의도해서 비운 것처럼
                  읽혀, 실제로는 자격증명 없이 릴레이를 시도하게 된 위험을 감춘다. */}
              {isEmptyInBundle('smtp.password') ? (
                <EmptyInBundleNote id="smtp-password-desc" show />
              ) : (
                <p id="smtp-password-desc" className="text-sm text-muted-foreground">
                  {form['smtp.password'] === ''
                    ? '설정된 비밀번호가 없습니다 (인증 없는 SMTP)'
                    : '현재 비밀번호가 설정되어 있습니다. 값을 바꾸려면 새 비밀번호를 입력하세요.'}
                </p>
              )}
            </div>

            {/* STARTTLS — Switch 는 라벨 오른쪽에 놓이는 배치라 왼쪽 열에 라벨+설명을 직접 조합한다. */}
            <div className="flex items-center justify-between max-w-md">
              <div className="space-y-1">
                <Label htmlFor="smtp-starttls">STARTTLS 사용</Label>
                <p className="text-sm text-muted-foreground">TLS 암호화로 SMTP 연결 보안</p>
                {/* 이 필드에는 빈 항목 노트가 없다. 번들 채움이 `smtp.starttls` 만 'true' 로
                    채우므로(RULING F) **번들 채움으로는** 이 키가 빈 값으로 내려올 수 없다 —
                    노트를 달아 두면 도달 불가 방어 코드가 된다.
                    ("어떤 경로로도 불가능"이라고는 적지 않는다: PUT 으로 빈 문자열을 직접 보내면
                    행이 실재해 채움을 건너뛰고 그대로 해석된다. 다만 그것은 'false' 를 보내는 것과
                    같은 명시적 조작이고, Switch 가 꺼짐으로 그려져 화면이 거짓말을 하지 않는다.) */}
              </div>
              <Switch
                id="smtp-starttls"
                aria-label="STARTTLS 사용"
                checked={form['smtp.starttls'] === 'true'}
                disabled={!isEditable('smtp.starttls')}
                onCheckedChange={(checked) => updateField('smtp.starttls', checked ? 'true' : 'false')}
              />
            </div>
          </fieldset>

          <Separator />

          {/* From Address */}
          <div className="space-y-2">
            <SettingFieldLabel
              htmlFor="smtp-from"
              state={fieldState('smtp.from_address')}
              action={clearAction('smtp.from_address')}
            >
              발신자 주소
            </SettingFieldLabel>
            <Input
              id="smtp-from"
              className="max-w-md"
              value={form['smtp.from_address']}
              disabled={!isEditable('smtp.from_address')}
              onChange={(e) => updateField('smtp.from_address', e.target.value)}
              placeholder="noreply@example.com"
            />
            {/* 그룹 밖 + 개별 배지 + 개별 해제 버튼. 구분은 구조가 하고, 텍스트가 한 번 더 말한다(§4). */}
            <p className="text-sm text-muted-foreground">
              이메일 발신자로 표시되는 주소. 접속 정보와 무관하게 이 항목만 따로 재정의할 수 있습니다.
            </p>
          </div>
        </CardContent>
      </Card>

      {/* 액션 행 하나에 저장·되돌리기·연결 테스트를 모두 둔다 — 행을 둘로 나누면 화면에 액션
          영역이 두 개 생겨 어느 것이 주 동작인지 흐려진다. */}
      <div className="flex flex-wrap items-center gap-3">
        <Button onClick={handleSave} disabled={isSaving || !hasChanges}>
          <Save className="h-4 w-4" />
          {isSaving ? '저장 중...' : '저장'}
        </Button>
        <Button variant="outline" onClick={handleReset} disabled={!hasChanges}>
          <RotateCcw className="h-4 w-4" />
          되돌리기
        </Button>
        <div className="ml-auto flex items-center gap-3">
          {/* 연결 테스트는 <b>서버에 저장된</b> 설정으로 접속한다 — 편집 중인 폼 값이 아니다.
              버튼을 막지 않는 이유: 지금 실제로 적용 중인 값을 확인하려는 것도 유효한 용도라
              (AI 탭의 "인증 확인"이 dirty 에서 비활성인 것과 다르다) 막으면 그 진단을 없앤다.
              대신 dirty 인 동안 무엇으로 테스트하는지 문자열로 알려 거짓 결과 해석을 막는다. */}
          {/* 우선순위·배타성의 근거는 testNotice 선언부에 있다 — 여기서는 고른 문자열을 그릴 뿐이다. */}
          {testNotice && <p className="max-w-md text-sm text-muted-foreground">{testNotice}</p>}
          <Button variant="outline" onClick={handleTest} disabled={testMutation.isPending}>
            <Send className="h-4 w-4" />
            {testMutation.isPending ? '테스트 중...' : '연결 테스트'}
          </Button>
        </div>
      </div>
    </div>
  );
}
