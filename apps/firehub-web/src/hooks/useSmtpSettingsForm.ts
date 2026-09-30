import { useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';

import { settingsApi } from '../api/settings';
import { extractApiError } from '../lib/api-error';
import { indexSettingsByKey } from '../lib/settings-fields';
import { isIntegerSyntax } from '../lib/settings-number';
import { isSenderAddressSyntax } from '../lib/smtp-address';
import { eulReul } from '../lib/utils';
import type { ResolvedSettingResponse } from '../types/settings';

/**
 * 이메일(SMTP) 탭 6키의 <b>단일 원본 표</b>. 폼 타입·키 순서·초기값·라벨·필수 여부가 전부
 * 여기서 파생된다 — 목록을 여러 벌 두면 키 하나를 추가할 때 한 곳을 빠뜨린다.
 *
 * - `initial`: 미설정 워크스페이스의 빈 폼 값. 서버는 저장하지 않은 키를 아예 내려주지 않으므로(#712)
 *   응답에 없는 키는 이 값으로 채운다.
 *   - 포트 '587'·STARTTLS 'true': 발송 코드(`EmailChannel`/`EmailDeliveryChannel`)가 키가 없을 때
 *     쓰는 값과 같다. Switch 는 빈 문자열을 표현할 수 없고, 빈 포트는 서버가 400 으로 거부한다.
 * - `required`: 비워 둔 채 저장할 수 없는 키.
 *   - 호스트: 비어 있으면 "미설정"으로 판정된다. 저장했는데 미설정으로 보이는 상태를 만들지 않는다.
 *   - 포트: 서버가 빈 문자열을 거부한다.
 *   - 발신자 주소: 빈 값이 저장되면 발송 코드의 기본 발신자 폴백(키가 <b>없을 때만</b> 동작)을
 *     건너뛰고 빈 From 으로 보내려 한다.
 *   - 사용자 이름·비밀번호는 필수가 아니다 — 인증 없는 릴레이가 합법적 최종 상태다.
 */
export const SMTP_FIELDS = [
  { key: 'smtp.host', label: 'SMTP 호스트', required: true, initial: '' },
  { key: 'smtp.port', label: '포트', required: true, initial: '587' },
  { key: 'smtp.username', label: '사용자 이름', required: false, initial: '' },
  { key: 'smtp.password', label: '비밀번호', required: false, initial: '' },
  { key: 'smtp.starttls', label: 'STARTTLS 사용', required: false, initial: 'true' },
  { key: 'smtp.from_address', label: '발신자 주소', required: true, initial: '' },
] as const;

type SmtpKey = (typeof SMTP_FIELDS)[number]['key'];

/** 이메일(SMTP) 탭의 6키 폼. 값은 항상 문자열이다(입력창·Switch 가 문자열만 다룬다). */
export type SmtpForm = Record<SmtpKey, string>;

/** 키로 라벨·필수 여부를 찾는다 — 탭의 필드 블록과 검증 문구가 같은 표를 읽는다. */
export const SMTP_FIELD_META = Object.fromEntries(SMTP_FIELDS.map((f) => [f.key, f])) as Record<
  SmtpKey,
  (typeof SMTP_FIELDS)[number]
>;

const EMPTY = Object.fromEntries(SMTP_FIELDS.map((f) => [f.key, f.initial])) as SmtpForm;

// 포트 범위는 백엔드 `SettingsService.validateSmtpPort`(1~65535)와 반드시 같아야 한다 —
// 어긋나면 한쪽이 통과시킨 값을 다른 쪽이 거부해 "저장했는데 400" 또는 그 반대가 된다.
export const PORT_MIN = 1;
export const PORT_MAX = 65535;

/**
 * 저장할 때 앞뒤 공백을 다듬어 보내는 키(#728). 검증도 다듬은 값으로 한다 — 검증한 문자열과
 * 보내는 문자열이 다르면 서버가 다른 판정을 내린다(#727).
 *
 * 사용자 이름·비밀번호는 넣지 않는다: 공백만 입력한 값이 빈 문자열로 바뀌면 "빈 값"의 의미가
 * 달라지고(선택 칸의 빈 값 의미는 #661 소관), 비밀번호는 공백도 값의 일부다.
 */
const TRIMMED_KEYS: readonly SmtpKey[] = ['smtp.host', 'smtp.port', 'smtp.from_address'];

/** 보낼 값 — {@link TRIMMED_KEYS} 만 앞뒤 공백을 뗀다. */
function normalize(form: SmtpForm): SmtpForm {
  const next = { ...form };
  TRIMMED_KEYS.forEach((key) => {
    next[key] = form[key].trim();
  });
  return next;
}

/**
 * 서버 응답으로 폼 값을 만든다. 응답에 없는 키(또는 null)는 초기값으로 채운다.
 *
 * <b>비밀번호는 서버 값을 시드하지 않는다(항상 빈 칸).</b> 서버는 저장된 비밀번호를 고정 표식
 * (`****` — #725 이후 평문 끝 4자는 붙지 않는다)으로 내려주는데, 이것을 편집 가능한 입력에 넣으면
 * 사용자가 표식 뒤에 글자를 덧붙일 수 있다. 서버의 센티널 판정(`SettingsService.isMaskSentinel`)은
 * 정확히 `****` 인 값만 버리므로, 덧붙인 문자열(`****X`)은 <b>진짜 비밀번호로 저장</b>되고 저장 후
 * 같은 표식만 보여 사용자가 알아챌 방법이 없다. 그래서 "저장됨" 여부는 `passwordSaved` 로 따로 알린다.
 */
function seedFrom(byKey: Record<string, ResolvedSettingResponse>): SmtpForm {
  const values = { ...EMPTY };
  SMTP_FIELDS.forEach(({ key, initial }) => {
    values[key] = key === 'smtp.password' ? '' : (byKey[key]?.value ?? initial);
  });
  return values;
}

/** 이메일(SMTP) 탭이 그리는 데 필요한 전부. */
export interface SmtpSettingsFormState {
  isLoading: boolean;
  /** 최초 조회가 실패했는가 — 탭은 편집 가능한 빈 폼 대신 재시도 화면을 그린다. */
  loadFailed: boolean;
  /**
   * 이 워크스페이스에 SMTP 가 <b>저장돼 있는가</b>. 서버 응답의 `smtp.host` 로만 판정한다 —
   * 폼 값으로 판정하면 호스트를 입력하는 순간(저장 전) "설정됨"으로 뒤집혀 해제 버튼이 뜬다.
   */
  configured: boolean;
  /** 서버에 비밀번호가 저장돼 있는가(마스크가 비어 있지 않은가). 입력칸은 항상 빈 칸으로 시작한다. */
  passwordSaved: boolean;
  form: SmtpForm;
  errors: Partial<Record<SmtpKey, string>>;
  hasChanges: boolean;
  isSaving: boolean;
  isClearing: boolean;
  /** 저장은 됐지만 화면을 다시 읽지 못했을 때의 지속 안내. */
  staleNotice: string | null;
  /** 연결 테스트 옆 안내 한 줄(없으면 null). */
  testNotice: string | null;
  updateField: (key: SmtpKey, value: string) => void;
  handleReset: () => void;
  handleSave: () => Promise<void>;
  handleClear: () => Promise<void>;
  retryInitialLoad: () => Promise<void>;
}

/**
 * 이메일(SMTP) 탭의 폼 상태 기계 — 워크스페이스 전용 6키(#712).
 *
 * <b>저장은 6키를 한 벌로(비밀번호만 예외), 해제도 6키를 한 벌로 한다.</b> SMTP 에는 이제 이
 * 워크스페이스 값 하나뿐이라 "바꾼 키만 보낸다"로 지킬 다른 값이 없다. 그래서
 * `useSettingsOverrideForm`(키 단위 diff)을 쓰지 않고 `useAiClassifyForm` 처럼 자기 상태 기계를 갖는다.
 *
 * <b>비밀번호 규칙</b>: 입력칸은 항상 빈 칸으로 시작한다(`seedFrom` 주석). 저장된 비밀번호가 있을 때
 * 칸을 비워 두면 페이로드에서 <b>키를 뺀다</b> — 서버 PUT 은 받은 키만 upsert 하므로 빠진 키는
 * 그대로 유지된다. 마스크 센티널을 보내는 방식보다 이쪽을 고른 이유: 센티널 판정은 형태(길이 4/8)에
 * 기대는 휴리스틱이지만 "키 없음 = 손대지 않음"은 계약 자체다.
 * 저장된 비밀번호를 <b>지워 인증 없는 릴레이로 바꾸는</b> 조작은 이 폼에 없다 — 빈 칸이 "유지"를
 * 뜻하므로 "삭제"와 구별할 수 없다. 그 경우는 "설정 해제" 뒤 비밀번호 없이 다시 저장한다.
 *
 * <b>인스턴스는 `SettingsPage` 가 소유한다.</b> Radix `TabsContent` 는 비활성 탭을 언마운트하므로,
 * 탭 컴포넌트가 상태를 소유하면 탭을 바꾸는 순간 미저장 편집이 경고 없이 사라진다(#390-2b).
 */
export function useSmtpSettingsForm(): SmtpSettingsFormState {
  const [isLoading, setIsLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [settings, setSettings] = useState<Record<string, ResolvedSettingResponse>>({});
  const [form, setForm] = useState<SmtpForm>(EMPTY);
  const [original, setOriginal] = useState<SmtpForm>(EMPTY);
  const [errors, setErrors] = useState<Partial<Record<SmtpKey, string>>>({});
  const [isSaving, setIsSaving] = useState(false);
  const [isClearing, setIsClearing] = useState(false);
  /**
   * "지금 화면이 서버 상태와 다를 수 있다" 지속 안내. 토스트로 끝내지 않는 이유: 사용자가 다시
   * 조작해야 하는 상태인데 토스트는 사라지고 스크린리더 사용자가 놓칠 수 있다.
   */
  const [staleNotice, setStaleNotice] = useState<string | null>(null);

  /**
   * 서버 스냅숏 하나를 화면에 확정한다 — 설정 플래그·폼·원본을 함께 맞추고 낡음 안내를 지운다.
   * 안내를 지우는 자리를 "화면이 실제로 새로워진 지점" 한 곳으로 묶는다.
   *
   * @param submitted 저장 직후 재조회일 때 보낸 폼. 주어지면 <b>보낸 값 그대로인 칸만</b> 서버 값으로
   *   맞추고, 저장 요청이 도는 사이 사용자가 다시 고친 칸은 그대로 둔다(그 편집을 덮으면 조용히 사라진다).
   */
  const applySnapshot = useCallback((data: ResolvedSettingResponse[], submitted?: SmtpForm) => {
    const byKey = indexSettingsByKey(data);
    const values = seedFrom(byKey);
    setSettings(byKey);
    setOriginal(values);
    setForm((prev) => {
      if (!submitted) return values;
      const next = { ...prev };
      SMTP_FIELDS.forEach(({ key }) => {
        if (prev[key] === submitted[key]) next[key] = values[key];
      });
      return next;
    });
    if (!submitted) setErrors({});
    setStaleNotice(null);
  }, []);

  const load = useCallback(async () => {
    setIsLoading(true);
    try {
      const { data } = await settingsApi.getByPrefix('smtp');
      applySnapshot(data);
      setLoadFailed(false);
    } catch {
      setLoadFailed(true);
      toast.error('설정을 불러오는데 실패했습니다.');
    } finally {
      setIsLoading(false);
    }
  }, [applySnapshot]);

  /**
   * <b>최초 1회만 조회한다.</b> 개발 모드 StrictMode 의 effect 이중 실행이 두 번째 조회로
   * 폼을 다시 시드하면 그 사이 입력한 값이 사라진다 — `useSettingsOverrideForm` 과 같은 가드다.
   */
  const didInitialLoad = useRef(false);
  useEffect(() => {
    if (didInitialLoad.current) return;
    didInitialLoad.current = true;
    void load();
  }, [load]);

  const configured = (settings['smtp.host']?.value ?? '').trim() !== '';
  const passwordSaved = (settings['smtp.password']?.value ?? '') !== '';
  const hasChanges = SMTP_FIELDS.some(({ key }) => form[key] !== original[key]);

  const updateField = (key: SmtpKey, value: string) => {
    setForm((prev) => ({ ...prev, [key]: value }));
    if (errors[key]) {
      setErrors((prev) => {
        const next = { ...prev };
        delete next[key];
        return next;
      });
    }
  };

  const handleReset = () => {
    setForm({ ...original });
    setErrors({});
  };

  /**
   * 필수 키 공백, 포트 범위, 호스트·발신자 주소 형식을 검사한다. 오류는 필드 아래에 그린다.
   * 서버(`SettingsService` 의 `validateSmtpPort`·`validateSmtpHost`·`validateSmtpFromAddress`)와
   * 같은 규칙이어야 한다.
   */
  const validate = (): boolean => {
    const next: Partial<Record<SmtpKey, string>> = {};
    SMTP_FIELDS.forEach(({ key, label, required }) => {
      // 조사는 라벨의 받침에 맞춘다 — "포트을(를)" 같은 병기 표기를 쓰지 않는다.
      if (required && form[key].trim() === '') next[key] = `${label}${eulReul(label)} 입력하세요`;
    });
    // 호스트 중간의 공백은 다듬어도 남는다 — 그대로 저장되면 발송 시점의 접속 실패로만 드러난다(#728).
    const host = form['smtp.host'].trim();
    if (host !== '' && /\s/.test(host)) {
      next['smtp.host'] = 'SMTP 호스트에는 공백을 넣을 수 없습니다';
    }
    // 발신자 주소 형식 — 연결 테스트는 접속만 확인하므로 여기서 막지 않으면 실제 메일이 나갈 때에야
    // 실패한다(#728).
    const from = form['smtp.from_address'].trim();
    if (from !== '' && !isSenderAddressSyntax(from)) {
      next['smtp.from_address'] = '올바른 이메일 주소를 입력하세요 (예: noreply@example.com 또는 표시명 <noreply@example.com>)';
    }
    const port = form['smtp.port'].trim();
    if (port !== '') {
      // 표기부터 본다 — `587.0`·`5e2` 는 JS 로는 정수지만 서버(Java)는 못 읽는다(#727).
      const n = Number(port);
      if (!isIntegerSyntax(port) || n < PORT_MIN || n > PORT_MAX) {
        next['smtp.port'] = `${PORT_MIN}~${PORT_MAX} 사이의 정수를 입력하세요`;
      }
    }
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const handleSave = async () => {
    if (!validate()) {
      toast.error('입력값을 확인하세요.');
      return;
    }
    // 호스트·포트·발신자 주소는 검증한 값(앞뒤 공백 제거)을 그대로 보낸다 — 검증한 문자열과 보내는
    // 문자열이 다르면 서버가 다른 판정을 내린다(#727·#728).
    const typed = form;
    const submitted = normalize(typed);
    const payload: Record<string, string> = { ...submitted };
    // 저장된 비밀번호가 있고 칸이 비었으면 키를 뺀다 → 서버가 기존 비밀번호를 유지한다(훅 주석).
    if (passwordSaved && submitted['smtp.password'] === '') delete payload['smtp.password'];

    setIsSaving(true);
    try {
      await settingsApi.update({ settings: payload });
      // 저장 성공 — 우선 보낸 값을 원본으로 확정해 dirty 를 푼다(재조회가 실패해도 참인 사실).
      setOriginal(submitted);
      // 화면의 칸도 보낸 값으로 맞춘다. 다듬기 전 문자열(`" smtp.x.com "`)이 칸에 남으면 원본(다듬은
      // 값)과 달라 저장 직후에도 dirty 로 남고(저장 버튼·이탈 가드), 아래 재조회가 "보낸 값 그대로인
      // 칸"을 찾지 못해 그 칸을 서버 값으로 맞추지 못한다. 요청이 도는 사이 다시 고친 칸은 건드리지 않는다.
      setForm((prev) => {
        const next = { ...prev };
        TRIMMED_KEYS.forEach((key) => {
          if (prev[key] === typed[key]) next[key] = submitted[key];
        });
        return next;
      });
      toast.success('설정이 저장되었습니다.');
    } catch (err) {
      // 서버가 알려준 거부 사유(어느 값이 왜 틀렸는지)를 그대로 보여준다(#727).
      toast.error(extractApiError(err, '설정 저장에 실패했습니다.'));
      setIsSaving(false);
      return;
    }

    try {
      // 서버 값으로 다시 읽는다 — `configured`·`passwordSaved` 가 갱신되고 비밀번호 칸이 비워진다.
      const { data } = await settingsApi.getByPrefix('smtp');
      applySnapshot(data, submitted);
    } catch {
      // 저장은 성공했고 다시 그리기가 실패했다 — 둘을 뭉뚱그리지 않는다. "저장 실패"로 알리면
      // 사용자가 실제로 저장된 값을 되돌리려 들고, 삼키면 화면이 조용히 낡은 상태로 남는다.
      const message =
        '저장은 완료됐지만 화면을 다시 읽지 못했습니다. 지금 보이는 값은 서버 상태와 다를 수 있습니다 — 새로고침하세요.';
      setStaleNotice(message);
      toast.error(message);
    } finally {
      setIsSaving(false);
    }
  };

  /**
   * SMTP 설정 해제 — `DELETE /settings/smtp` 한 번으로 6키(발신자 주소 포함)를 지운다.
   *
   * 204 를 받으면 서버에는 SMTP 행이 하나도 없다는 것이 확정이므로(엔드포인트가 6키 전부를 지운다)
   * 다시 조회하지 않고 빈 스냅숏으로 화면을 맞춘다. 원본도 함께 비워야 미저장 편집 가드가 풀린다.
   * 요청이 끝나기 전에는 화면을 바꾸지 않는다 — 실패하면 설정된 상태가 그대로 맞다.
   */
  const handleClear = async () => {
    setIsClearing(true);
    try {
      await settingsApi.clearSmtp();
      applySnapshot([]);
      toast.success('SMTP 설정을 해제했습니다.');
    } catch {
      toast.error('SMTP 설정 해제에 실패했습니다.');
    } finally {
      setIsClearing(false);
    }
  };

  /**
   * 연결 테스트 옆 안내. 테스트는 <b>서버에 저장된</b> 설정으로 접속하므로:
   * 1. 미설정이면 테스트할 대상이 없다(버튼도 탭에서 막는다).
   * 2. 편집 중이면 무엇으로 테스트하는지 알려 결과를 잘못 읽지 않게 한다.
   */
  const testNotice = !configured
    ? '저장된 SMTP 설정이 없어 연결 테스트를 할 수 없습니다 — 먼저 저장하세요.'
    : hasChanges
      ? '저장 전 값이 아니라 마지막 저장값으로 테스트합니다'
      : null;

  return {
    isLoading,
    loadFailed,
    configured,
    passwordSaved,
    form,
    errors,
    hasChanges,
    isSaving,
    isClearing,
    staleNotice,
    testNotice,
    updateField,
    handleReset,
    handleSave,
    handleClear,
    retryInitialLoad: load,
  };
}
