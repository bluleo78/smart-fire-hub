import { client } from './client';

/**
 * 플랫폼 DB 기능 상태(WD-495). 설치본 전체에 하나뿐이라 테넌트와 무관하다.
 * PostgreSQL 설치본은 둘 다 true, Tibero 설치본은 벡터(pgvector)·트라이그램(pg_trgm)이 없어 둘 다 false 다.
 * vectorStoreAvailable 은 별도 벡터 저장소(Qdrant)가 지금 응답하는지다(WD-724) — 앞의 둘과 달리 실행 중에 바뀐다.
 */
export interface PlatformDbCapabilities {
  /** 벡터 검색(의미·하이브리드 문서 검색, 임베딩) 지원 여부. */
  vectorSearch: boolean;
  /** 트라이그램 검색(키워드 문서 검색) 지원 여부. */
  trigramSearch: boolean;
  /**
   * 벡터 저장소가 지금 응답하는지(WD-724). false 면 검색이 빈 목록을 돌려주므로 화면이 「일시 중단」을 안내한다.
   * 이 필드가 없는 옛 서버는 늘 켜짐으로 본다.
   */
  vectorStoreAvailable?: boolean;
}

/** 플랫폼 DB 기능 상태 조회 — 인증된 모든 사용자 가능(권한 없음). */
export async function getPlatformDbCapabilities(): Promise<PlatformDbCapabilities> {
  const { data } = await client.get<PlatformDbCapabilities>('/platform-db/capabilities');
  return data;
}
