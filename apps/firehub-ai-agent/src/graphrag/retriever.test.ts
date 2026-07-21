// expandSubgraph 순수 함수 유닛 테스트 — Neo4j 없이 fetchNeighbors를 목으로 주입해
// 앵커타입 인지 허브 감쇠(anchor-aware hub damping) BFS 로직만 검증한다.
import { describe, it, expect } from 'vitest';
import {
  expandSubgraph, FetchNeighbors, SubgraphNode, SeedNode,
} from './retriever.js';

describe('expandSubgraph', () => {
  // 그래프: A(시드, Incident, deg 3) -REL- near1(Cause, deg 2), A -REL- HUB(Regulation, deg 12),
  // HUB -REL- far1/far2(허브를 거쳐야만 도달).
  const seedA: SeedNode = { key: 'A', type: 'Incident', name: 'A', degree: 3 };
  const near1: SubgraphNode = { key: 'near1', type: 'Cause', name: 'near1' };
  const hub: SubgraphNode = { key: 'HUB', type: 'Regulation', name: 'HUB' };
  const far1: SubgraphNode = { key: 'far1', type: 'Incident', name: 'far1' };
  const far2: SubgraphNode = { key: 'far2', type: 'Incident', name: 'far2' };

  const anchorTypes = new Set(['Incident']);

  const fetchNeighbors: FetchNeighbors = async (keys: string[]) => {
    const rows: Array<{ fromKey: string; relType: string; neighbor: SubgraphNode; neighborDegree: number }> = [];
    if (keys.includes('A')) {
      rows.push({ fromKey: 'A', relType: 'CAUSED_BY', neighbor: near1, neighborDegree: 2 });
      rows.push({ fromKey: 'A', relType: 'VIOLATED', neighbor: hub, neighborDegree: 12 });
    }
    if (keys.includes('HUB')) {
      rows.push({ fromKey: 'HUB', relType: 'VIOLATED', neighbor: far1, neighborDegree: 1 });
      rows.push({ fromKey: 'HUB', relType: 'VIOLATED', neighbor: far2, neighborDegree: 1 });
    }
    return rows;
  };

  it('허브(degree>=hubDegree, 비-앵커타입)는 포함되지만 그 너머로는 확장하지 않는다', async () => {
    const { nodes, relations } = await expandSubgraph(
      ['A'], [seedA], fetchNeighbors, { maxHops: 2, hubDegree: 10, maxNodes: 40, anchorTypes },
    );
    const keys = nodes.map((n) => n.key).sort();
    expect(keys).toEqual(['A', 'HUB', 'near1']);
    expect(keys).not.toContain('far1');
    expect(keys).not.toContain('far2');
    // 관계도 포함된 노드 사이의 것만 남는다.
    expect(relations.every((r) => keys.includes(r.subject) && keys.includes(r.object))).toBe(true);
    expect(relations.some((r) => r.type === 'VIOLATED' && r.object === 'HUB')).toBe(true);
  });

  it('maxNodes 정원에 도달하면 더 이상 새 노드를 추가하지 않는다', async () => {
    // maxNodes=2 → 시드 A 하나 있는 상태에서 near1까지만 들어가고 HUB는 정원 초과로 제외.
    const { nodes } = await expandSubgraph(
      ['A'], [seedA], fetchNeighbors, { maxHops: 2, hubDegree: 10, maxNodes: 2, anchorTypes },
    );
    expect(nodes.length).toBeLessThanOrEqual(2);
    expect(nodes.map((n) => n.key)).toContain('A');
  });

  it('앵커타입 시드는 degree와 무관하게 항상 확장된다', async () => {
    // 시드 A(Incident=앵커타입) 자체가 고차수(허브급)여도 hubDegree를 낮게 잡아도 확장되어야 한다.
    const hiDegreeSeed: SeedNode = { key: 'A', type: 'Incident', name: 'A', degree: 99 };
    const fetchNeighborsSeedHub: FetchNeighbors = async (keys: string[]) => {
      if (keys.includes('A')) return [{ fromKey: 'A', relType: 'CAUSED_BY', neighbor: near1, neighborDegree: 2 }];
      return [];
    };
    const { nodes } = await expandSubgraph(
      ['A'], [hiDegreeSeed], fetchNeighborsSeedHub, { maxHops: 1, hubDegree: 1, maxNodes: 40, anchorTypes },
    );
    // hubDegree=1이면 A의 degree(99)는 종단 취급 기준을 훌쩍 넘지만, Incident는 앵커타입이므로 확장된다.
    expect(nodes.map((n) => n.key)).toContain('near1');
  });

  it('비-앵커타입 고차수 시드는 확장하지 않는다 — 공유 Equipment/Regulation 브릿징 차단 회귀', async () => {
    // 시드가 Equipment이고 degree(6)가 hubDegree(4) 이상이면, 시드로 포함은 되지만
    // 그 너머(다른 건물/사건)로는 확장되지 않아야 한다 — 이게 6문서 e2e에서 발견된 결함의 핵심.
    const sharedEquipmentSeed: SeedNode = { key: 'EQ', type: 'Equipment', name: '스프링클러 설비', degree: 6 };
    const unrelatedBuilding: SubgraphNode = { key: 'B2', type: 'Building', name: '무관 건물' };
    const fetchNeighborsEq: FetchNeighbors = async (keys: string[]) => {
      if (keys.includes('EQ')) {
        return [{ fromKey: 'EQ', relType: 'HAS_EQUIPMENT', neighbor: unrelatedBuilding, neighborDegree: 2 }];
      }
      return [];
    };
    const { nodes } = await expandSubgraph(
      ['EQ'], [sharedEquipmentSeed], fetchNeighborsEq, { maxHops: 2, hubDegree: 4, maxNodes: 40, anchorTypes },
    );
    const keys = nodes.map((n) => n.key);
    expect(keys).toEqual(['EQ']); // 시드 자체만 포함, 그 너머 건물은 확장되지 않아 제외됨.
    expect(keys).not.toContain('B2');
  });

  it('비-앵커타입이라도 저차수 시드(hubDegree 미만)는 정상 확장된다', async () => {
    const lowDegreeBuildingSeed: SeedNode = { key: 'B1', type: 'Building', name: '중앙로 상가', degree: 2 };
    const incident: SubgraphNode = { key: 'I1', type: 'Incident', name: '2026-001' };
    const fetchNeighborsB: FetchNeighbors = async (keys: string[]) => {
      if (keys.includes('B1')) return [{ fromKey: 'B1', relType: 'OCCURRED_AT', neighbor: incident, neighborDegree: 3 }];
      return [];
    };
    const { nodes } = await expandSubgraph(
      ['B1'], [lowDegreeBuildingSeed], fetchNeighborsB, { maxHops: 2, hubDegree: 4, maxNodes: 40, anchorTypes },
    );
    expect(nodes.map((n) => n.key)).toContain('I1');
  });
});
