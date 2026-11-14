package com.smartfirehub.vectorstore.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.vectorstore.VectorOutboxKind;
import com.smartfirehub.vectorstore.VectorOutboxRepository;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link VectorOutboxRepository} 의 MyBatis 구현(WD-721) — {@code JooqVectorOutboxRepository} 와 같은 의미.
 * Tibero 플랫폼 DB 가 쓴다. 쓰기 트랜잭션을 열고, 호출한 트랜잭션이 있으면 합류한다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisVectorOutboxRepository implements VectorOutboxRepository {

  /** 마지막 오류 열 길이(V162). */
  private static final int ERROR_MAX = 500;

  private final VectorOutboxMapper mapper;

  /** 매퍼를 주입받는다(플랫폼 MyBatis 빈). */
  public MyBatisVectorOutboxRepository(VectorOutboxMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public long nextSeq() {
    return mapper.nextSeq();
  }

  /** 순번을 먼저 받아 넣는다 — 방언마다 다른 INSERT … RETURNING 을 피한다. */
  @Override
  public long enqueue(VectorOutboxKind kind, Long targetId, Integer keepDim) {
    long tenantId = TenantContext.require("벡터 아웃박스 쌓기");
    long seq = mapper.nextSeq();
    mapper.insert(seq, tenantId, kind.name(), targetId, keepDim);
    return seq;
  }

  @Override
  public List<Entry> findPending(int limit) {
    return mapper.findPending(TenantContext.require("벡터 아웃박스 읽기"), limit, 0);
  }

  @Override
  public void delete(long seq) {
    mapper.delete(TenantContext.require("벡터 아웃박스 지우기"), seq);
  }

  @Override
  public void markFailed(long seq, String error) {
    String cut =
        error == null || error.length() <= ERROR_MAX ? error : error.substring(0, ERROR_MAX);
    mapper.markFailed(TenantContext.require("벡터 아웃박스 실패 기록"), seq, cut);
  }
}
