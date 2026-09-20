// 그래프 **읽기**는 예외 없이 온톨로지 단위라는 규약의 트립와이어.
//
// 왜 이 형태인가: Neo4j 에는 RLS 같은 강제 장치가 없고, `session.run` 은 임의의 Cypher 문자열을
// 받는다. 세션을 감싸 "스코프 없는 :Entity 읽기"를 런타임에 막으려면 결국 문자열 매칭이 되는데,
// 그건 정규식의 오탐률을 운영 장애 형태로 갖는 것이다(게다가 술어의 **위치**는 검사할 수 없다 —
// readWholeGraph 의 엣지 질의처럼 양쪽 끝점에 걸어야 하는 경우를 통과시킨다).
//
// 그래서 증명이 아니라 트립와이어로 둔다: 새로 스코프 없는 읽기를 추가하려면 아래 허용목록을
// 고쳐야 하고, 그 편집은 코드리뷰에 보인다. 실수로 새는 것을 막는 게 아니라, 모르고 지나가는 것을
// 막는 장치다.
import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const GRAPHRAG_DIR = dirname(fileURLToPath(import.meta.url));

/**
 * 스코프 술어 없이 :Entity 를 MATCH 해도 되는 파일과 그 이유.
 * 새 항목을 추가할 때는 반드시 이유를 함께 적는다 — 이유 없는 추가는 리뷰에서 막으라는 뜻이다.
 */
const ALLOWLIST: Record<string, string> = {
  // 쓰기 경로: 대상을 entityKey(=`<entity_type_id>:<name>`)로 지목한다. entity_type_id 는
  // 온톨로지 하나가 소유하는 전역 유니크 DB id 라, 키 자체가 온톨로지에 귀속된다.
  'loader.ts': '적재(MERGE) — entityKey 로 지목',
  'entity-add.ts': '엔티티 추가 — entityKey 로 지목',
  'relation-add.ts': '관계 추가 — entityKey 로 지목',
  'property-mutation.ts': '속성 정정 — entityKey 로 지목',
  'synonym-merge.ts': '동의어 병합 — entityKey 로 지목',
  // 일회성 운영 스크립트: 운영자가 명시적으로 실행하며, 전 그래프를 대상으로 하는 것이 목적이다.
  'migrate-entity-keys-to-id.ts': '일회성 마이그레이션 스크립트',
  'migrate-schema-version-to-int.ts': '일회성 마이그레이션 스크립트',
};

// `MATCH (x:Entity` 형태를 찾는다. 별칭은 있을 수도 없을 수도 있다.
const ENTITY_MATCH = /MATCH\s*\(\s*\w*\s*:\s*Entity/;

describe('그래프 읽기 스코프 규약 (트립와이어)', () => {
  it('src/graphrag 의 :Entity 조회는 ontologyId 술어를 갖거나 허용목록에 있어야 한다', () => {
    const sources = readdirSync(GRAPHRAG_DIR)
      .filter((f) => f.endsWith('.ts') && !f.endsWith('.test.ts'));

    const offenders: string[] = [];
    for (const file of sources) {
      const text = readFileSync(join(GRAPHRAG_DIR, file), 'utf8');
      if (!ENTITY_MATCH.test(text)) continue;
      if (file in ALLOWLIST) continue;
      // 파일 단위 판정 — 한 파일 안의 모든 :Entity 조회가 스코프를 쓰는지까지는 보지 못한다.
      // 그래도 "ontologyId 를 전혀 언급하지 않는 새 리더"는 확실히 잡힌다.
      if (!text.includes('ontologyId')) offenders.push(file);
    }

    expect(offenders).toEqual([]);
  });

  // 허용목록이 낡아 실제로는 없는 파일을 가리키면 위 검사가 조용히 헐거워진다.
  it('허용목록에 실재하지 않는 파일이 남아 있지 않다', () => {
    const present = new Set(readdirSync(GRAPHRAG_DIR));
    const stale = Object.keys(ALLOWLIST).filter((f) => !present.has(f));
    expect(stale).toEqual([]);
  });
});
