import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import axios, { AxiosHeaders } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AUTH_FLAG_KEY, cancelQueuedRequests, client, getAccessToken, setAccessToken } from './client';

/**
 * `client.ts` 의 401 재시도 큐 — 이 앱에서 보안 하중이 가장 큰 코드인데 지금까지 자동 검증이
 * 0건이었다(M2). msw/axios-mock-adapter 를 추가하지 않고 axios 자체의 커스텀 adapter 로
 * 요청/응답 인터셉터 체인 전체를 실제로 실행시켜, 각 시나리오를 코드 변경 없이 고정한다.
 *
 * adapter 는 axios 가 실제 네트워크 대신 부르는 최종 단계다 — adapter 를 바꿔치기해도
 * `client.interceptors.*` 로 등록된 요청/응답 인터셉터는 정상 경로 그대로 실행된다.
 */

interface FakeResult {
  status: number;
  data?: unknown;
}

/**
 * url(+ method) 별 스크립트를 등록해 커스텀 axios adapter 를 만든다.
 *
 * 가짜 응답을 `AxiosResponse` 실제 계약에 맞춰 구성한다(`as any` 로 뭉개지 않는다) —
 * `config` 를 `InternalAxiosRequestConfig`(headers 가 필수인 정규화된 타입)로 받고,
 * 응답/에러의 `headers` 는 `AxiosHeaders` 인스턴스로 채운다. 그래야 이 테스트가 검증하는
 * 것이 실제 axios 어댑터 계약과 어긋나지 않는다.
 */
function makeAdapter(
  script: (config: InternalAxiosRequestConfig) => FakeResult | Promise<FakeResult>,
) {
  return async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const result = await script(config);
    if (result.status >= 200 && result.status < 300) {
      return {
        data: result.data ?? {},
        status: result.status,
        statusText: 'OK',
        headers: new AxiosHeaders(),
        config,
      };
    }
    // axios 응답 인터셉터가 보는 것은 `error.response.status` 뿐이지만, 흉내내는 모양 자체는
    // 실제 `AxiosResponse` 형태를 그대로 채운다.
    const response: AxiosResponse = {
      data: result.data ?? {},
      status: result.status,
      statusText: '',
      headers: new AxiosHeaders(),
      config,
    };
    return Promise.reject({ config, response, isAxiosError: true });
  };
}

beforeEach(() => {
  setAccessToken(null);
  localStorage.clear();
  // 코드가 refresh 실패 시 `window.location.href = '/login'` 로 강제 이동한다. jsdom 은 실제
  // 내비게이션을 구현하지 않아 대입만으로는 "Not implemented: navigation" 경고가 뜬다 —
  // href setter 를 관측 가능한 스텁으로 바꿔 그 대입 자체를 테스트 대상으로 만든다.
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: { href: '' },
    writable: true,
  });
});

afterEach(() => {
  client.defaults.adapter = undefined;
  axios.defaults.adapter = undefined;
  vi.restoreAllMocks();
});

describe('client.ts — 401 재시도 큐', () => {
  it('1) 401 → refresh 성공 → 원 요청이 새 토큰으로 재시도되어 성공한다', async () => {
    let refreshCalls = 0;
    let protectedCalls = 0;

    const adapter = makeAdapter((config) => {
      if (config.url === '/api/platform/auth/refresh') {
        refreshCalls += 1;
        return { status: 200, data: { accessToken: 'new-token', tokenType: 'Bearer', expiresIn: 1800, permissions: [], refreshToken: null } };
      }
      if (config.url === '/protected') {
        protectedCalls += 1;
        if (protectedCalls === 1) return { status: 401 };
        // 재시도는 새 토큰을 달고 와야 한다.
        expect(config.headers?.Authorization).toBe('Bearer new-token');
        return { status: 200, data: { ok: true } };
      }
      throw new Error(`unexpected url: ${config.url}`);
    });
    client.defaults.adapter = adapter;
    axios.defaults.adapter = adapter;

    setAccessToken('old-token');
    const res = await client.get('/protected');

    expect(res.data).toEqual({ ok: true });
    expect(protectedCalls).toBe(2);
    expect(refreshCalls).toBe(1);
    expect(getAccessToken()).toBe('new-token');
  });

  it('2) refresh 자체가 401 → 큐가 비워지고 /login 으로 가며 무한 루프가 없다', async () => {
    let refreshCalls = 0;

    const adapter = makeAdapter((config) => {
      if (config.url === '/api/platform/auth/refresh') {
        refreshCalls += 1;
        return { status: 401 };
      }
      if (config.url === '/protected') {
        return { status: 401 };
      }
      throw new Error(`unexpected url: ${config.url}`);
    });
    client.defaults.adapter = adapter;
    axios.defaults.adapter = adapter;

    localStorage.setItem(AUTH_FLAG_KEY, 'true');
    setAccessToken('old-token');

    await expect(client.get('/protected')).rejects.toBeTruthy();

    // refresh 는 정확히 1회만 나갔다(무한 루프 없음) — url 필터(`includes('/auth/refresh')`)가
    // refresh 요청 자체의 401 을 다시 갱신 대상으로 삼지 않는다.
    expect(refreshCalls).toBe(1);
    expect(getAccessToken()).toBeNull();
    expect(localStorage.getItem(AUTH_FLAG_KEY)).toBeNull();
    expect(window.location.href).toBe('/login');
  });

  it('3) 동시 401 두 건 → refresh 는 한 번만 나간다', async () => {
    let refreshCalls = 0;
    const protectedCallsByUrl: Record<string, number> = {};

    const adapter = makeAdapter((config) => {
      if (config.url === '/api/platform/auth/refresh') {
        refreshCalls += 1;
        return { status: 200, data: { accessToken: 'new-token', tokenType: 'Bearer', expiresIn: 1800, permissions: [], refreshToken: null } };
      }
      const url = config.url ?? '';
      protectedCallsByUrl[url] = (protectedCallsByUrl[url] ?? 0) + 1;
      if (protectedCallsByUrl[url] === 1) return { status: 401 };
      return { status: 200, data: { ok: url } };
    });
    client.defaults.adapter = adapter;
    axios.defaults.adapter = adapter;

    setAccessToken('old-token');
    const [a, b] = await Promise.all([client.get('/a'), client.get('/b')]);

    expect(a.data).toEqual({ ok: '/a' });
    expect(b.data).toEqual({ ok: '/b' });
    expect(refreshCalls).toBe(1);
  });

  it('4) refresh 성공 후 재시도가 또 401 → 두 번째 refresh 를 시도하지 않는다(_retry 가드)', async () => {
    let refreshCalls = 0;
    let protectedCalls = 0;

    const adapter = makeAdapter((config) => {
      if (config.url === '/api/platform/auth/refresh') {
        refreshCalls += 1;
        return { status: 200, data: { accessToken: 'new-token', tokenType: 'Bearer', expiresIn: 1800, permissions: [], refreshToken: null } };
      }
      if (config.url === '/protected') {
        protectedCalls += 1;
        // 재시도(2번째 호출)도 다시 401 — 원 요청 리소스 자체가 여전히 거부되는 경우.
        return { status: 401 };
      }
      throw new Error(`unexpected url: ${config.url}`);
    });
    client.defaults.adapter = adapter;
    axios.defaults.adapter = adapter;

    setAccessToken('old-token');
    await expect(client.get('/protected')).rejects.toBeTruthy();

    expect(protectedCalls).toBe(2);
    // `_retry` 가 이미 true 라 재시도의 401 은 갱신을 다시 몰고 가지 않는다.
    expect(refreshCalls).toBe(1);
  });

  it('5) 로그아웃이 대기 중인 재시도 큐를 취소한다(L3)', async () => {
    let resolveRefresh: (() => void) | undefined;
    let refreshCalls = 0;
    let otherCalls = 0;

    const adapter = makeAdapter(async (config) => {
      if (config.url === '/api/platform/auth/refresh') {
        refreshCalls += 1;
        // refresh 를 의도적으로 지연시켜, 그 사이 두 번째 401 요청이 큐에 쌓이게 한다.
        await new Promise<void>((resolve) => {
          resolveRefresh = resolve;
        });
        return { status: 200, data: { accessToken: 'new-token', tokenType: 'Bearer', expiresIn: 1800, permissions: [], refreshToken: null } };
      }
      if (config.url === '/protected') {
        return { status: 401 };
      }
      if (config.url === '/other') {
        // 큐가 실제로 취소되는지만 가려내려면, 취소되지 않았을 때 이 요청이 '성공'할 수
        // 있어야 한다 — 그래야 reject 의 유일한 원인이 취소 자체가 된다. 스크립트가 없어
        // 매번 'unexpected url' 로 던져지던 이전 버전은 취소 여부와 무관하게 항상 실패해
        // `cancelQueuedRequests()` 를 no-op 으로 바꿔도 테스트가 그대로 초록이었다(공허).
        otherCalls += 1;
        // 1번째 호출은 401 로 큐에 태우고, (취소되지 않았다면) 재시도인 2번째 호출은 성공시킨다.
        if (otherCalls === 1) return { status: 401 };
        return { status: 200, data: { ok: '/other' } };
      }
      throw new Error(`unexpected url: ${config.url}`);
    });
    client.defaults.adapter = adapter;
    axios.defaults.adapter = adapter;

    setAccessToken('old-token');

    const first = client.get('/protected'); // 이 요청이 refresh 를 시작시킨다.
    // 이 시나리오에서 `first` 는 결국 거부된다(재시도도 401). 아래에서 뒤늦게
    // `expect(first).rejects` 로 단언하기 전까지의 마이크로태스크 구간에 핸들러가 없다고
    // vitest/Node 가 "Unhandled Rejection" 으로 오탐하는 것을 막기 위해 즉시 빈 catch 를
    // 붙여 둔다 — 실제 단언은 아래에서 그대로 한다(프라미스는 여러 번 관찰해도 무해하다).
    first.catch(() => {});
    // 첫 요청이 인터셉터를 타고 refresh 를 시작할 때까지 한 틱 기다린다.
    await new Promise((r) => setTimeout(r, 0));
    const second = client.get('/other'); // isRefreshing===true 라 큐에 쌓인다. 정상 성공하는
    // 엔드포인트이므로, 이 요청이 reject 된다면 그 이유는 오직 큐 취소뿐이다.
    // /other 가 자신의 401 응답을 받고 "isRefreshing" 을 확인해 큐에 들어갈 때까지 한 틱
    // 더 기다린다 — 안 그러면 refresh 완료 처리(`finally { isRefreshing = false }` 는
    // 재시도 완료를 기다리지 않고 `return client(originalRequest)` 직후 곧바로 실행된다)가
    // 먼저 끝나 버려 /other 가 큐를 타지 않고 스스로 새 refresh 를 시작해 테스트가 멈춘다
    // (실측: resolveRefresh 를 재사용할 수 없어 두 번째 refresh 가 영원히 pending).
    await new Promise((r) => setTimeout(r, 0));

    // refresh 가 끝나기 전에 로그아웃 — 큐를 비운다.
    cancelQueuedRequests();
    resolveRefresh?.();

    await expect(second).rejects.toBeTruthy();
    // 큐가 reject 됐으므로 두 번째 요청은 새 토큰으로 재시도되지 않는다.
    await expect(first).rejects.toBeTruthy();
    expect(refreshCalls).toBe(1);
  });
});
