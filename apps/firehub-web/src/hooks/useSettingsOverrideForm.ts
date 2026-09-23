import type { Dispatch, SetStateAction } from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';

import { settingsApi } from '../api/settings';
import { indexSettingsByKey } from '../lib/settings-fields';
import type { ResolvedSettingResponse } from '../types/settings';

/** 설정 폼 한 벌 — 키는 설정 키 문자열, 값은 항상 문자열이다(입력창이 문자열만 다룬다). */
export type SettingsFormShape = Record<string, string>;

export interface UseSettingsOverrideFormOptions<F extends SettingsFormShape> {
  /** `GET /settings?prefix=` 에 넘길 프리픽스. 지금 소비자는 AI 동작 설정('ai') 하나다. */
  prefix: string;
  /**
   * 서버 값이 없을 때 쓰는 키별 폴백. <b>모듈 레벨 상수여야 한다</b> — 인라인 객체를 넘기면
   * 렌더마다 새 참조가 되어 해제 폴백이 매번 다른 객체를 읽는다.
   *
   * AI 탭은 전부 빈 문자열을 넘긴다(서버가 저장값이 없으면 코드 기본값을 값으로 내려준다).
   */
  defaults: F;
  /**
   * 서버 메타를 실제로 다시 읽은 직후(최초 조회 성공 포함) 호출된다. 호출자가 "화면이 낡았다"
   * 안내를 <b>화면이 실제로 새로워진 그 지점에서</b> 지우는 데 쓴다 — 호출부마다 지우면
   * 하나를 빠뜨리고, 실제로 빠뜨린 전례가 있다.
   */
  onMetaRefreshed?: () => void;
}

export interface SettingsOverrideForm<F extends SettingsFormShape> {
  isLoading: boolean;
  /** 최초 조회가 실패했는가. 이것으로 무엇을 할지는 호출자가 정한다(RULING C). */
  loadFailed: boolean;
  settings: Record<string, ResolvedSettingResponse>;
  form: F;
  original: F;
  errors: Partial<Record<keyof F, string>>;
  setErrors: Dispatch<SetStateAction<Partial<Record<keyof F, string>>>>;
  hasChanges: boolean;
  updateField: (key: keyof F, value: string) => void;
  handleReset: () => void;
  buildChangedPayload: () => {
    payload: Record<string, string>;
    droppedChangedKeys: (keyof F)[];
  };
  /** 저장 성공 후 `original` 을 현재 폼으로 확정한다. */
  commitSaved: () => void;
  refreshMeta: () => Promise<Record<string, ResolvedSettingResponse>>;
  /**
   * <b>`loadFailed` 종단 화면의 "다시 시도" 버튼 전용</b>이다. 이름이 `refetch` 가 아닌 이유:
   * 평범한 "새로고침" 버튼에 물리면 살아 있는 편집 위로 `original` 이 재시드되어
   * 저장 대상 판정이 무너진다. 잘못 쓸 자리를 이름으로 좁힌다.
   */
  retryInitialLoad: () => Promise<void>;
}

/**
 * 키·값 평면 설정의 <b>필드 단위</b> 폼 상태 기계. 지금 소비자는 AI 탭의 동작 설정 6키 하나다.
 *
 * 소유하는 것: 최초 조회·폼/원본 시드, 메타 갱신, "바꾼 키만" 페이로드 diff, `hasChanges`, 되돌리기.
 *
 * <b>소유하지 않는 것</b>:
 * - <b>낡음 안내(`staleNotice`)</b> — 훅은 갱신이 실제로 일어난 지점을 `onMetaRefreshed` 로
 *   알릴 뿐, 그 사실로 어떤 배너를 세우고 지울지는 호출자의 표현 문제다.
 * - <b>조회 실패 종단 화면</b> — `loadFailed` 를 보고만 한다(RULING C). AI 탭은 toast 만 띄우고
 *   폴백 값으로 렌더한다.
 *
 * <b>이메일(SMTP) 탭은 더 이상 이 훅을 쓰지 않는다(#712).</b> SMTP 가 워크스페이스 전용이 되면서
 * "바꾼 키만 보낸다"는 이 훅의 핵심 규칙이 SMTP 에는 맞지 않게 됐다(6키를 한 벌로 저장하고 한 벌로
 * 해제한다). 그래서 `useSmtpSettingsForm` 은 `useAiClassifyForm` 처럼 자기 상태 기계를 갖고,
 * 이 훅에서는 SMTP 전용이던 키별 해제(DELETE)·상태 치환·비밀 시드 규칙·빈 값 허용 목록을 걷어냈다.
 * AI 동작 설정 6키는 전부 워크스페이스가 편집할 수 있고 비밀이 없으며 빈 값을 허용하지 않는다.
 */
export function useSettingsOverrideForm<F extends SettingsFormShape>({
  prefix,
  defaults,
  onMetaRefreshed,
}: UseSettingsOverrideFormOptions<F>): SettingsOverrideForm<F> {
  const [isLoading, setIsLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  // 서버 응답을 키로 인덱싱해 보관한다 — 기본값 힌트(overridden)의 근거.
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
   * 사용자가 편집 중인 `form` 과 새 서버 값으로 갱신된 `original` 이 갈라져 저장 대상 판정
   * (`form[key] === original[key]` → 제외)이 무너진다 — 실제 편집이 "안 바뀐 키"로 조용히 누락된다.
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
   * 플래그만 다시 읽고 인덱싱된 맵을 돌려준다 — 폼 값은 건드리지 않으므로 입력 중인 내용이
   * 사라지지 않는다. 실패를 여기서 삼키지 않는다: 저장 후 재조회 실패를 삼키면 화면이 낡았다는
   * 사실이 조용히 묻힌다. 삼킴 여부는 호출부가 정한다.
   */
  const refreshMeta = useCallback(async () => {
    const { data } = await settingsApi.getByPrefix(prefix);
    const byKey = indexSettingsByKey(data);
    setSettings(byKey);
    metaRefreshedRef.current?.();
    return byKey;
  }, [prefix]);

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
   * 저장 페이로드는 <b>이번에 바꾼 키만</b> 담는다. 전 키를 보내면 사용자가 한 필드를 고쳐도
   * 나머지가 같은 값으로 tenant_settings 에 기록되어 <b>코드 기본값 추종이 조용히 끊긴다</b> —
   * 그 뒤로는 기본값이 바뀌어도 이 워크스페이스에는 전파되지 않는다.
   *
   * 사용자가 방금 <b>비운</b> 키는 페이로드에서 빼는 대신 `droppedChangedKeys` 로 돌려준다.
   * 그냥 빼면 "저장했다"면서 아무것도 쓰지 않고 dirty 까지 지워, 사용자는 반영된 줄 알고
   * 떠나는데 옛 저장값이 그대로 적용된다.
   */
  const buildChangedPayload = () => {
    const payload: Record<string, string> = {};
    const droppedChangedKeys: (keyof F)[] = [];
    (Object.keys(form) as (keyof F)[]).forEach((key) => {
      if (form[key] === original[key]) return;
      if (form[key].trim() !== '') {
        payload[key as string] = form[key];
      } else {
        droppedChangedKeys.push(key);
      }
    });
    return { payload, droppedChangedKeys };
  };

  const commitSaved = () => setOriginal({ ...form });

  // dirty 판정도 저장 대상과 같은 기준(원본과 다른 키)을 쓴다.
  const hasChanges = (Object.keys(form) as (keyof F)[]).some((key) => form[key] !== original[key]);

  return {
    isLoading,
    loadFailed,
    settings,
    form,
    original,
    errors,
    setErrors,
    hasChanges,
    updateField,
    handleReset,
    buildChangedPayload,
    commitSaved,
    refreshMeta,
    retryInitialLoad: load,
  };
}
