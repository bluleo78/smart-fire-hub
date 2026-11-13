package com.smartfirehub.vectorstore;

/**
 * 벡터 저장소의 기동 준비·상태 점검 포트(ADR 0002 결정 2).
 *
 * <p>Qdrant 구현은 기동 때 컬렉션·payload 색인을 만들거나 확인하고, 구성(스키마 버전 포함)이 어긋나면 기동을 멈춘다(Flyway validate 와 같은
 * 역할). 플랫폼 DB 안의 pgvector 는 Flyway 가 스키마를 맡으므로 이 포트가 할 일이 없다.
 */
public interface VectorStoreLifecycle {

  /**
   * 저장소를 쓸 수 있게 준비한다. 없으면 만들고, 있으면 기대 구성과 대조한다.
   *
   * @throws IllegalStateException 기존 구성이 기대와 다를 때(기동을 멈춰야 한다)
   */
  void prepare();

  /** 저장소가 지금 응답하는지. 내려가 있을 때 기능 응답의 「저장소 일시 중단」 안내에 쓴다(결정 3·11). */
  boolean available();
}
