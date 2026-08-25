import type { Dispatch, SetStateAction } from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';

import { settingsApi } from '../api/settings';
import type { SettingFieldState } from '../lib/settings-fields';
import { indexSettingsByKey, resolveSettingFieldState } from '../lib/settings-fields';
import type { ResolvedSettingResponse } from '../types/settings';

/** 설정 폼 한 벌 — 키는 설정 키 문자열, 값은 항상 문자열이다(입력창이 문자열만 다룬다). */
export type SettingsFormShape = Record<string, string>;

export interface UseSettingsOverrideFormOptions<F extends SettingsFormShape> {
  /** `GET /settings?prefix=` 에 넘길 프리픽스. 'ai' / 'smtp'. */
  prefix: string;
  /**
   * 서버 값이 없을 때 쓰는 키별 폴백. <b>모듈 레벨 상수여야 한다</b> — 인라인 객체를 넘기면
   * 렌더마다 새 참조가 되어 해제 폴백이 매번 다른 객체를 읽는다.
   *
   * AI 탭은 "빈 문자열 + 코드 기본값(BUILTIN_AI_DEFAULTS)"을 미리 합쳐 넘기고, SMTP 탭은
   * 조회 전 초기값(starttls 만 'true')을 넘긴다 — 두 폴백 규칙이 이 한 파라미터로 합쳐진다.
   */
  defaults: F;
  /**
   * 빈 값으로 저장해도 되는 키의 화이트리스트. 나머지 키를 비운 채 저장하면
   * `droppedChangedKeys` 에 담겨 호출자가 거부한다 — 값의 유효성 문제가 아니라 의도 불일치다.
   */
  blankAllowed?: ReadonlySet<keyof F>;
  /**
   * 필드 단위 상태를 <b>다른 기준으로 치환</b>하고 싶을 때 쓴다. SMTP 연결 5키가 그룹(번들)
   * 상태로 치환되는 경우가 유일한 소비자다. 두 번째 인자로 훅 자신의 필드 단위 판정 함수를
   * 넘겨 주므로, 호출자가 그룹 상태를 계산하려고 `settings` 를 다시 파헤칠 필요가 없다.
   */
  resolveState?: (
    key: keyof F,
    fieldState: (k: keyof F) => SettingFieldState,
  ) => SettingFieldState;
  /**
   * 서버 메타를 실제로 다시 읽은 직후(최초 조회 성공 포함) 호출된다. SMTP 탭이 "화면이 낡았다"
   * 안내를 <b>화면이 실제로 새로워진 그 지점에서</b> 지우는 데 쓴다 — 호출부마다 지우면
   * 하나를 빠뜨리고, 실제로 빠뜨린 전례가 있다.
   */
  onMetaRefreshed?: () => void;
}

export interface SettingsOverrideForm<F extends SettingsFormShape> {
  isLoading: boolean;
  /** 최초 조회가 실패했는가. 이것으로 무엇을 할지는 호출자가 정한다(RULING C). */
  loadFailed: boolean;
  isClearing: boolean;
  /**
   * 해제 진행 중 표시를 <b>훅 밖의 해제 작업</b>도 함께 쓰기 위한 setter. SMTP 의 "연결 5키
   * 전체 해제"가 유일한 소비자다 — 그 작업은 번들 개념이라 훅 밖에 있지만(RULING B), 화면에서
   * 잠그는 버튼은 개별 해제와 <b>같은 것들</b>이다. 별도 플래그를 두면 두 상태가 갈라져
   * "해제 중인데 해제 버튼이 눌린다"가 생긴다.
   */
  setIsClearing: Dispatch<SetStateAction<boolean>>;
  settings: Record<string, ResolvedSettingResponse>;
  form: F;
  original: F;
  /**
   * 서버에서 다시 읽은 값으로 지정한 키들을 <b>확정</b>한다 — `form` 과 `original` 을 <b>같은
   * 값으로 함께</b> 덮고 그 키의 검증 오류를 지운다.
   *
   * <b>왜 `setForm`/`setOriginal` 을 따로 내주지 않는가</b>: 이 둘이 갈라지는 것이 이 밴드가
   * 막으려던 결함 그 자체다. `original` 만 새 마스크로 갱신되면 실제 편집이 "안 바뀐 키"로
   * 조용히 누락되고, `form` 만 갱신되면 마스크가 값처럼 저장 대상에 오른다. 쌍으로만 움직이는
   * 연산이므로 <b>쌍을 깨뜨릴 수 없는 형태로</b>만 노출한다.
   *
   * 값은 조회와 <b>같은 폴백</b>을 거친다(서버 값 → `defaults`) — 코드 기본값이 있는 키를
   * 빈칸으로 만들면 실제 적용값과 화면이 어긋난다.
   */
  resyncFromServer: (keys: readonly (keyof F)[], byKey: Record<string, ResolvedSettingResponse>) => void;
  errors: Partial<Record<keyof F, string>>;
  setErrors: Dispatch<SetStateAction<Partial<Record<keyof F, string>>>>;
  fieldState: (key: keyof F) => SettingFieldState;
  effectiveState: (key: keyof F) => SettingFieldState;
  isEditable: (key: keyof F) => boolean;
  hasChanges: boolean;
  updateField: (key: keyof F, value: string) => void;
  handleReset: () => void;
  handleClearOverride: (key: keyof F) => Promise<void>;
  buildChangedPayload: () => {
    payload: Record<string, string>;
    droppedChangedKeys: (keyof F)[];
  };
  /** 저장 성공 후 `original` 을 현재 폼으로 확정한다. */
  commitSaved: () => void;
  refreshMeta: () => Promise<Record<string, ResolvedSettingResponse>>;
  /**
   * <b>`loadFailed` 종단 화면의 "다시 시도" 버튼 전용</b>이다. 이름이 `refetch` 가 아닌 이유:
   * 평범한 "새로고침" 버튼에 물리면 살아 있는 편집 위로 `original` 이 새 마스크로 재시드되어
   * 저장 대상 판정이 무너진다. 잘못 쓸 자리를 이름으로 좁힌다.
   */
  retryInitialLoad: () => Promise<void>;
}

/**
 * 설정 탭 두 개(AI·SMTP)가 공유하는 <b>필드 단위</b> 폼 상태 기계.
 *
 * 소유하는 것: 최초 조회·폼/원본 시드, 메타 갱신, `fieldState`/`isEditable`, 개별 재정의 해제,
 * "바꾼 키만" 페이로드 diff, `hasChanges`, 되돌리기.
 *
 * <b>소유하지 않는 것</b>(다음 사람이 여기로 밀어 넣으려 할 것이다 — 넣지 마라):
 * - <b>번들(연결 5키) 개념 일체</b> — `SMTP_CONNECTION_KEYS`, 그룹 상태, 그룹 해제 버튼,
 *   "번들 안에서 비어 있음" 노트, 저장 후 번들 전환 재시드. 훅은 `resolveState` 라는 <b>구멍</b>
 *   하나만 열어 두고, 그 구멍에 무엇을 끼울지는 호출자가 정한다.
 * - <b>낡음 안내(`staleNotice`)</b> — 훅은 갱신이 실제로 일어난 지점을 `onMetaRefreshed` 로
 *   알릴 뿐, 그 사실로 어떤 배너를 세우고 지울지는 호출자의 표현 문제다.
 * - <b>조회 실패 종단 화면</b> — `loadFailed` 를 보고만 한다(RULING C). AI 탭은 toast 만 띄우고
 *   폴백 값으로 렌더하고, SMTP 탭은 폼 자체를 그리지 않는다. 비밀번호 필드가 있는 쪽에서만
 *   "빈 폼"이 자격증명 덮어쓰기로 이어지므로 <b>이 비대칭은 의도된 것</b>이다.
 * - <b>연결 테스트 안내</b> — SMTP 전용 3단 우선순위 문자열.
 *
 * 이유는 하나다: 위 전부가 오늘도 내일도 소비자가 SMTP 하나뿐이라, 훅에 넣으면 AI 탭이 영원히
 * 쓰지 않는 분기를 훅이 들고 다니게 된다. 평면 통합이 아니라 <b>계층화</b>다 — SMTP 고유 개념은
 * 이 훅의 반환값 <b>위에 얹히는 레이어</b>로 SMTP 파일에 남는다.
 */
export function useSettingsOverrideForm<F extends SettingsFormShape>({
  prefix,
  defaults,
  blankAllowed,
  resolveState,
  onMetaRefreshed,
}: UseSettingsOverrideFormOptions<F>): SettingsOverrideForm<F> {
  const [isLoading, setIsLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [isClearing, setIsClearing] = useState(false);
  // 서버 응답을 키로 인덱싱해 보관한다 — 배지 상태(overridden/tenantEditable)와 description 폴백의 근거.
  const [settings, setSettings] = useState<Record<string, ResolvedSettingResponse>>({});
  const [form, setForm] = useState<F>(defaults);
  const [original, setOriginal] = useState<F>(defaults);
  const [errors, setErrors] = useState<Partial<Record<keyof F, string>>>({});

  // 콜백·상수를 effect 의존성에서 떼어 낸다 — 호출부의 참조가 바뀌어도 조회가 다시 돌지 않는다.
  const defaultsRef = useRef(defaults);
  defaultsRef.current = defaults;
  const metaRefreshedRef = useRef(onMetaRefreshed);
  metaRefreshedRef.current = onMetaRefreshed;

  const load = useCallback(async () => {
    setIsLoading(true);
    try {
      const { data } = await settingsApi.getByPrefix(prefix);
      const byKey = indexSettingsByKey(data);
      const base = defaultsRef.current;
      // 서버 값 → 폴백 순으로 채운다. null 폴백을 반드시 거치므로 "null"/"undefined" 문자열이
      // 입력창에 렌더되는 일은 없다.
      const values = { ...base } as Record<keyof F, string>;
      (Object.keys(values) as (keyof F)[]).forEach((key) => {
        values[key] = byKey[key as string]?.value ?? base[key];
      });
      setSettings(byKey);
      setForm(values as F);
      setOriginal(values as F);
      setLoadFailed(false);
      metaRefreshedRef.current?.();
    } catch {
      setLoadFailed(true);
      toast.error('설정을 불러오는데 실패했습니다.');
    } finally {
      setIsLoading(false);
    }
  }, [prefix]);

  /**
   * <b>최초 1회만 조회한다.</b> 재조회가 `original` 을 새 서버 값으로 다시 시드하면,
   * 사용자가 입력한 진짜 비밀번호가 있는 `form` 과 마스크(`****3f2a`)로 갱신된 `original` 이
   * 갈라져 저장 대상 판정(`form[key] === original[key]` → 제외)이 무너진다 — 실제 편집이
   * "안 바뀐 키"로 조용히 누락되거나, 반대 순서면 마스크가 값처럼 저장 대상에 오른다.
   *
   * 오늘 이 가드가 실제로 막는 것은 개발 모드 StrictMode 의 effect 이중 실행과 `load` 참조
   * 변동뿐이다(훅 인스턴스는 언마운트되지 않는 `SettingsPage` 가 소유한다). 그래도 둔다 —
   * 훅이 어디서 인스턴스화되든 이 불변식이 훅 자신의 성질이어야 한다.
   */
  const didInitialLoad = useRef(false);
  useEffect(() => {
    if (didInitialLoad.current) return;
    didInitialLoad.current = true;
    load();
  }, [load]);

  /**
   * 배지 상태(플래그)만 다시 읽고 인덱싱된 맵을 돌려준다 — 폼 값은 건드리지 않으므로 입력 중인
   * 내용이 사라지지 않는다. 실패를 여기서 삼키지 않는다: 재정의 해제는 이 조회로 결과를
   * 확정하므로 삼키면 해제가 "성공처럼 보이는 무동작"이 된다. 삼킴 여부는 호출부가 정한다.
   */
  const refreshMeta = useCallback(async () => {
    const { data } = await settingsApi.getByPrefix(prefix);
    const byKey = indexSettingsByKey(data);
    setSettings(byKey);
    metaRefreshedRef.current?.();
    return byKey;
  }, [prefix]);

  // 필드 상태 판정 — 배지·disabled·검증·저장 대상이 모두 이 한 곳을 거쳐 서로 어긋나지 않게 한다.
  const fieldState = (key: keyof F) =>
    resolveSettingFieldState(key as string, settings[key as string]);

  // 호출자가 그룹 판정을 얹으면 그것이 이긴다 — 배지·disabled·저장 대상·dirty 가 모두 이 하나를 지난다.
  const effectiveState = (key: keyof F) =>
    resolveState ? resolveState(key, fieldState) : fieldState(key);

  const isEditable = (key: keyof F) => effectiveState(key) !== 'locked';

  const updateField = (key: keyof F, value: string) => {
    setForm((prev) => ({ ...prev, [key]: value }) as F);
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
   * 재정의 해제 — DELETE 후 해당 키 하나만 서버 값으로 되돌린다.
   * 전체 폼을 다시 시드하지 않는 이유: 다른 필드에 입력 중이던 미저장 변경을 조용히 날려버린다.
   */
  /** 서버 값으로 지정 키들을 확정한다. `form`·`original` 을 같은 값으로 함께 덮는다. */
  const resyncFromServer = (
    keys: readonly (keyof F)[],
    byKey: Record<string, ResolvedSettingResponse>,
  ) => {
    const patch = {} as Partial<Record<keyof F, string>>;
    keys.forEach((key) => {
      patch[key] = byKey[key as string]?.value ?? defaultsRef.current[key];
    });
    setForm((prev) => ({ ...prev, ...patch }) as F);
    setOriginal((prev) => ({ ...prev, ...patch }) as F);
    setErrors((prev) => {
      const next = { ...prev };
      keys.forEach((key) => delete next[key]);
      return next;
    });
  };

  const handleClearOverride = async (key: keyof F) => {
    setIsClearing(true);
    try {
      await settingsApi.clearOverride(key as string);
      const byKey = await refreshMeta();
      resyncFromServer([key], byKey);
      toast.success('플랫폼 기본값으로 되돌렸습니다.');
    } catch {
      toast.error('재정의 해제에 실패했습니다.');
    } finally {
      setIsClearing(false);
    }
  };

  /**
   * 저장 페이로드는 <b>이번에 바꾼 키만</b> 담는다. 전 키를 보내면 사용자가 한 필드를 고쳐도
   * 나머지가 같은 값으로 tenant_settings 에 기록되어 <b>상속이 조용히 끊긴다</b> — 그 뒤로는
   * 플랫폼이 기본값을 바꿔도 이 테넌트에는 영원히 전파되지 않는다.
   *
   * 저장 대상 판정의 권위는 <b>서버 플래그</b>(`effectiveState`)다. web 상수로 거르면 표시는
   * 서버가, 저장은 web 사본이 구동해 둘이 갈라진다.
   *
   * 사용자가 방금 <b>비운</b> 키는 페이로드에서 빼는 대신 `droppedChangedKeys` 로 돌려준다.
   * 그냥 빼면 "저장했다"면서 아무것도 쓰지 않고 dirty 까지 지워, 사용자는 반영된 줄 알고
   * 떠나는데 옛 오버라이드가 그대로 적용된다.
   */
  const buildChangedPayload = () => {
    const payload: Record<string, string> = {};
    const droppedChangedKeys: (keyof F)[] = [];
    (Object.keys(form) as (keyof F)[]).forEach((key) => {
      if (effectiveState(key) === 'locked') return;
      if (form[key] === original[key]) return;
      if (form[key].trim() !== '' || blankAllowed?.has(key)) {
        payload[key as string] = form[key];
      } else {
        droppedChangedKeys.push(key);
      }
    });
    return { payload, droppedChangedKeys };
  };

  const commitSaved = () => setOriginal({ ...form });

  // dirty 판정도 저장 대상과 같은 기준을 쓴다 — 두 기준이 갈리면 "저장 버튼은 활성인데 보낼
  // 것이 없다"(또는 그 반대)가 생긴다.
  const hasChanges = (Object.keys(form) as (keyof F)[]).some(
    (key) => effectiveState(key) !== 'locked' && form[key] !== original[key],
  );

  return {
    isLoading,
    loadFailed,
    isClearing,
    setIsClearing,
    settings,
    form,
    original,
    resyncFromServer,
    errors,
    setErrors,
    fieldState,
    effectiveState,
    isEditable,
    hasChanges,
    updateField,
    handleReset,
    handleClearOverride,
    buildChangedPayload,
    commitSaved,
    refreshMeta,
    retryInitialLoad: load,
  };
}
