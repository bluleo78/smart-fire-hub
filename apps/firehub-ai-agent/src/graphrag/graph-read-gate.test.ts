// 그래프 읽기 게이트(WD-28) — 런타임 헬퍼와, "판정을 빠뜨리면 컴파일되지 않는다"는 타입 계약을 함께 못박는다.
// 타입 계약 부분은 vitest 가 아니라 `pnpm typecheck`(tsc)가 검증한다: @ts-expect-error 아래 줄이 컴파일되면
// (=브랜드가 풀려 VerifiedOntologyId 로도 읽기 함수를 부를 수 있게 되면) "Unused '@ts-expect-error' directive" 로
// typecheck 가 실패한다.
import { describe, it, expect } from 'vitest';
import type { readWholeGraph } from './neo4j-client.js';
import type { retrieve } from './retriever.js';
import type { structuredQuery } from './structured-query.js';
import type { loadGraph } from './loader.js';
import type { GraphReadableOntologyId, VerifiedOntologyId } from './verified-ontology-id.js';
import {
  GRAPH_READ_CHECK_FAILED_CODE,
  GRAPH_READ_CHECK_FAILED_MESSAGE,
  GraphReadCheckFailedError,
  GRAPH_READ_RESTRICTED_CODE,
  GRAPH_READ_RESTRICTED_MESSAGE,
  GraphReadRestrictedError,
  requireGraphReadable,
} from './graph-read-gate.js';

describe('requireGraphReadable', () => {
  it("'restricted' 면 스펙 문구의 GraphReadRestrictedError 를 던진다", () => {
    let thrown: unknown;
    try {
      requireGraphReadable('restricted');
    } catch (e) {
      thrown = e;
    }
    expect(thrown).toBeInstanceOf(GraphReadRestrictedError);
    expect((thrown as GraphReadRestrictedError).code).toBe('GRAPH_READ_RESTRICTED');
    expect((thrown as Error).message).toBe(
      '이 지식그래프에는 열람 권한이 없는 데이터가 포함되어 있어 조회할 수 없습니다.',
    );
  });

  // 판정 조회 실패는 권한 없음과 다른 오류다 — 문구가 재시도를 안내해야 사용자가 권한 문제로 오해하지 않는다.
  it("'unavailable' 이면 재시도 안내 문구의 GraphReadCheckFailedError 를 던진다", () => {
    let thrown: unknown;
    try {
      requireGraphReadable('unavailable');
    } catch (e) {
      thrown = e;
    }
    expect(thrown).toBeInstanceOf(GraphReadCheckFailedError);
    expect(thrown).not.toBeInstanceOf(GraphReadRestrictedError);
    expect((thrown as GraphReadCheckFailedError).code).toBe('GRAPH_READ_CHECK_FAILED');
    expect((thrown as Error).message).toBe('지식그래프 열람 권한을 확인하지 못했습니다. 잠시 후 다시 시도하세요.');
    expect(GRAPH_READ_CHECK_FAILED_CODE).toBe('GRAPH_READ_CHECK_FAILED');
    expect(GRAPH_READ_CHECK_FAILED_MESSAGE).toBe('지식그래프 열람 권한을 확인하지 못했습니다. 잠시 후 다시 시도하세요.');
  });

  it('판정이 있으면 같은 값을 그대로 돌려준다', () => {
    // 브랜드 값은 해소 함수만 만든다 — 테스트에서는 런타임 값(number)만 흉내 낸다.
    const id = 42 as unknown as GraphReadableOntologyId;
    expect(requireGraphReadable(id)).toBe(42);
  });

  it('공유 코드·문구 상수가 스펙 원문과 같다', () => {
    expect(GRAPH_READ_RESTRICTED_CODE).toBe('GRAPH_READ_RESTRICTED');
    expect(GRAPH_READ_RESTRICTED_MESSAGE).toBe(
      '이 지식그래프에는 열람 권한이 없는 데이터가 포함되어 있어 조회할 수 없습니다.',
    );
  });
});

// ─── 타입 계약(컴파일 타임) ─────────────────────────────────────────────
// 읽기 함수의 ontologyId 매개변수 타입. 값 import 없이 typeof 로만 참조해 Neo4j 드라이버를 건드리지 않는다.
type ReadWholeGraphArg = Parameters<typeof readWholeGraph>[0];
type RetrieveArg = Parameters<typeof retrieve>[1];
type StructuredQueryArg = Parameters<typeof structuredQuery>[1];
type LoadGraphArg = Parameters<typeof loadGraph>[3];

/** 실행하지 않는 함수 — 본문은 tsc 만 본다. */
function typeContract(verified: VerifiedOntologyId, readable: GraphReadableOntologyId): unknown[] {
  // 테넌트 경계만 통과한 id(VerifiedOntologyId)로는 세 읽기 함수를 부를 수 없다 — 판정 누락은 컴파일 오류다.
  // @ts-expect-error VerifiedOntologyId 는 GraphReadableOntologyId 가 아니다
  const a: ReadWholeGraphArg = verified;
  // @ts-expect-error VerifiedOntologyId 는 GraphReadableOntologyId 가 아니다
  const b: RetrieveArg = verified;
  // @ts-expect-error VerifiedOntologyId 는 GraphReadableOntologyId 가 아니다
  const c: StructuredQueryArg = verified;
  // 생숫자는 더더욱 안 된다.
  // @ts-expect-error number 는 GraphReadableOntologyId 가 아니다
  const d: ReadWholeGraphArg = 42;
  // 읽기 판정 id 는 하위 타입이라 읽기·쓰기 모두에 넘어간다(쓰기 경로는 읽기 제한과 무관, 스펙 §6).
  const e: ReadWholeGraphArg = readable;
  const f: LoadGraphArg = readable;
  return [a, b, c, d, e, f];
}

describe('타입 계약', () => {
  it('typeContract 는 tsc 전용이라 실행하지 않는다(참조만 유지)', () => {
    expect(typeof typeContract).toBe('function');
  });
});
