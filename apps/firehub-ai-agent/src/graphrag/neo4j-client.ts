// Neo4j 드라이버 싱글턴 + 세션 팩토리 + 제약 부트스트랩.
import neo4j, { Driver, Session } from 'neo4j-driver';

let driver: Driver | null = null;

// env로 드라이버를 1회 생성해 재사용한다.
export function getDriver(): Driver {
  if (!driver) {
    const uri = process.env.NEO4J_URI ?? 'bolt://localhost:7687';
    const user = process.env.NEO4J_USER ?? 'neo4j';
    const password = process.env.NEO4J_PASSWORD ?? 'firehub-graph-dev';
    driver = neo4j.driver(uri, neo4j.auth.basic(user, password));
  }
  return driver;
}

export function getSession(): Session {
  return getDriver().session();
}

// Entity.key 유일성 제약 — MERGE 멱등성과 조회 성능의 기반.
export async function bootstrapConstraints(): Promise<void> {
  const session = getSession();
  try {
    await session.run(
      'CREATE CONSTRAINT entity_key IF NOT EXISTS FOR (n:Entity) REQUIRE n.key IS UNIQUE',
    );
  } finally {
    await session.close();
  }
}

export async function closeDriver(): Promise<void> {
  if (driver) {
    await driver.close();
    driver = null;
  }
}
