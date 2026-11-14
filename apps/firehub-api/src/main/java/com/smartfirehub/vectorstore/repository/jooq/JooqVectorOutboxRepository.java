package com.smartfirehub.vectorstore.repository.jooq;

import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.vectorstore.VectorOutboxKind;
import com.smartfirehub.vectorstore.VectorOutboxRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link VectorOutboxRepository} 의 jOOQ(PostgreSQL) 구현(WD-721). 쓰기 트랜잭션을 연다 — 호출한 트랜잭션이 있으면 합류해 부모
 * 행 삭제·표시 열 비우기와 함께 커밋·롤백된다. RLS GUC 는 트랜잭션 시작 때 주입되고, 쓰는 문장은 RLS 와 별개로 {@code tenant_id} 를 명시한다.
 */
@Repository
@ConditionalOnPostgresPlatformDb
@RequiredArgsConstructor
@Transactional
public class JooqVectorOutboxRepository implements VectorOutboxRepository {

  /** 마지막 오류 열 길이(V162). */
  static final int ERROR_MAX = 500;

  private final DSLContext dsl;

  @Override
  public long nextSeq() {
    return dsl.fetchOne("SELECT nextval('vector_seq')").get(0, Long.class);
  }

  @Override
  public long enqueue(VectorOutboxKind kind, Long targetId, Integer keepDim) {
    long tenantId = TenantContext.require("벡터 아웃박스 쌓기");
    return dsl.fetchOne(
            "INSERT INTO vector_outbox (seq, tenant_id, kind, target_id, keep_dim)"
                + " VALUES (nextval('vector_seq'), ?, ?, ?, ?) RETURNING seq",
            tenantId,
            kind.name(),
            targetId,
            keepDim)
        .get(0, Long.class);
  }

  @Override
  public List<Entry> findPending(int limit) {
    long tenantId = TenantContext.require("벡터 아웃박스 읽기");
    return dsl.fetch(
            "SELECT seq, tenant_id, kind, target_id, keep_dim, attempts FROM vector_outbox"
                + " WHERE tenant_id = ? ORDER BY seq LIMIT ?",
            tenantId,
            limit)
        .map(
            r ->
                new Entry(
                    r.get("seq", Long.class),
                    r.get("tenant_id", Long.class),
                    VectorOutboxKind.valueOf(r.get("kind", String.class)),
                    r.get("target_id", Long.class),
                    r.get("keep_dim", Integer.class),
                    r.get("attempts", Integer.class)));
  }

  @Override
  public void delete(long seq) {
    long tenantId = TenantContext.require("벡터 아웃박스 지우기");
    dsl.execute("DELETE FROM vector_outbox WHERE tenant_id = ? AND seq = ?", tenantId, seq);
  }

  @Override
  public void markFailed(long seq, String error) {
    long tenantId = TenantContext.require("벡터 아웃박스 실패 기록");
    dsl.execute(
        "UPDATE vector_outbox SET attempts = attempts + 1, last_error = ?"
            + " WHERE tenant_id = ? AND seq = ?",
        truncate(error),
        tenantId,
        seq);
  }

  /** 오류 글을 열 길이에 맞춘다(null 은 그대로). */
  static String truncate(String error) {
    return error == null || error.length() <= ERROR_MAX ? error : error.substring(0, ERROR_MAX);
  }
}
