// WD-429: 온톨로지에 없는 엔티티 타입의 검수 항목(엔티티 적재·동의어 병합) 승인은 일반 Error(→ 502 「잠시 후 다시 시도」)가 아니라
// 사유가 담긴 OntologyConformanceError(→ 409)로 거절돼야 한다. 거절은 그래프를 건드리기 전에 일어난다.
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { VerifiedOntologyId } from './verified-ontology-id.js';

const runMock = vi.fn();
const closeMock = vi.fn().mockResolvedValue(undefined);
vi.mock('./neo4j-client.js', () => ({ getSession: () => ({ run: runMock, close: closeMock }) }));

import { addEntity } from './entity-add.js';
import { assertEntityTypeDefined, GraphMutationRejectedError, OntologyConformanceError } from './graph-mutation-guard.js';
import { CORE_ONTOLOGY } from './ontology.js';
import { mergeEntities } from './synonym-merge.js';

const ONTOLOGY_ID = 9 as VerifiedOntologyId;

describe('온톨로지에 없는 엔티티 타입 거절 (WD-429)', () => {
  beforeEach(() => {
    runMock.mockReset();
    closeMock.mockClear();
  });

  it('assertEntityTypeDefined: 정의된 타입은 통과한다', () => {
    expect(() => assertEntityTypeDefined(CORE_ONTOLOGY, 'Building')).not.toThrow();
  });

  it('assertEntityTypeDefined: 없는 타입은 이름이 든 한국어 사유의 OntologyConformanceError 다', () => {
    const err = (() => {
      try {
        assertEntityTypeDefined(CORE_ONTOLOGY, '없는타입');
      } catch (e) {
        return e;
      }
    })();
    expect(err).toBeInstanceOf(OntologyConformanceError);
    // 라우트가 409 로 매핑하는 상위 타입이어야 한다 — 일반 Error 면 502 로 떨어진다.
    expect(err).toBeInstanceOf(GraphMutationRejectedError);
    expect((err as Error).message).toContain("'없는타입'");
    expect((err as Error).message).not.toContain('잠시 후');
  });

  it('addEntity: 없는 타입이면 그래프를 건드리기 전에 409 계열로 거절한다', async () => {
    await expect(
      addEntity(CORE_ONTOLOGY, ONTOLOGY_ID, { entityType: '없는타입', name: 'x', sourceChunkIds: [], relations: [] }),
    ).rejects.toThrow(OntologyConformanceError);
    expect(runMock).not.toHaveBeenCalled();
  });

  it('mergeEntities: 대기 중 타입 이름이 바뀌어 사라졌다면 그래프를 건드리기 전에 409 계열로 거절한다', async () => {
    await expect(mergeEntities(CORE_ONTOLOGY, ONTOLOGY_ID, 'BuildingQA', 'A', 'B')).rejects.toThrow(OntologyConformanceError);
    expect(runMock).not.toHaveBeenCalled();
  });
});
