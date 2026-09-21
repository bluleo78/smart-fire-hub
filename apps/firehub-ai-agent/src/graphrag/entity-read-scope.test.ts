// 그래프 조회는 예외 없이 온톨로지 단위라는 규약의 트립와이어.
//
// 왜 이 형태인가: Neo4j 에는 RLS 같은 강제 장치가 없고, `session.run` 은 임의의 Cypher 문자열을
// 받는다. 세션을 감싸 "스코프 없는 :Entity 조회"를 런타임에 막으려면 결국 문자열 매칭이 되는데,
// 그건 정규식의 오탐률을 운영 장애 형태로 갖는 것이다. 그래서 증명이 아니라 트립와이어로 둔다:
// 새로 스코프 없는 조회를 추가하려면 아래 허용목록을 고쳐야 하고, 그 편집은 코드리뷰에 보인다.
//
// 실제 증명은 각 경로의 크로스 온톨로지 통합 테스트(*.integration.test.ts)가 한다 — 술어를 지우면
// 그쪽이 깨진다(변이 테스트로 확인). 여기는 "누가 새 조회를 스코프 없이 추가했는가"만 본다.
//
// 판정 단위가 파일이 아니라 **조회 하나**인 이유: 파일 단위로 "ontologyId 라는 단어가 있는가"만
// 보면, 이미 스코프된 파일에 스코프 없는 조회를 하나 더 추가해도 통과한다. 그 검사는 사실상
// 공허했다(모든 쓰기 경로가 ontologyId 를 스탬프하므로 단어는 항상 존재한다).
import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const GRAPHRAG_DIR = dirname(fileURLToPath(import.meta.url));
const SRC_DIR = dirname(GRAPHRAG_DIR);
// routes 도 본다 — 라우트가 직접 Cypher 를 쓰지는 않지만, 스캔 범위를 graphrag 로 좁혀 두면
// "옆 디렉터리에 새로 만들면 안 걸린다"가 성립한다.
const SCAN_DIRS = [GRAPHRAG_DIR, join(SRC_DIR, 'routes')];

/**
 * 스코프 술어 없이 :Entity 를 MATCH 해도 되는 파일과 그 이유.
 * 새 항목을 추가할 때는 반드시 이유를 함께 적는다 — 이유 없는 추가는 리뷰에서 막으라는 뜻이다.
 *
 * 예전에는 쓰기 경로 다섯 개(loader/entity-add/relation-add/property-mutation/synonym-merge)가
 * "entityKey 로 지목하니 안전하다"는 이유로 여기 있었다. 그 이유는 요청 바디로 들어오는 키
 * (set-property 의 entityKey, add-relation 의 subjectKey/objectKey, add-entity 의 otherKey)에는
 * 성립하지 않았다 — 그 키로 남의 온톨로지 노드를 덮어쓰거나 지울 수 있었다. 지금은 전부 스코프된다.
 */
const ALLOWLIST: Record<string, string> = {
  // 일회성 운영 스크립트: 운영자가 명시적으로 실행하며, 전 그래프를 대상으로 하는 것이 목적이다.
  'migrate-entity-keys-to-id.ts': '일회성 마이그레이션 스크립트',
  'migrate-schema-version-to-int.ts': '일회성 마이그레이션 스크립트',
};

// `MATCH (x:Entity` 형태. 별칭은 있을 수도 없을 수도 있다.
const ENTITY_MATCH = /MATCH\s*\(\s*\w*\s*:\s*Entity/g;
// 조회 하나가 차지할 만한 범위. Cypher 한 문장이 이 안에 들어간다는 경험칙이다.
const WINDOW = 400;

function sourceFiles(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    // __fixtures__ 는 테스트 전용이다 — 여기서 dev 그래프를 정리하는 전체삭제가 정당하게 존재한다.
    if (statSync(full).isDirectory()) {
      if (name === '__fixtures__') continue;
      out.push(...sourceFiles(full));
    } else if (name.endsWith('.ts') && !name.endsWith('.test.ts')) {
      out.push(full);
    }
  }
  return out;
}

describe('그래프 조회 스코프 규약 (트립와이어)', () => {
  it('모든 :Entity 조회는 가까이에 ontologyId 술어를 갖거나 허용목록에 있어야 한다', () => {
    const offenders: string[] = [];
    for (const dir of SCAN_DIRS) {
      for (const full of sourceFiles(dir)) {
        const file = relative(SRC_DIR, full);
        if (ALLOWLIST[full.split('/').pop() as string]) continue;
        const text = readFileSync(full, 'utf8');
        for (const m of text.matchAll(ENTITY_MATCH)) {
          // 술어는 MATCH 앞(먼저 선언된 파라미터)이나 뒤(WHERE) 어느 쪽에도 올 수 있다.
          const near = text.slice(Math.max(0, m.index - WINDOW), m.index + WINDOW);
          if (!near.includes('ontologyId')) {
            const line = text.slice(0, m.index).split('\n').length;
            offenders.push(`${file}:${line}`);
          }
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  // 허용목록이 낡아 실제로는 없는 파일을 가리키면 위 검사가 조용히 헐거워진다.
  it('허용목록에 실재하지 않는 파일이 남아 있지 않다', () => {
    const present = new Set(SCAN_DIRS.flatMap((d) => sourceFiles(d)).map((f) => f.split('/').pop()));
    const stale = Object.keys(ALLOWLIST).filter((f) => !present.has(f));
    expect(stale).toEqual([]);
  });

  // 브랜드 타입의 생산 지점은 ontology-source.ts 하나여야 한다 — 다른 곳에서 캐스팅으로 만들면
  // "RLS 왕복을 거친 값"이라는 타입의 의미가 그 순간 사라진다.
  it('VerifiedOntologyId 캐스팅은 ontology-source.ts 밖에서 일어나지 않는다', () => {
    const offenders: string[] = [];
    for (const dir of SCAN_DIRS) {
      for (const full of sourceFiles(dir)) {
        if (full.endsWith('ontology-source.ts')) continue;
        if (readFileSync(full, 'utf8').includes('as VerifiedOntologyId')) {
          offenders.push(relative(SRC_DIR, full));
        }
      }
    }
    expect(offenders).toEqual([]);
  });
});
