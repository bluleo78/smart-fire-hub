/**
 * useSettingsOverrideForm 단위 테스트.
 *
 * 이 훅이 지키는 계약 중 **하나만 틀려도 조용한 데이터 사고**가 되는 것이 fetch-once 다:
 * 재조회가 `original` 을 서버 값으로 다시 시드하면, 사용자가 입력한 진짜 비밀번호가 담긴
 * `form` 과 마스크(`****3f2a`)로 갱신된 `original` 이 갈라져 저장 대상 판정
 * (`form[key] === original[key]` → 제외)이 무너진다. 그래서 호출 횟수만이 아니라
 * **`original` 이 첫 응답 그대로인지**까지 단언한다 — 횟수만 보면 가드 없이도 통과한다.
 */
import { act, renderHook, waitFor } from '@testing-library/react';
import { StrictMode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { settingsApi } from '../api/settings';
import type { ResolvedSettingResponse } from '../types/settings';
import { useSettingsOverrideForm } from './useSettingsOverrideForm';

vi.mock('../api/settings', () => ({
  settingsApi: {
    getByPrefix: vi.fn(),
    update: vi.fn(),
    clearOverride: vi.fn(),
  },
}));

// 토스트는 이 훅의 계약이 아니다 — jsdom 에서 sonner 가 DOM 을 건드리지 않게 막는다.
vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

type DemoForm = {
  'demo.host': string;
  'demo.password': string;
};

// 모듈 레벨 상수 — 훅 계약이 요구한다(인라인 객체 금지).
const DEMO_DEFAULTS: DemoForm = { 'demo.host': '', 'demo.password': '' };

// 플래그는 **이름 있는 인자**로 받는다. 위치 불리언(`row(k, v, false, false)`)은 호출부에서
// 어느 쪽이 overridden 이고 어느 쪽이 tenantEditable 인지 읽을 수 없고, 두 값을 맞바꿔 써도
// 타입이 통과한다 — 이 훅의 계약이 정확히 그 두 플래그의 해석이므로 값싸게 틀릴 자리를 없앤다.
const row = (
  key: string,
  value: string | null,
  { overridden = false, tenantEditable = true }: { overridden?: boolean; tenantEditable?: boolean } = {},
): ResolvedSettingResponse => ({
  key,
  value,
  description: null,
  updatedAt: null,
  overridden,
  tenantEditable,
});

const mockedGet = vi.mocked(settingsApi.getByPrefix);
const mockedClear = vi.mocked(settingsApi.clearOverride);

beforeEach(() => {
  vi.clearAllMocks();
  mockedGet.mockResolvedValue({
    data: [row('demo.host', 'smtp.platform.com'), row('demo.password', '****3f2a')],
  } as never);
});

describe('useSettingsOverrideForm', () => {
  /**
   * <b>StrictMode 안에서 렌더하는 것이 이 테스트의 전부다.</b> 평범한 `renderHook` + `rerender`
   * 로는 이 계약을 증명할 수 없다 — `load` 는 `useCallback([prefix])` 라 참조가 안정적이고,
   * 의존성이 그대로인 `useEffect` 는 재렌더에서 애초에 다시 돌지 않는다. 실제로 가드 두 줄을
   * 지운 변이에서도 그 형태의 테스트는 <b>초록으로 통과했다</b>(공허했다).
   *
   * StrictMode 는 마운트 시 effect 를 실행 → 정리 → 다시 실행한다. 가드가 없으면 두 번째
   * 실행이 재조회를 걸고, 그 응답으로 `original` 이 <b>덮어써진다</b>. 그래서 첫 응답과 이후
   * 응답의 마스크를 다르게 만들어(`mockResolvedValueOnce`) 호출 횟수만이 아니라 값까지
   * 어긋나게 한다 — 횟수 단언 하나만 두면 그것도 절반은 공허하다.
   */
  it('최초 1회만 조회하고, effect 재실행이 있어도 original 을 다시 시드하지 않는다', async () => {
    // 두 번째 이후의 조회는 다른 마스크를 내려준다 — 재조회가 일어나면 original 이 오염된다.
    mockedGet
      .mockResolvedValueOnce({
        data: [row('demo.host', 'smtp.platform.com'), row('demo.password', '****3f2a')],
      } as never)
      .mockResolvedValue({
        data: [row('demo.host', 'smtp.platform.com'), row('demo.password', '****ffff')],
      } as never);

    const { result, rerender } = renderHook(
      () => useSettingsOverrideForm<DemoForm>({ prefix: 'demo', defaults: DEMO_DEFAULTS }),
      { wrapper: StrictMode },
    );
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    // 사용자가 진짜 비밀번호를 입력한다.
    act(() => result.current.updateField('demo.password', 'real-app-password'));
    rerender();
    rerender();
    // 마운트 시 걸린 조회가 마이크로태스크로 늦게 도착할 수 있으므로 한 틱 흘려보낸다.
    await act(async () => {
      await Promise.resolve();
    });

    expect(mockedGet).toHaveBeenCalledTimes(1);
    // 호출 횟수만으로는 부족하다 — 값이 실제로 첫 응답 그대로여야 한다.
    expect(result.current.original['demo.password']).toBe('****3f2a');
    expect(result.current.form['demo.password']).toBe('real-app-password');
  });

  it('바뀐 키만 페이로드에 담고, 마스크가 그대로인 키는 제외한다', async () => {
    const { result } = renderHook(() =>
      useSettingsOverrideForm<DemoForm>({ prefix: 'demo', defaults: DEMO_DEFAULTS }),
    );
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    act(() => result.current.updateField('demo.host', 'smtp.ourcompany.com'));
    const { payload, droppedChangedKeys } = result.current.buildChangedPayload();

    expect(Object.keys(payload)).toEqual(['demo.host']);
    expect(payload['demo.host']).toBe('smtp.ourcompany.com');
    // 손대지 않은 비밀번호 마스크는 절대 실리지 않는다.
    expect(payload).not.toHaveProperty('demo.password');
    expect(droppedChangedKeys).toEqual([]);
  });

  it('blankAllowed 에 없는 키를 비우면 페이로드가 아니라 거부 목록으로 간다', async () => {
    const { result } = renderHook(() =>
      useSettingsOverrideForm<DemoForm>({
        prefix: 'demo',
        defaults: DEMO_DEFAULTS,
        blankAllowed: new Set<keyof DemoForm>(['demo.password']),
      }),
    );
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    act(() => {
      result.current.updateField('demo.host', '');
      result.current.updateField('demo.password', '');
    });
    const { payload, droppedChangedKeys } = result.current.buildChangedPayload();

    // 양성 대조군: 허용 키는 빈 값이어도 실제로 실린다 — 이것이 없으면 아래 단언은
    // "아무것도 안 실린다"로도 통과해 화이트리스트가 살아 있는지 증명하지 못한다.
    expect(payload).toEqual({ 'demo.password': '' });
    expect(droppedChangedKeys).toEqual(['demo.host']);
  });

  it('resolveState 를 주면 그 판정이 isEditable·hasChanges·페이로드를 모두 지배한다', async () => {
    const { result } = renderHook(() =>
      useSettingsOverrideForm<DemoForm>({
        prefix: 'demo',
        defaults: DEMO_DEFAULTS,
        // 그룹 개념 흉내 — 어느 한 키가 잠기면 둘 다 잠근다.
        resolveState: (_key, fieldState) =>
          (['demo.host', 'demo.password'] as (keyof DemoForm)[]).some(
            (k) => fieldState(k) === 'locked',
          )
            ? 'locked'
            : fieldState(_key),
      }),
    );
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    // 양성 대조군: 잠금이 없을 때는 편집 가능하다.
    expect(result.current.isEditable('demo.password')).toBe(true);

    mockedGet.mockResolvedValue({
      data: [row('demo.host', 'x', { tenantEditable: false }), row('demo.password', '****3f2a')],
    } as never);
    await act(async () => {
      await result.current.refreshMeta();
    });

    expect(result.current.isEditable('demo.password')).toBe(false);
    act(() => result.current.updateField('demo.password', 'whatever'));
    expect(result.current.hasChanges).toBe(false);
    expect(result.current.buildChangedPayload().payload).toEqual({});
  });

  it('조회 실패는 loadFailed 로 보고만 하고, 무엇을 할지는 호출자가 정한다', async () => {
    mockedGet.mockRejectedValue(new Error('boom'));
    const { result } = renderHook(() =>
      useSettingsOverrideForm<DemoForm>({ prefix: 'demo', defaults: DEMO_DEFAULTS }),
    );
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.loadFailed).toBe(true);
    expect(result.current.form).toEqual(DEMO_DEFAULTS);
  });

  /**
   * `emptySeedKeys` — 마스크를 편집 가능한 입력에 <b>시드하지 않는다</b>.
   *
   * 무엇을 막는가: 마스크가 시드돼 있으면 사용자가 그 뒤에 진짜 키를 덧붙일 수 있고
   * (`****3f2asecret`), 그 문자열은 서버 센티널 판정(`****` 로 시작 + 길이가 정확히 4 또는 8)을
   * 빠져나가 <b>테넌트의 진짜 자격증명으로 저장</b>된다.
   *
   * <b>`form` 과 `original` 을 둘 다 단언한다.</b> `form` 만 보면 `original` 에 마스크가 남은
   * 변이에서도 통과하는데, 그 상태는 손대지 않은 비밀이 `form !== original` 이 되어 "빈 값으로
   * 바꿨다"로 판정되는 더 나쁜 결함이다 — 그래서 아래에서 페이로드 제외까지 이어서 확인한다.
   */
  it('emptySeedKeys 의 키는 서버 마스크 대신 빈 값으로 form·original 을 함께 시드한다', async () => {
    const { result } = renderHook(() =>
      useSettingsOverrideForm<DemoForm>({
        prefix: 'demo',
        defaults: DEMO_DEFAULTS,
        emptySeedKeys: new Set<keyof DemoForm>(['demo.password']),
      }),
    );
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.form['demo.password']).toBe('');
    expect(result.current.original['demo.password']).toBe('');
    // 음성 대조군: 집합 밖의 키는 여전히 서버 값으로 시드된다 — 이것이 없으면 "아무것도 시드하지
    // 않는다"는 변이에서도 통과해 옵션이 <b>선택적</b>이라는 성질을 증명하지 못한다.
    expect(result.current.form['demo.host']).toBe('smtp.platform.com');

    // 손대지 않은 비밀은 페이로드에서 빠진다(이 성질이 깨지면 빈 문자열이 저장돼 자격증명이 날아간다).
    act(() => result.current.updateField('demo.host', 'smtp.ourcompany.com'));
    expect(result.current.buildChangedPayload()).toEqual({
      payload: { 'demo.host': 'smtp.ourcompany.com' },
      droppedChangedKeys: [],
    });
    // dirty 판정도 비밀 때문에 켜지면 안 된다(그러면 "안 바꿨는데 저장 버튼이 활성"이 된다).
    act(() => result.current.handleReset());
    expect(result.current.hasChanges).toBe(false);
  });

  /**
   * 재시드도 <b>조회와 같은 규칙</b>을 따라야 한다. 여기만 서버 값을 넣으면 저장·해제 직후 비밀
   * 입력에 새 마스크가 다시 들어앉아, 조회 경로에서 막은 덧붙이기 결함이 저장 경로로 되살아난다.
   */
  it('emptySeedKeys 의 키는 resyncFromServer 에서도 서버 마스크를 되돌려받지 않는다', async () => {
    mockedClear.mockResolvedValue({} as never);
    const { result } = renderHook(() =>
      useSettingsOverrideForm<DemoForm>({
        prefix: 'demo',
        defaults: DEMO_DEFAULTS,
        emptySeedKeys: new Set<keyof DemoForm>(['demo.password']),
      }),
    );
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    // 해제 후 재조회는 <b>새 마스크</b>를 내려준다 — 재시드가 서버 값을 쓰면 이 값이 폼에 들어온다.
    mockedGet.mockResolvedValue({
      data: [row('demo.host', 'smtp.platform.com'), row('demo.password', '****ffff')],
    } as never);
    await act(async () => {
      await result.current.handleClearOverride('demo.password');
    });

    expect(result.current.form['demo.password']).toBe('');
    expect(result.current.original['demo.password']).toBe('');
  });

  it('개별 재정의 해제는 그 키 하나만 서버 값으로 되돌리고 다른 필드의 편집을 건드리지 않는다', async () => {
    mockedClear.mockResolvedValue({} as never);
    const { result } = renderHook(() =>
      useSettingsOverrideForm<DemoForm>({ prefix: 'demo', defaults: DEMO_DEFAULTS }),
    );
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    act(() => result.current.updateField('demo.password', 'real-app-password'));
    mockedGet.mockResolvedValue({
      data: [row('demo.host', 'smtp.platform.com'), row('demo.password', '****3f2a')],
    } as never);
    await act(async () => {
      await result.current.handleClearOverride('demo.host');
    });

    expect(mockedClear).toHaveBeenCalledWith('demo.host');
    expect(result.current.form['demo.host']).toBe('smtp.platform.com');
    // 다른 필드의 미저장 편집은 살아 있어야 한다.
    expect(result.current.form['demo.password']).toBe('real-app-password');
  });
});
