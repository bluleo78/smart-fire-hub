package com.smartfirehub.vectorstore.qdrant;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.matchValues;

import com.google.common.util.concurrent.ListenableFuture;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.QuantizationSearchParams;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchParams;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Qdrant 색인 구현들이 함께 쓰는 도우미(WD-720). 테넌트 필터, 결과 기다리기, 커밋 뒤 삭제를 한곳에 둔다.
 *
 * <p>테넌트 필터는 모든 검색·삭제에 붙는다(ADR 0002 결정 5·7). 컬렉션을 테넌트끼리 함께 쓰기 때문이다. {@code tenant_id} 는 키워드형이라 값을
 * 문자열로 싣는다(정수형 색인은 Qdrant 1.20 이 {@code is_tenant} 를 버린다 — WD-719 실측).
 */
@Slf4j
final class QdrantSupport {

  /**
   * 검색 후보를 topK 의 몇 배로 받는가(ADR 0002 결정 5). 플랫폼 DB 사후 확인에서 빠지는 후보(저장소 반영이 늦은 삭제·불허, 적재 중 문서)가 있어도
   * topK 를 채우려고 넉넉히 받는다.
   */
  static final int CANDIDATE_FACTOR = 3;

  private QdrantSupport() {}

  /** payload {@code tenant_id} 값(문자열). */
  static String tenantKey(long tenantId) {
    return Long.toString(tenantId);
  }

  /** 이 테넌트 점만 고르는 조건. */
  static Condition tenant(long tenantId) {
    return matchKeyword(QdrantCollectionSpec.TENANT_ID, tenantKey(tenantId));
  }

  /** 정수 payload 가 목록 안에 있는 점만 고르는 조건. */
  static Condition anyOf(String field, List<Long> values) {
    return matchValues(field, values);
  }

  /** 조건을 모두 만족하는(must) 필터. */
  static Filter allOf(Condition... conditions) {
    return Filter.newBuilder().addAllMust(List.of(conditions)).build();
  }

  /**
   * dense 검색 파라미터 — int8 양자화본으로 후보를 찾고 원본으로 다시 점수를 매긴다(rescore). int8 은 기본으로 rescore 하지 않아 순위가 흔들릴
   * 수 있다(ADR 0002 결정 7). 양자화가 꺼져 있으면 Qdrant 가 이 값을 무시한다.
   */
  static SearchParams rescore() {
    return SearchParams.newBuilder()
        .setQuantization(QuantizationSearchParams.newBuilder().setRescore(true))
        .build();
  }

  /** 점 payload 의 정수 값. 없으면 null. */
  static Long longPayload(ScoredPoint point, String field) {
    Value v = point.getPayloadMap().get(field);
    return v == null || !v.hasIntegerValue() ? null : v.getIntegerValue();
  }

  /**
   * 비동기 호출 결과를 제한 시간까지 기다린다. 실패하면 {@link QdrantAccessException} 으로 감싼다 — 호출자가 저장소 장애를 한 종류로 다루게 하려는
   * 것이다(기능 응답 「저장소 일시 중단」은 WD-724).
   */
  static <T> T await(ListenableFuture<T> future, Duration timeout, String what) {
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new QdrantAccessException(what + " 중 중단됨", e);
    } catch (ExecutionException e) {
      throw new QdrantAccessException(what + " 실패: " + e.getCause().getMessage(), e.getCause());
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new QdrantAccessException(what + " 제한 시간(" + timeout + ") 초과", e);
    }
  }

  /**
   * 삭제를 커밋 뒤로 미룬다. 트랜잭션이 없으면 바로 하고 실패는 호출자에게 던진다.
   *
   * <p>왜 미루나: 벡터를 지우는 리포지토리 메서드는 같은 트랜잭션에서 표시 열을 비운다. Qdrant 삭제를 트랜잭션 안에서 바로 하면, 그 트랜잭션이 롤백될 때 표시는
   * 「임베딩됨」으로 남는데 벡터는 이미 사라진다(검색에서 조용히 빠짐). 커밋 뒤에 지우면 실패해도 표시는 이미 비어 있어 사후 확인이 그 결과를 걸러 낸다. 커밋 뒤 실패는
   * 로그만 남긴다 — 콜백 예외가 커밋을 마친 요청을 500 으로 만들지 않게. 남은 점은 대조 스윕(WD-721)이 지운다. 순서·유실을 보장하는 아웃박스도 WD-721
   * 이다.
   */
  static void deleteAfterCommit(String what, Runnable delete) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      delete.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              delete.run();
            } catch (RuntimeException e) {
              log.warn("Qdrant 삭제 실패(커밋 뒤) — 대조 스윕이 정리한다: {}", what, e);
            }
          }
        });
  }
}
