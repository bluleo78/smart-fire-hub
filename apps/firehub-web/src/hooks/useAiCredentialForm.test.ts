/**
 * useAiCredentialForm 단위 테스트.
 *
 * 이 훅이 지키는 계약 중 하나라도 틀리면 실제 사고로 이어진다 — "빈 비밀 입력을 보내지 않는다"
 * (건드리지 않은 비밀이 삭제됨), "canLoadModels 의 저장된 키 절반"(400 이 나는 버튼을 활성으로
 * 보여줌), "유형 전환 시 payload/secret 초기화"(다른 유형의 비밀이 새 유형 필드로 새어나감)가
 * 전부 여기 있다. 모든 테스트는 실제 훅(`useAiCredentialForm`)을 호출한다 — 로직을 훅 밖에
 * 재구현해 그것을 검증하지 않는다.
 */
import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { settingsApi } from '../api/settings';
import type { AiCredentialResponse } from '../types/settings';
import { useAiCredentialForm } from './useAiCredentialForm';

vi.mock('../api/settings', () => ({
  settingsApi: {
    getAiCredential: vi.fn(),
    putAiCredential: vi.fn(),
    deleteAiCredential: vi.fn(),
    probeAiCredential: vi.fn(),
  },
}));

// 토스트는 이 훅의 계약이 아니다 — jsdom 에서 sonner 가 DOM 을 건드리지 않게 막는다.
vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

const mockedGet = vi.mocked(settingsApi.getAiCredential);
const mockedPut = vi.mocked(settingsApi.putAiCredential);
const mockedDelete = vi.mocked(settingsApi.deleteAiCredential);
const mockedProbe = vi.mocked(settingsApi.probeAiCredential);

/** GET 응답 픽스처 빌더. 필드별 기본값은 "테넌트가 opencode 를 직접 설정해 둔 상태"다. */
function makeResponse(overrides: Partial<AiCredentialResponse> = {}): AiCredentialResponse {
  return {
    agentType: 'opencode',
    payload: { providerId: 'openai', baseURL: 'https://x.example/v1', reasoningEffort: 'medium' },
    secretFieldNames: [],
    tenantOwned: true,
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockedGet.mockResolvedValue({ data: makeResponse() } as never);
  mockedPut.mockResolvedValue({} as never);
  mockedDelete.mockResolvedValue({} as never);
  mockedProbe.mockResolvedValue({ data: { ok: true, models: ['gpt-4o'], message: null } } as never);
});

/** 초기 GET 이 끝날 때까지 기다린다 — 이후에 폼을 조작해야 `load()` 의 시딩이 조작값을 덮지 않는다. */
async function renderLoaded() {
  const view = renderHook(() => useAiCredentialForm());
  await waitFor(() => expect(view.result.current.isLoading).toBe(false));
  return view;
}

describe('useAiCredentialForm', () => {
  it('라디오 전환은 폼 상태다 — 저장 전에는 DELETE 를 부르지 않는다', async () => {
    const { result } = await renderLoaded();
    act(() => result.current.setPlane('platform'));
    act(() => result.current.setPlane('tenant'));
    expect(mockedDelete).not.toHaveBeenCalled();
  });

  it('라디오만 만지면 hasUnsavedInput 이 노이즈를 내지 않는다', async () => {
    // 라디오 전환 자체가 저장 확인 다이얼로그를 여는 계기다 — 아무 필드도 입력하지 않았는데
    // "미저장 입력이 있습니다" 덧문구까지 매번 뜨면 그 문구가 잡음이 된다.
    const { result } = await renderLoaded(); // 기본 픽스처: tenantOwned true → plane 'tenant'
    act(() => result.current.setPlane('platform'));
    expect(result.current.hasUnsavedInput).toBe(false);
    act(() => result.current.setPlane('tenant'));
    expect(result.current.hasUnsavedInput).toBe(false);
  });

  it('tenantOwned 는 plane(폼 상태)과 별개로 서버가 확정한 소유 평면을 유지한다', async () => {
    // Task 11 이 "플랫폼 설정을 사용" 화면에서 지금 GET 결과를 "플랫폼 값"이라고 그려도 되는지
    // 판단하는 근거다. 라디오만 만졌을 뿐 아직 저장하지 않았으면 서버의 실제 소유는 그대로다.
    const { result } = await renderLoaded(); // 기본 픽스처: tenantOwned true
    expect(result.current.tenantOwned).toBe(true);

    act(() => result.current.setPlane('platform'));
    expect(result.current.plane).toBe('platform');
    expect(result.current.tenantOwned).toBe(true); // 저장 전이므로 그대로다 — plane 과 갈라진다
  });

  it('비밀 입력을 건드리지 않으면 secret 에 싣지 않는다', async () => {
    const { result } = await renderLoaded();
    act(() => result.current.setPayloadField('baseURL', 'https://y.example/v1'));
    await act(() => result.current.save());
    expect(mockedPut).toHaveBeenCalledTimes(1);
    expect(mockedPut.mock.calls[0][0].secret).toEqual({});
  });

  it('빈 문자열로 지운 비밀도, 건드리지 않은 비밀도 PUT 에는 똑같이 빠진다 — 빈 문자열 값은 절대 안 싣는다', async () => {
    // 이 훅은 필드 단위 "삭제" 제스처를 제공하지 않는다(위 useAiCredentialForm.ts 의 해당 분기
    // 주석 참고) — 빈 값은 "생략"과 같은 결과여야 한다. 리뷰어의 뮤턴트(`typed !== undefined`)는
    // "타이핑했다가 지운 값"과 "아예 안 건드린 값"을 구분해 전자를 `''`(서버 계약상 삭제)로
    // 실어 보낸다 — 기본 URL 만 고치고 저장해도 저장된 API 키가 조용히 사라지는 경로다.
    mockedGet.mockResolvedValue({
      data: makeResponse({ agentType: 'sdk', payload: {}, secretFieldNames: ['oauthToken', 'apiKey'] }),
    } as never);
    const { result } = await renderLoaded();

    // apiKey: 타이핑했다가 다시 지운다("삭제하려는 것처럼 보이는" 제스처).
    act(() => result.current.setSecretInput('apiKey', 'sk-typed'));
    act(() => result.current.setSecretInput('apiKey', ''));
    // oauthToken: 아예 건드리지 않는다.

    await act(() => result.current.save());

    const sentSecret = mockedPut.mock.calls[0][0].secret;
    expect(sentSecret).toEqual({}); // '' 값이 실린 키가 하나도 없어야 한다
    expect(sentSecret).not.toHaveProperty('apiKey');
    expect(sentSecret).not.toHaveProperty('oauthToken');
  });

  it('로드 직후 secretFieldNames 가 있어도 secretInputs 는 비어 있다(마스크 시드 금지)', async () => {
    // secretFieldNames 는 "이름"만 준다 — 값 자체를 시드할 원본이 없다. 여기서 플레이스홀더를
    // 채워 넣으면 다음 save() 가 그 플레이스홀더를 진짜 비밀값으로 암호화해 버린다.
    mockedGet.mockResolvedValue({ data: makeResponse({ secretFieldNames: ['apiKey'] }) } as never);
    const { result } = await renderLoaded();
    expect(result.current.secretInputs).toEqual({});
  });

  describe('canLoadModels', () => {
    it('저장된 키가 있으면(같은 유형·테넌트 소유) 키 입력 없이도 활성이다', async () => {
      mockedGet.mockResolvedValue({ data: makeResponse({ secretFieldNames: ['apiKey'] }) } as never);
      const { result } = await renderLoaded();
      act(() => result.current.setPayloadField('baseURL', 'https://x.example/v1'));
      expect(result.current.canLoadModels).toBe(true);
    });

    it('저장된 키도 타이핑한 키도 없으면 기본 URL 이 있어도 비활성이다', async () => {
      // 느슨해지는 방향의 회귀를 잡는다 — `secretFieldNames.includes('apiKey')` 를 `true` 로
      // 바꿔치기해도(저장된 키가 있는 척) 이 테스트가 없으면 들키지 않는다. 기본 픽스처는
      // secretFieldNames: [] 이고 아무 키도 타이핑하지 않았다.
      const { result } = await renderLoaded();
      expect(result.current.canLoadModels).toBe(false);
    });

    it('저장된 키가 있어도 기본 URL 을 저장된 값과 다르게 고치면 재사용하지 않는다(Ruling #37)', async () => {
      // 스펙 "프로브" 절: "baseURL 이 저장된 값과 다르면 apiKey 를 요청에 반드시 포함해야
      // 한다." 서버는 이것을 MSG_BASE_URL_MISMATCH → 400 으로 강제한다. 저장된 키가 있고
      // 유형·평면이 맞아도, 기본 URL 만 고친 채 키를 생략하면 그 400 을 그대로 맞는다 —
      // Ruling #35 가 고친 것과 같은 모양의 막다른 버튼이 기본 URL 변경 경로로 열려 있었다.
      mockedGet.mockResolvedValue({ data: makeResponse({ secretFieldNames: ['apiKey'] }) } as never);
      const { result } = await renderLoaded();
      expect(result.current.canLoadModels).toBe(true); // 고치기 전 sanity check

      act(() => result.current.setPayloadField('baseURL', 'https://different.example/v1'));
      expect(result.current.canLoadModels).toBe(false);
    });

    it('타이핑한 키만 있어도 활성이다(저장된 키가 전혀 없어도)', async () => {
      const { result } = await renderLoaded(); // 기본 픽스처: secretFieldNames: []
      act(() => result.current.setSecretInput('apiKey', 'sk-typed'));
      expect(result.current.canLoadModels).toBe(true);
    });

    it('기본 URL 이 없으면 저장된 키·타이핑한 키가 있어도 비활성이다', async () => {
      mockedGet.mockResolvedValue({
        data: makeResponse({ payload: { providerId: 'openai', baseURL: '', reasoningEffort: '' }, secretFieldNames: ['apiKey'] }),
      } as never);
      const { result } = await renderLoaded();
      expect(result.current.canLoadModels).toBe(false);
    });

    it('플랫폼 상속 중(tenantOwned=false)이면 저장된 키를 재사용하지 않는다 — 평면 교차 폴백 금지', async () => {
      // secretFieldNames 가 apiKey 를 포함해도, 그것은 "플랫폼의" 키다. 프로브가 생략된 키를
      // 재사용할 때 테넌트 행만 보므로(OpencodeProbeService), 이 조건에서 활성으로 보이면
      // 눌렀을 때 400 이 나는 막다른 버튼이 된다(설계서 "프로브" 절).
      mockedGet.mockResolvedValue({
        data: makeResponse({ secretFieldNames: ['apiKey'], tenantOwned: false }),
      } as never);
      const { result } = await renderLoaded();
      // 상속 중이던 테넌트가 "직접 설정" 을 막 연 상황을 흉내낸다.
      act(() => result.current.setPlane('tenant'));
      expect(result.current.canLoadModels).toBe(false);
      // Ruling #38: 노출되는 secretFieldNames 자체도 걸러진다 — 이 상태에서 "현재 값이
      // 설정되어 있습니다"를 그릴 근거가 화면에 없어야 한다(플랫폼 문서의 비밀이지 테넌트
      // 문서의 비밀이 아니다).
      expect(result.current.secretFieldNames).toEqual([]);
    });

    it('저장된 키가 플랫폼 것뿐이면(plane 을 건드리지 않아도) 재사용 판정에 넣지 않는다', async () => {
      // 위 테스트와 달리 plane 을 'tenant' 로 토글하지 않는다 — tenantOwned=false 면 plane 은
      // 로드 직후 그대로 'platform' 이고, 그 상태에서도 visibleSecretFieldNames 는
      // agentType·plane 조건만으로 'apiKey' 를 통과시킬 수 있다(원본 문서의 실제 소유 평면이
      // 'platform' 이므로). `hasStoredApiKey` 의 `original.tenantOwned` 가드가 없으면 이
      // 경우에도 canLoadModels 가 true 로 새 나간다 — 그 가드 자체를 이 시나리오로 고정한다.
      mockedGet.mockResolvedValue({
        data: makeResponse({ secretFieldNames: ['apiKey'], tenantOwned: false }),
      } as never);
      const { result } = await renderLoaded();
      expect(result.current.plane).toBe('platform'); // 토글하지 않았다 — sanity check
      expect(result.current.canLoadModels).toBe(false);
    });

    it('유형을 로컬에서 바꾸면 노출되는 secretFieldNames 가 즉시 비워진다(Ruling #38)', async () => {
      mockedGet.mockResolvedValue({ data: makeResponse({ secretFieldNames: ['apiKey'] }) } as never);
      const { result } = await renderLoaded();
      expect(result.current.secretFieldNames).toEqual(['apiKey']); // 전환 전 sanity check

      act(() => result.current.setAgentType('sdk'));
      expect(result.current.secretFieldNames).toEqual([]);
    });

    it('로컬에서 유형만 바꾸고 아직 저장하지 않았으면 저장된 키를 재사용하지 않는다', async () => {
      // 서버에 저장된 문서는 여전히 opencode 다. sdk 로 전환한 채(아직 저장 안 함) baseURL 을
      // 손으로 다시 채워도(원래 sdk 필드는 아니지만 raw setter 라 막지 않는다), 그 baseURL 로
      // 프로브를 날리면서 opencode 문서의 apiKey 를 재사용하면 안 된다.
      mockedGet.mockResolvedValue({ data: makeResponse({ secretFieldNames: ['apiKey'] }) } as never);
      const { result } = await renderLoaded();
      expect(result.current.canLoadModels).toBe(true); // 스위치 전 sanity check

      act(() => result.current.setAgentType('sdk'));
      act(() => result.current.setPayloadField('baseURL', 'https://x.example/v1'));
      expect(result.current.canLoadModels).toBe(false);
    });
  });

  it('기본 URL 을 고치면 모델 목록이 무효화된다', async () => {
    const { result } = await renderLoaded();
    await act(() => result.current.loadModels());
    expect(result.current.models).not.toBeNull();

    act(() => result.current.setPayloadField('baseURL', 'https://other.example/v1'));
    expect(result.current.models).toBeNull();
  });

  it('프로브가 4xx(요청 형태 오류)로 응답하면 modelsError 에 서버 메시지를 담는다', async () => {
    // ok:false 는 200 으로 오는 upstream 실패이고, 이것은 별개 경로다 — 요청 형태 자체가
    // 잘못됐을 때(예: tenantOwned=false 인데 secret 생략) HTTP 오류로 온다(설계서 "프로브" 절).
    mockedProbe.mockRejectedValue(
      Object.assign(new Error('bad request'), {
        isAxiosError: true,
        response: { status: 400, data: { message: 'tenantOwned=false 인데 secret 을 생략했습니다.' } },
      }),
    );
    const { result } = await renderLoaded();
    await act(() => result.current.loadModels());
    expect(result.current.models).toBeNull();
    expect(result.current.modelsError).toBe('tenantOwned=false 인데 secret 을 생략했습니다.');
  });

  it('프로브가 ok:false 로 응답하면 modelsError 에 메시지를 담고 models 는 null 이다', async () => {
    mockedProbe.mockResolvedValue({
      data: { ok: false, models: [], message: '자격증명이 거부되었습니다.' },
    } as never);
    const { result } = await renderLoaded();
    await act(() => result.current.loadModels());
    expect(result.current.models).toBeNull();
    expect(result.current.modelsError).toBe('자격증명이 거부되었습니다.');
  });

  it('유형을 바꾸면 이전 유형의 비밀 입력이 폼에서 지워진다', async () => {
    mockedGet.mockResolvedValue({ data: makeResponse({ agentType: 'sdk', payload: {} }) } as never);
    const { result } = await renderLoaded();
    act(() => result.current.setSecretInput('oauthToken', 'oat'));
    expect(result.current.secretInputs.oauthToken).toBe('oat');

    act(() => result.current.setAgentType('opencode'));
    expect(result.current.secretInputs.oauthToken).toBeUndefined();
  });

  it('유형을 바꾸면 이전 유형의 payload 도 지워진다 — 저장 시 옛 필드를 싣지 않는다', async () => {
    // 기본 픽스처는 opencode(payload 에 providerId/baseURL/reasoningEffort 가 있다). sdk 로
    // 바꾼 뒤 저장하면 그 payload 가 조금도 남아 있으면 안 된다 — sdk 는 payload 가 없는 유형이다.
    const { result } = await renderLoaded();
    act(() => result.current.setAgentType('sdk'));
    await act(() => result.current.save());

    expect(mockedPut).toHaveBeenCalledTimes(1);
    expect(mockedPut.mock.calls[0][0].agentType).toBe('sdk');
    expect(mockedPut.mock.calls[0][0].payload).toEqual({});
  });

  it('유형을 두 번 바꿔도(opencode → sdk → opencode) 원래 payload 가 되살아나지 않는다', async () => {
    // save() 는 목적 유형의 필드만 걸러 보내므로, opencode → sdk 전환만으로는(sdk 는 payload
    // 필드가 아예 없다) payload 를 지우지 않는 버그를 가려낼 수 없다 — payloadOut 이 항상 {}
    // 이기 때문이다. sdk 를 한 번 거쳐 다시 opencode 로 돌아왔을 때도 baseURL 이 원래 값으로
    // 살아 있으면, "유형이 바뀌면 payload 를 지운다"가 로컬 상태 수준에서 실제로 동작하지
    // 않는다는 뜻이다(save() 의 목적지 필터링에 가려 조용히 통과하는 버그).
    const { result } = await renderLoaded(); // 기본 픽스처: opencode, baseURL='https://x.example/v1'
    act(() => result.current.setAgentType('sdk'));
    act(() => result.current.setAgentType('opencode'));
    await act(() => result.current.save());

    expect(mockedPut.mock.calls[0][0].payload.baseURL).toBe('');
  });

  it('plane이 platform이면 save()는 DELETE만 호출한다(PUT 은 호출하지 않는다)', async () => {
    mockedGet.mockResolvedValue({ data: makeResponse({ tenantOwned: false }) } as never);
    const { result } = await renderLoaded();
    expect(result.current.plane).toBe('platform');

    await act(() => result.current.save());
    expect(mockedDelete).toHaveBeenCalledTimes(1);
    expect(mockedPut).not.toHaveBeenCalled();
  });

  it('plane이 tenant이면 save()는 PUT만 호출한다(DELETE 는 호출하지 않는다)', async () => {
    const { result } = await renderLoaded(); // 기본 픽스처: tenantOwned true → plane 'tenant'
    expect(result.current.plane).toBe('tenant');

    await act(() => result.current.save());
    expect(mockedPut).toHaveBeenCalledTimes(1);
    expect(mockedDelete).not.toHaveBeenCalled();
  });

  it('저장에 성공하면 secretInputs 가 비워지고 hasUnsavedInput 이 false 가 된다', async () => {
    mockedGet
      .mockResolvedValueOnce({ data: makeResponse({ agentType: 'cli-api', payload: {}, secretFieldNames: [] }) } as never)
      .mockResolvedValueOnce({
        data: makeResponse({ agentType: 'cli-api', payload: {}, secretFieldNames: ['apiKey'] }),
      } as never);
    const { result } = await renderLoaded();

    act(() => result.current.setSecretInput('apiKey', 'sk-new'));
    expect(result.current.hasUnsavedInput).toBe(true);

    await act(() => result.current.save());

    // 타이핑한 값이 실제로 PUT 바디에 실렸는지 — mock 이 되돌려준 "저장 후" 상태가 아니라
    // 훅이 <b>보낸</b> 요청 그 자체를 본다. 이 단언이 없으면 secretOut 조립이 통째로 사라져도
    // (예: `if (typed && ...)` 를 `if (false)` 로 바꿔도) 아래 상태 단언들은 여전히 초록이다 —
    // mock 의 두 번째 GET 이 `secretFieldNames: ['apiKey']` 를 그대로 되돌려주기 때문이다.
    expect(mockedPut.mock.calls[0][0].secret).toEqual({ apiKey: 'sk-new' });

    expect(result.current.secretInputs).toEqual({});
    expect(result.current.hasUnsavedInput).toBe(false);
    // 재조회가 새 secretFieldNames 를 반영했다 — 다음 canLoadModels 판정이 이 값을 쓴다.
    expect(result.current.secretFieldNames).toEqual(['apiKey']);
  });

  it('저장은 성공했지만 재조회가 실패하면 staleNotice 를 세운다', async () => {
    mockedGet
      .mockResolvedValueOnce({ data: makeResponse() } as never)
      .mockRejectedValueOnce(new Error('network down'));
    const { result } = await renderLoaded();

    await act(() => result.current.save());
    expect(result.current.staleNotice).not.toBeNull();
  });

  it('GET 이 403 이면 isLocked 를 세우고 loadFailed 는 세우지 않는다', async () => {
    mockedGet.mockRejectedValue(
      Object.assign(new Error('forbidden'), { isAxiosError: true, response: { status: 403, data: {} } }),
    );
    const { result } = renderHook(() => useAiCredentialForm());
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.isLocked).toBe(true);
    expect(result.current.loadFailed).toBe(false);
  });

  it('GET 이 403 이 아닌 오류면 loadFailed 를 세우고 isLocked 는 세우지 않는다', async () => {
    mockedGet.mockRejectedValue(new Error('network down'));
    const { result } = renderHook(() => useAiCredentialForm());
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.loadFailed).toBe(true);
    expect(result.current.isLocked).toBe(false);
  });

  /**
   * fix round 1 — Ruling #42: `save()` 가 쓰기(PUT/DELETE)의 성공/실패를 boolean 으로
   * 보고한다. 재조회 실패는 "쓰기 실패"가 아니므로 여전히 `true` 다 — 페이지가 이 값으로
   * `verifyAuth()` 호출을 조건부로 만든다(위 재조회 실패 테스트가 staleNotice 를, 이 테스트가
   * 반환값을 각각 고정한다).
   */
  it('저장이 성공하면(재조회가 실패해도) save() 는 true 를 돌려준다', async () => {
    mockedGet
      .mockResolvedValueOnce({ data: makeResponse() } as never)
      .mockRejectedValueOnce(new Error('network down'));
    const { result } = await renderLoaded();

    let resolved: boolean | undefined;
    await act(async () => {
      resolved = await result.current.save();
    });
    expect(resolved).toBe(true);
  });

  it('PUT 이 실패하면 save() 는 false 를 돌려준다', async () => {
    const { result } = await renderLoaded(); // 기본 픽스처: plane 'tenant' → PUT 경로
    mockedPut.mockRejectedValueOnce(new Error('500'));

    let resolved: boolean | undefined;
    await act(async () => {
      resolved = await result.current.save();
    });
    expect(resolved).toBe(false);
  });

  it('DELETE 가 실패하면 save() 는 false 를 돌려준다', async () => {
    mockedGet.mockResolvedValue({ data: makeResponse({ tenantOwned: false }) } as never);
    const { result } = await renderLoaded(); // plane 'platform' → DELETE 경로
    mockedDelete.mockRejectedValueOnce(new Error('500'));

    let resolved: boolean | undefined;
    await act(async () => {
      resolved = await result.current.save();
    });
    expect(resolved).toBe(false);
  });

  /**
   * fix round 1 — Ruling #41: `reset()` 이 로컬 편집을 마지막 `original` 스냅샷으로 되돌린다.
   * 네트워크 호출 없이 즉시 반영되는지(= `save()`/재조회를 부르지 않는지)까지 함께 고정한다 —
   * "되돌리기"가 서버를 다시 때리면 그 자체로 또 하나의 예상 밖 부작용이 된다.
   */
  it('reset() 은 유형·payload·비밀 입력을 마지막 동기화 상태로 되돌리고 네트워크를 부르지 않는다', async () => {
    const { result } = await renderLoaded(); // 기본 픽스처: opencode, tenantOwned true
    const getCallsBefore = mockedGet.mock.calls.length;

    act(() => {
      result.current.setAgentType('sdk');
      result.current.setSecretInput('apiKey', 'sk-typed');
    });
    expect(result.current.agentType).toBe('sdk');
    expect(result.current.hasUnsavedInput).toBe(true);

    act(() => result.current.reset());

    expect(result.current.agentType).toBe('opencode');
    expect(result.current.secretInputs).toEqual({});
    expect(result.current.hasUnsavedInput).toBe(false);
    expect(mockedGet.mock.calls.length).toBe(getCallsBefore); // 재조회 없음
    expect(mockedPut).not.toHaveBeenCalled();
    expect(mockedDelete).not.toHaveBeenCalled();
  });

  it('reset() 은 라디오(plane)도 마지막 소유 평면으로 되돌린다', async () => {
    const { result } = await renderLoaded(); // 기본 픽스처: tenantOwned true → plane 'tenant'
    act(() => result.current.setPlane('platform'));
    expect(result.current.plane).toBe('platform');

    act(() => result.current.reset());
    expect(result.current.plane).toBe('tenant');
  });
});
