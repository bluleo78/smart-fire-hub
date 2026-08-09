/**
 * useOntologyElementMutations 동기화 규약 테스트.
 * 이 훅의 값은 배관이 아니라 동기화 규약이다 — 응답으로 캐시를 직접 갱신하고,
 * 남이 편집한 낌새(schemaVersion 급증)와 낡은 화면(404)을 구분해 처리하는 부분만 겨냥한다.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { AxiosError, AxiosHeaders } from 'axios';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ontologyElementApi } from '@/api/ontology-element';
import type { OntologySchema } from '@/types/ontology';

import { useOntologyElementMutations } from './useOntologyElement';

vi.mock('@/api/ontology-element', () => ({
  ontologyElementApi: {
    patchDomain: vi.fn(),
    addEntityType: vi.fn(),
    updateEntityType: vi.fn(),
    deleteEntityType: vi.fn(),
    addProperty: vi.fn(),
    updateProperty: vi.fn(),
    deleteProperty: vi.fn(),
    addRelation: vi.fn(),
    updateRelation: vi.fn(),
    deleteRelation: vi.fn(),
  },
}));

vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

const ONTOLOGY_ID = 7;
const QUERY_KEY = ['ontology', ONTOLOGY_ID];

function baseSchema(): OntologySchema {
  return {
    domain: '화재 안전',
    schemaVersion: 3,
    entities: [
      { id: 1, type: '건물', description: '', naming: '', resolution: 'exact', properties: [] },
    ],
    relations: [],
  };
}

function makeAxios404() {
  return new AxiosError('Not Found', '404', undefined, undefined, {
    status: 404,
    statusText: 'Not Found',
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
    data: { message: 'not found' },
  });
}

/** QueryClientProvider로 감싼 렌더용 래퍼. 캐시를 미리 시딩해 두고 그 인스턴스를 함께 반환한다. */
function setup() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  queryClient.setQueryData(QUERY_KEY, baseSchema());
  const wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client: queryClient }, children);
  const { result } = renderHook(() => useOntologyElementMutations(ONTOLOGY_ID), { wrapper });
  return { queryClient, result };
}

describe('useOntologyElementMutations — 동기화 규약', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('요소 추가 성공 시 재조회 없이 캐시가 갱신된다', async () => {
    const { queryClient, result } = setup();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    vi.mocked(ontologyElementApi.addEntityType).mockResolvedValue({
      data: {
        schemaVersion: 4,
        entityType: { id: 2, type: '소화기', description: '', naming: '', resolution: 'exact', properties: [] },
      },
    } as never);

    await result.current.addEntityType({ type: '소화기', description: '', naming: '', resolution: 'exact' });

    await waitFor(() => {
      const cached = queryClient.getQueryData<OntologySchema>(QUERY_KEY);
      expect(cached?.entities).toHaveLength(2);
    });

    const cached = queryClient.getQueryData<OntologySchema>(QUERY_KEY);
    expect(cached?.entities.map((e) => e.type)).toEqual(['건물', '소화기']);
    expect(cached?.schemaVersion).toBe(4);
    // 응답으로 직접 갱신했으므로 재조회(invalidateQueries)가 일어나지 않아야 한다.
    expect(invalidateSpy).not.toHaveBeenCalled();
    await waitFor(() => expect(result.current.saveState).toBe('saved'));
  });

  it('응답 schemaVersion이 기대치보다 크면 전체를 재조회한다', async () => {
    const { queryClient, result } = setup();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    // 로컬 캐시는 schemaVersion=3이므로 기대치는 4. 서버가 6을 돌려주면(남이 2건 더 편집)
    // 낙관적 직접 갱신만으로는 부족하므로 전체 재조회로 화면을 맞춰야 한다.
    vi.mocked(ontologyElementApi.addEntityType).mockResolvedValue({
      data: {
        schemaVersion: 6,
        entityType: { id: 2, type: '소화기', description: '', naming: '', resolution: 'exact', properties: [] },
      },
    } as never);

    await result.current.addEntityType({ type: '소화기', description: '', naming: '', resolution: 'exact' });

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith(expect.objectContaining({ queryKey: QUERY_KEY }));
    });
  });

  it('404는 사용자 입력 오류가 아니라 재동기화로 처리된다', async () => {
    const { toast } = await import('sonner');
    const { queryClient, result } = setup();
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');

    vi.mocked(ontologyElementApi.updateEntityType).mockRejectedValue(makeAxios404());

    await result.current.updateEntityType(1, { type: '리네임됨' });

    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith(expect.objectContaining({ queryKey: QUERY_KEY }));
    });

    // 일반 4xx(handleApiError 경로, fallback '저장에 실패했습니다.')와는 다른 재동기화 전용 문구여야
    // 한다. /입력/ 부재만 보면 handleApiError의 fallback도 통과해버려 404 분기 삭제를 못 잡는다 —
    // 문구 전체를 고정해 즉시·명확하게 실패하도록 한다.
    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith('이미 삭제된 요소입니다 — 최신 상태로 새로고침했습니다.');
    await waitFor(() => expect(result.current.saveState).toBe('error'));
    // saveState==='error'라고 해서 재시도가 가능한 건 아니다(Task 4 리뷰 I-2) — 404는 대상이 이미
    // 없어 재시도해도 소용없으므로 canRetry는 false로 남아야 SaveStatusChip이 dead 버튼을 그리지 않는다.
    expect(result.current.canRetry).toBe(false);
  });

  it('겹친 뮤테이션 중 하나가 실패하면, 나머지가 나중에 성공해도 error 상태가 유지된다', async () => {
    const { result } = setup();

    let resolveSuccess!: (value: { data: unknown }) => void;
    vi.mocked(ontologyElementApi.addEntityType).mockReturnValue(
      new Promise((resolve) => {
        resolveSuccess = resolve;
      }) as never,
    );
    vi.mocked(ontologyElementApi.addRelation).mockRejectedValue(new Error('boom'));

    const successPromise = result.current.addEntityType({
      type: '소화기',
      description: '',
      naming: '',
      resolution: 'exact',
    });
    const failurePromise = result.current.addRelation({
      subjectTypeId: 1,
      relation: '설치됨',
      objectTypeId: 1,
      description: '',
    });

    // 실패가 먼저 반영된다.
    await failurePromise;
    await waitFor(() => expect(result.current.saveState).toBe('error'));

    // 아직 진행 중이던 다른 요청이 뒤늦게 성공해도 'saved'로 되돌아가면 안 된다.
    // act()로 감싸 성공 응답 처리의 상태 갱신까지 완전히 flush한 뒤 읽는다 — 그렇지 않으면
    // "아직 반영 전(이전 렌더의 'error')"을 우연히 관측해 회귀를 놓칠 수 있다.
    await act(async () => {
      resolveSuccess({
        data: {
          schemaVersion: 4,
          entityType: { id: 2, type: '소화기', description: '', naming: '', resolution: 'exact', properties: [] },
        },
      });
      await successPromise;
    });

    expect(result.current.saveState).toBe('error');
  });

  it('엔티티 타입 삭제 시 FK CASCADE로 함께 사라진 관계도 캐시에서 제거된다', async () => {
    const { queryClient, result } = setup();
    // 엔티티 타입 2개(건물=1, 소화기=2)와 그 둘을 잇는 관계(id=10)를 시딩한다.
    queryClient.setQueryData(QUERY_KEY, {
      ...baseSchema(),
      entities: [
        { id: 1, type: '건물', description: '', naming: '', resolution: 'exact', properties: [] },
        { id: 2, type: '소화기', description: '', naming: '', resolution: 'exact', properties: [] },
      ],
      relations: [{ id: 10, subject: '건물', relation: '보유', object: '소화기', description: '' }],
    });

    // 소화기(id=2) 삭제 → FK CASCADE로 관계(id=10)도 서버에서 함께 삭제되어 deletedRelationIds에 담겨온다.
    vi.mocked(ontologyElementApi.deleteEntityType).mockResolvedValue({
      data: { schemaVersion: 4, deletedRelationIds: [10] },
    } as never);

    await result.current.deleteEntityType(2);

    await waitFor(() => {
      const cached = queryClient.getQueryData<OntologySchema>(QUERY_KEY);
      expect(cached?.entities).toHaveLength(1);
    });

    const cached = queryClient.getQueryData<OntologySchema>(QUERY_KEY);
    expect(cached?.entities.map((e) => e.id)).toEqual([1]);
    // 삭제된 엔티티 타입뿐 아니라, 그로 인해 CASCADE로 사라진 관계도 캐시에서 빠져야 한다 —
    // 그렇지 않으면 화면이 이미 없는 노드를 가리키는 유령 엣지를 계속 그린다.
    expect(cached?.relations).toHaveLength(0);
  });

  it('엔티티 타입을 리네임하면 relations[].subject/object도 새 이름으로 갱신된다(Task 4 리뷰 C-2)', async () => {
    const { queryClient, result } = setup();
    // 건물(id=1)이 주어·목적어 양쪽으로 등장하는 관계 2개 + 무관한 관계 1개를 시딩한다.
    // SchemaGraph는 relations[].subject/object를 그대로 cytoscape 노드 id로 쓰므로(entities[].type
    // 참조), 여기서 리네임 후에도 옛 이름이 남아 있으면 존재하지 않는 노드를 잇는 엣지가 되어
    // 캔버스가 크래시한다(#Cannot create edge... 실제 프로덕션 크래시로 확인됨).
    queryClient.setQueryData(QUERY_KEY, {
      ...baseSchema(),
      entities: [
        { id: 1, type: '건물', description: '', naming: '', resolution: 'exact', properties: [] },
        { id: 2, type: '소화기', description: '', naming: '', resolution: 'exact', properties: [] },
        { id: 3, type: '설비', description: '', naming: '', resolution: 'exact', properties: [] },
      ],
      relations: [
        { id: 10, subject: '건물', relation: '보유', object: '소화기', description: '' },
        { id: 11, subject: '소화기', relation: '설치됨', object: '건물', description: '' },
        { id: 12, subject: '소화기', relation: '관련설비', object: '설비', description: '' }, // 건물과 무관
      ],
    });

    vi.mocked(ontologyElementApi.updateEntityType).mockResolvedValue({
      data: {
        schemaVersion: 4,
        entityType: { id: 1, type: '신축건물', description: '', naming: '', resolution: 'exact', properties: [] },
      },
    } as never);

    await result.current.updateEntityType(1, { type: '신축건물' });

    await waitFor(() => {
      const cached = queryClient.getQueryData<OntologySchema>(QUERY_KEY);
      expect(cached?.entities.find((e) => e.id === 1)?.type).toBe('신축건물');
    });

    const cached = queryClient.getQueryData<OntologySchema>(QUERY_KEY);
    const bySubject = cached?.relations.find((r) => r.id === 10);
    const byObject = cached?.relations.find((r) => r.id === 11);
    const unrelated = cached?.relations.find((r) => r.id === 12);
    expect(bySubject?.subject).toBe('신축건물');
    expect(byObject?.object).toBe('신축건물');
    // 옛 이름과 무관한 관계는 건드리지 않는다(과잉 치환 방지 확인).
    expect(unrelated?.subject).toBe('소화기');
    expect(unrelated?.object).toBe('설비');
  });

  it('타입명이 바뀌지 않은 업데이트(설명 등)는 relations를 그대로 둔다(불필요한 배열 재생성 없음)', async () => {
    const { queryClient, result } = setup();
    queryClient.setQueryData(QUERY_KEY, {
      ...baseSchema(),
      relations: [{ id: 10, subject: '건물', relation: '보유', object: '소화기', description: '' }],
    });
    const before = queryClient.getQueryData<OntologySchema>(QUERY_KEY)?.relations;

    vi.mocked(ontologyElementApi.updateEntityType).mockResolvedValue({
      data: {
        schemaVersion: 4,
        entityType: { id: 1, type: '건물', description: '새 설명', naming: '', resolution: 'exact', properties: [] },
      },
    } as never);

    await result.current.updateEntityType(1, { description: '새 설명' });

    await waitFor(() => {
      const cached = queryClient.getQueryData<OntologySchema>(QUERY_KEY);
      expect(cached?.entities.find((e) => e.id === 1)?.description).toBe('새 설명');
    });

    const after = queryClient.getQueryData<OntologySchema>(QUERY_KEY)?.relations;
    expect(after).toBe(before); // 참조 identity까지 그대로 — 리네임이 아니면 relations 배열을 새로 만들지 않는다.
  });

  it('재시도(retry): 실패 후 재시도가 같은 요청을 다시 보내고, 성공하면 saved로 회복된다', async () => {
    const { queryClient, result } = setup();

    vi.mocked(ontologyElementApi.updateEntityType)
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({
        data: { schemaVersion: 4, entityType: { id: 1, type: '리네임됨', description: '', naming: '', resolution: 'exact', properties: [] } },
      } as never);

    await result.current.updateEntityType(1, { type: '리네임됨' });
    await waitFor(() => expect(result.current.saveState).toBe('error'));
    expect(result.current.canRetry).toBe(true);

    await act(async () => {
      result.current.retry();
    });

    await waitFor(() => expect(result.current.saveState).toBe('saved'));
    expect(ontologyElementApi.updateEntityType).toHaveBeenCalledTimes(2);
    expect(ontologyElementApi.updateEntityType).toHaveBeenNthCalledWith(2, ONTOLOGY_ID, 1, { type: '리네임됨' });
    const cached = queryClient.getQueryData<OntologySchema>(QUERY_KEY);
    expect(cached?.entities.find((e) => e.id === 1)?.type).toBe('리네임됨');
    expect(result.current.canRetry).toBe(false);
  });

  it('재시도(retry): 재시도도 실패하면 다시 error로 남고, 재재시도가 또 가능하다(재무장)', async () => {
    const { result } = setup();

    vi.mocked(ontologyElementApi.updateEntityType)
      .mockRejectedValueOnce(new Error('boom'))
      .mockRejectedValueOnce(new Error('boom again'))
      .mockResolvedValueOnce({
        data: { schemaVersion: 4, entityType: { id: 1, type: '리네임됨', description: '', naming: '', resolution: 'exact', properties: [] } },
      } as never);

    await result.current.updateEntityType(1, { type: '리네임됨' });
    await waitFor(() => expect(result.current.saveState).toBe('error'));

    // 첫 재시도도 실패 — 여전히 error여야 하고, 재시도 버튼이 사라지지 않아야(재무장) 한다.
    await act(async () => {
      result.current.retry();
    });
    await waitFor(() => expect(ontologyElementApi.updateEntityType).toHaveBeenCalledTimes(2));
    expect(result.current.saveState).toBe('error');
    expect(result.current.canRetry).toBe(true);

    // 두 번째 재시도는 성공 — saved로 회복된다.
    await act(async () => {
      result.current.retry();
    });
    await waitFor(() => expect(result.current.saveState).toBe('saved'));
    expect(ontologyElementApi.updateEntityType).toHaveBeenCalledTimes(3);
    expect(result.current.canRetry).toBe(false);
  });

  it('재시도(retry): 404 실패는 재시도 대상으로 등록되지 않는다(재시도해도 재요청이 없다)', async () => {
    const { result } = setup();

    vi.mocked(ontologyElementApi.updateEntityType).mockRejectedValue(makeAxios404());

    await result.current.updateEntityType(1, { type: '리네임됨' });
    await waitFor(() => expect(result.current.saveState).toBe('error'));
    expect(result.current.canRetry).toBe(false);

    await act(async () => {
      result.current.retry();
    });

    // lastFailedRef가 비어 있으므로 retry()가 아무 것도 하지 않는다 — 호출 횟수가 늘지 않는다.
    expect(ontologyElementApi.updateEntityType).toHaveBeenCalledTimes(1);
  });
});
