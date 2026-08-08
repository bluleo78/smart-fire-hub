import type { QueryClient } from '@tanstack/react-query';
import { useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';

import { ontologyElementApi } from '@/api/ontology-element';
import { handleApiError } from '@/lib/api-error';
import type {
  CreateEntityTypeRequest,
  CreatePropertyRequest,
  CreateRelationRequest,
  OntologySchema,
  PatchOntologyRequest,
  UpdateEntityTypeRequest,
  UpdatePropertyRequest,
  UpdateRelationRequest,
} from '@/types/ontology';

export type SaveState = 'idle' | 'saving' | 'saved' | 'error';

/**
 * 뮤테이션 응답 공통 처리.
 * ① updater로 캐시를 직접 갱신한다 — 매번 전체 재조회하면 자동 저장 특성상 타이핑 중
 *    화면이 자주 깜빡인다.
 * ② 응답 schemaVersion이 "직전 값 + 1"(내가 유발한 증가분)보다 크면 그 사이 남이 편집한
 *    것이므로 조용히 전체 재조회한다. 내 편집 자체는 이미 성공했으므로 에러를 띄우지 않는다.
 * 캐시가 비어 있으면(다른 화면에서 아직 조회 전 등) 갱신할 대상이 없으므로 아무 것도 하지 않는다.
 */
function commitMutation(
  queryClient: QueryClient,
  queryKey: readonly unknown[],
  schemaVersion: number,
  updater: (schema: OntologySchema) => OntologySchema,
) {
  const prev = queryClient.getQueryData<OntologySchema>(queryKey);
  if (!prev) return;
  const expected = prev.schemaVersion + 1;
  // updater는 entities/relations/domain 등 요소별 변경만 신경 쓰면 된다 — schemaVersion 갱신은
  // 여기서 일괄 적용해, 훅마다 반복해서 실어 보내다 깜빡 빠뜨리는 실수를 막는다.
  queryClient.setQueryData<OntologySchema>(queryKey, (old) =>
    old ? { ...updater(old), schemaVersion } : old,
  );
  if (schemaVersion > expected) {
    queryClient.invalidateQueries({ queryKey });
  }
}

/**
 * 지식 모델 요소 단위 편집 뮤테이션 훅(S2). 전체 문서를 왕복시키던 PUT 기반 모달을 대체하며,
 * 요소마다 독립적으로 저장한다. saveState는 진행 중인 뮤테이션 개수를 세어 계산한다 —
 * 여러 요청이 겹칠 수 있는데(자동 저장) 하나가 끝났다고 바로 'saved'로 떨어뜨리면
 * 아직 저장 중인 다른 요청이 가려지기 때문이다.
 */
export function useOntologyElementMutations(ontologyId: number) {
  const queryClient = useQueryClient();
  // 매 렌더 새 배열이면 이 값을 deps로 쓰는 아래 콜백 전부가 매번 재생성된다 —
  // ontologyId가 바뀔 때만 새로 만든다.
  const queryKey = useMemo(() => ['ontology', ontologyId] as const, [ontologyId]);

  const pendingRef = useRef(0);
  // 겹친 요청 중 하나라도 실패하면 끝까지 'error'를 유지해야 한다 — 그 뒤에 다른 요청이
  // 성공해서 pending이 0으로 떨어졌다고 곧장 'saved'로 덮으면 실패가 조용히 사라진다.
  const hadErrorRef = useRef(false);
  // 마지막으로 실패한 뮤테이션을 다시 실행하는 클로저(재시도 어포던스, Task 3 리뷰 IMP-5).
  // hadErrorRef와 같은 지점에서만 리셋한다 — begin()이 pendingRef===0일 때 hadErrorRef를 지우는
  // 바로 그 순간에 함께 지워야 한다.
  const lastFailedRef = useRef<(() => void) | null>(null);
  // 404는 saveState를 'error'로 만들지만 lastFailedRef는 채우지 않는다(대상이 이미 삭제돼
  // 재시도해도 소용없으므로) — 즉 "saveState==='error' ⟺ 재시도 가능"은 **성립하지 않는다**
  // (Task 4 리뷰 I-2, 이전 주석이 이걸 성립한다고 잘못 단언했었다). SaveStatusChip은 saveState만으로
  // 재시도 버튼 노출 여부를 판단할 수 없으므로, lastFailedRef가 채워졌는지를 렌더에 참여하는
  // state로 별도로 노출한다 — lastFailedRef 자체는 ref라 값이 바뀌어도 리렌더를 유발하지 않는다.
  const [canRetry, setCanRetry] = useState(false);
  const [saveState, setSaveState] = useState<SaveState>('idle');
  // runMutation 자기 자신을 재시도 클로저 안에서 이름으로 참조하면(recursion) lint가
  // "선언 전 접근"으로 막는다(react-hooks 플러그인이 TDZ를 정적으로 못 검증) — 매 렌더 최신
  // runMutation을 담아두는 ref로 우회한다. ref 갱신은 렌더마다 일어나는 대입일 뿐이라 안전하다.
  const runMutationRef = useRef<
    (<T>(fn: () => Promise<{ data: T }>, onSuccess: (data: T) => void) => Promise<T | undefined>) | null
  >(null);

  const begin = useCallback(() => {
    if (pendingRef.current === 0) {
      hadErrorRef.current = false;
      lastFailedRef.current = null;
      setCanRetry(false);
    }
    pendingRef.current += 1;
    setSaveState('saving');
  }, []);

  // isError=true면 즉시 'error'로 표시한다. 아직 다른 요청이 진행 중이어도 사용자에게
  // 실패를 숨기지 않는다 — 자동 저장은 침묵하는 실패가 가장 위험하다.
  const finish = useCallback((isError: boolean) => {
    pendingRef.current = Math.max(0, pendingRef.current - 1);
    if (isError) {
      hadErrorRef.current = true;
      setSaveState('error');
      return;
    }
    if (pendingRef.current === 0) {
      setSaveState(hadErrorRef.current ? 'error' : 'saved');
    }
  }, []);

  // 요소 뮤테이션 공통 실행기. 404는 "낡은 화면이 이미 삭제된 요소를 지목"한 경우이므로
  // 사용자 입력 오류로 다루지 않는다 — 전용 문구 + 전체 재조회로 화면을 최신화한다.
  // 전역 인터셉터(client.ts)에 넣지 않는 이유: 다른 도메인의 404까지 재조회를 유발하게 된다.
  const runMutation = useCallback(
    async <T,>(fn: () => Promise<{ data: T }>, onSuccess: (data: T) => void): Promise<T | undefined> => {
      begin();
      try {
        const { data } = await fn();
        onSuccess(data);
        finish(false);
        return data;
      } catch (error) {
        // 404는 "낡은 화면" 신호일 뿐 재시도로 해결되지 않는다(대상 자체가 이미 없음) — lastFailedRef를
        // 채우지 않는다. 그 외 실패(네트워크/5xx 등)만 같은 요청을 다시 시도할 가치가 있다.
        if (axios.isAxiosError(error) && error.response?.status === 404) {
          toast.error('이미 삭제된 요소입니다 — 최신 상태로 새로고침했습니다.');
          queryClient.invalidateQueries({ queryKey });
        } else {
          handleApiError(error, '저장에 실패했습니다.');
          lastFailedRef.current = () => {
            void runMutationRef.current?.(fn, onSuccess);
          };
          setCanRetry(true);
        }
        finish(true);
        return undefined;
      }
    },
    [begin, finish, queryClient, queryKey],
  );
  // 렌더 중 ref를 직접 대입하면 안 되므로(react-hooks 규칙) 커밋 이후 effect에서 동기화한다 —
  // runMutation은 통상 안정적이라 재시도 시점에는 이미 최신 값으로 갱신돼 있다.
  useEffect(() => {
    runMutationRef.current = runMutation;
  }, [runMutation]);

  // 마지막 실패 뮤테이션 재시도. canRetry===true일 때만 의미 있게 동작한다(위 canRetry 주석 참고 —
  // saveState==='error'만으로는 404 실패와 구분할 수 없다). 참조 identity를 안정적으로 유지해
  // (deps 없음) SaveStatusChip에 prop으로 내려가도 불필요한 재생성이 없다.
  const retry = useCallback(() => {
    lastFailedRef.current?.();
  }, []);

  const patchDomain = useCallback(
    (req: PatchOntologyRequest) =>
      runMutation(
        () => ontologyElementApi.patchDomain(ontologyId, req),
        (data) => {
          commitMutation(queryClient, queryKey, data.schemaVersion, (schema) => ({
            ...schema,
            domain: req.domain,
          }));
          // ['ontology', id] 스키마 캐시만 갱신하면 ['ontologies'] 목록 캐시(OntologySelect/
          // OntologyManageDialog가 읽는 domain)는 낡은 채로 남는다(NEW-2, Task 6 리뷰 라운드2) —
          // 도메인명을 고친 직후 같은 툴바 안에서 아웃라인은 새 이름을, 선택기는 옛 이름을 동시에
          // 보여주는 어긋남이 생긴다. useOntology.ts의 다른 뮤테이션들이 이미 이 키를 무효화하는
          // 관례를 따른다. entityCount 등 요소 CRUD로 인한 나머지 필드의 staleness는 Task 1부터
          // 있던 별개의 기존 문제라 여기서 함께 고치지 않는다 — 도메인명은 사용자가 방금 타이핑한
          // 문자열이라 어긋남이 유독 눈에 띄어 우선 처리한다.
          queryClient.invalidateQueries({ queryKey: ['ontologies'] });
        },
      ),
    [ontologyId, queryClient, queryKey, runMutation],
  );

  const addEntityType = useCallback(
    (req: CreateEntityTypeRequest) =>
      runMutation(
        () => ontologyElementApi.addEntityType(ontologyId, req),
        (data) =>
          commitMutation(queryClient, queryKey, data.schemaVersion, (schema) => ({
            ...schema,
            entities: [...schema.entities, data.entityType],
          })),
      ),
    [ontologyId, queryClient, queryKey, runMutation],
  );

  const updateEntityType = useCallback(
    (etId: number, req: UpdateEntityTypeRequest) =>
      runMutation(
        () => ontologyElementApi.updateEntityType(ontologyId, etId, req),
        (data) =>
          commitMutation(queryClient, queryKey, data.schemaVersion, (schema) => {
            // relations[].subject/object는 타입 "이름"을 사람이 읽는 표시용으로 들고 있고,
            // SchemaGraph는 그 이름을 그대로 cytoscape 노드/엣지 id로 쓴다(entityId 같은 숫자 id가
            // 아니다). 타입을 리네임하면 entities[]는 여기서 새 이름으로 바뀌는데 relations[]를
            // 그대로 두면 이름이 어긋나 "존재하지 않는 노드를 잇는 엣지"가 되어 캔버스가 크래시한다
            // (Task 4 리뷰 C-2). 리네임 전 이름은 이 업데이터만 교체 전/후 스키마를 동시에 보고 있어
            // 여기서 읽어야 한다 — 호출부(EntityInspector)에서 넘겨받으면 낙관적 갱신이 겹칠 때
            // 어느 시점의 이름인지 보장할 수 없다.
            const prevEntity = schema.entities.find((e) => e.id === etId);
            const renamed = prevEntity != null && prevEntity.type !== data.entityType.type;
            return {
              ...schema,
              entities: schema.entities.map((e) => (e.id === etId ? data.entityType : e)),
              relations: renamed
                ? schema.relations.map((r) => ({
                    ...r,
                    subject: r.subject === prevEntity.type ? data.entityType.type : r.subject,
                    object: r.object === prevEntity.type ? data.entityType.type : r.object,
                  }))
                : schema.relations,
            };
          }),
      ),
    [ontologyId, queryClient, queryKey, runMutation],
  );

  const deleteEntityType = useCallback(
    (etId: number) =>
      runMutation(
        () => ontologyElementApi.deleteEntityType(ontologyId, etId),
        (data) =>
          commitMutation(queryClient, queryKey, data.schemaVersion, (schema) => ({
            ...schema,
            entities: schema.entities.filter((e) => e.id !== etId),
            // FK CASCADE로 함께 사라진 관계들 — 서버가 삭제 직전에 조회해 담아 준다.
            relations: schema.relations.filter((r) => !data.deletedRelationIds.includes(r.id as number)),
          })),
      ),
    [ontologyId, queryClient, queryKey, runMutation],
  );

  const addProperty = useCallback(
    (etId: number, req: CreatePropertyRequest) =>
      runMutation(
        () => ontologyElementApi.addProperty(ontologyId, etId, req),
        (data) =>
          commitMutation(queryClient, queryKey, data.schemaVersion, (schema) => ({
            ...schema,
            entities: schema.entities.map((e) =>
              e.id === etId ? { ...e, properties: [...e.properties, data.property] } : e,
            ),
          })),
      ),
    [ontologyId, queryClient, queryKey, runMutation],
  );

  const updateProperty = useCallback(
    (etId: number, propId: number, req: UpdatePropertyRequest) =>
      runMutation(
        () => ontologyElementApi.updateProperty(ontologyId, etId, propId, req),
        (data) =>
          commitMutation(queryClient, queryKey, data.schemaVersion, (schema) => ({
            ...schema,
            entities: schema.entities.map((e) =>
              e.id === etId
                ? { ...e, properties: e.properties.map((p) => (p.id === propId ? data.property : p)) }
                : e,
            ),
          })),
      ),
    [ontologyId, queryClient, queryKey, runMutation],
  );

  const deleteProperty = useCallback(
    (etId: number, propId: number) =>
      runMutation(
        () => ontologyElementApi.deleteProperty(ontologyId, etId, propId),
        (data) =>
          commitMutation(queryClient, queryKey, data.schemaVersion, (schema) => ({
            ...schema,
            entities: schema.entities.map((e) =>
              e.id === etId ? { ...e, properties: e.properties.filter((p) => p.id !== propId) } : e,
            ),
          })),
      ),
    [ontologyId, queryClient, queryKey, runMutation],
  );

  const addRelation = useCallback(
    (req: CreateRelationRequest) =>
      runMutation(
        () => ontologyElementApi.addRelation(ontologyId, req),
        (data) =>
          commitMutation(queryClient, queryKey, data.schemaVersion, (schema) => ({
            ...schema,
            relations: [...schema.relations, data.relation],
          })),
      ),
    [ontologyId, queryClient, queryKey, runMutation],
  );

  const updateRelation = useCallback(
    (relId: number, req: UpdateRelationRequest) =>
      runMutation(
        () => ontologyElementApi.updateRelation(ontologyId, relId, req),
        (data) =>
          commitMutation(queryClient, queryKey, data.schemaVersion, (schema) => ({
            ...schema,
            relations: schema.relations.map((r) => (r.id === relId ? data.relation : r)),
          })),
      ),
    [ontologyId, queryClient, queryKey, runMutation],
  );

  const deleteRelation = useCallback(
    (relId: number) =>
      runMutation(
        () => ontologyElementApi.deleteRelation(ontologyId, relId),
        (data) =>
          commitMutation(queryClient, queryKey, data.schemaVersion, (schema) => ({
            ...schema,
            relations: schema.relations.filter((r) => r.id !== relId),
          })),
      ),
    [ontologyId, queryClient, queryKey, runMutation],
  );

  return {
    patchDomain,
    addEntityType,
    updateEntityType,
    deleteEntityType,
    addProperty,
    updateProperty,
    deleteProperty,
    addRelation,
    updateRelation,
    deleteRelation,
    saveState,
    retry,
    canRetry,
  };
}

// EntityInspector/RelationInspector(Task 4/5)가 이 훅을 직접 호출하지 않고 OntologyPage의 단일
// 인스턴스를 prop으로 받기 위한 타입(Task 4 리뷰 IMP-1) — 여기서 다시 호출하면 saveState가
// 인스턴스마다 갈라져 툴바 칩이 조용히 멈춘다.
export type OntologyElementMutations = ReturnType<typeof useOntologyElementMutations>;
