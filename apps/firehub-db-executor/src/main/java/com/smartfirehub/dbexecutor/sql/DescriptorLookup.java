package com.smartfirehub.dbexecutor.sql;

import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.StoreUnavailableException;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 조회 문장 검증 + 실행 계정이 있는 기술자 확정 — 분석 조회({@link QueryRunner}, RO 계정)와 슬롯 스트리밍({@link SlotQueryRunner},
 * 슬롯 계정)이 같은 흐름을 쓰도록 한 곳에 둔다.
 *
 * <p><b>왜 한 번 새로 받는가</b>(WD-263 슬롯 계정, WD-264 RO 계정): 계정을 막 만든 직후면 캐시된 기술자(TTL 60초)에 아직 그 계정이 없다.
 * 지우고 실패하면 계정이 준비됐는데도 요청이 거부되므로 한 번 새로 받아 다시 확인한다. 강제 갱신이 무효화를 겸하고 동시 요청은 원격 조회 하나로 묶인다. 요청당 한
 * 번뿐이라(다시 없으면 바로 실패) 반복되지 않는다. 슬롯 스트리밍은 응답 첫 줄을 쓰기 전에 부르므로 이미 보낸 줄을 다시 보내는 일도 없다.
 */
final class DescriptorLookup {

  private DescriptorLookup() {}

  /**
   * 확정 결과 — 성공이면 {@code descriptor}·{@code body}, 실패면 {@code failure}(한국어 사유)만 채워진다.
   *
   * @param descriptor 실행 계정이 있는 기술자(캐시된 것 또는 새로 받은 것)
   * @param body 그 기술자의 스키마로 검증한 실행 본문(끝 {@code ;} 제거)
   * @param failure 실패 사유 — 호출자가 자기 응답 모양(JSON·NDJSON 실패 줄)으로 바꾼다
   */
  record Resolved(StoreDescriptor descriptor, String body, String failure) {

    static Resolved ok(StoreDescriptor descriptor, String body) {
      return new Resolved(descriptor, body, null);
    }

    static Resolved failed(String failure) {
      return new Resolved(null, null, failure);
    }
  }

  /**
   * 검증 → (계정이 없으면 한 번 재조회) → (스키마가 바뀌었으면 다시 검증).
   *
   * <p>검증은 원격 조회 없이 먼저 한다 — 거부될 문장 때문에 기술자를 새로 받지 않게. 새로 받은 기술자의 스키마가 다르면 그 스키마로 다시 검증해 검증 기준과 실행
   * 기술자를 어긋나게 두지 않는다. 같으면 검증 결과도 같으므로(검증은 문장·스키마만 보는 순수 함수) 다시 하지 않는다.
   *
   * @param hasAccount 기술자에 실행 계정(RO 또는 요청 슬롯)이 있는지
   * @param missingMessage 새로 받아도 계정이 없을 때의 사유
   * @throws TiberoSqlGuard.Rejected 문장 거부(한국어 사유) — 처음 검증·다시 검증 모두
   */
  static Resolved resolve(
      StoreDescriptorClient descriptors,
      StoreDescriptor cached,
      String query,
      Predicate<StoreDescriptor> hasAccount,
      String missingMessage,
      Deadline deadline) {
    String body = QueryRunner.validatedQuery(query, cached.schema());
    if (hasAccount.test(cached)) {
      return Resolved.ok(cached, body);
    }
    StoreDescriptor fresh;
    try {
      fresh = descriptors.refresh(cached.tenantId(), deadline.remaining());
    } catch (StoreUnavailableException e) {
      // 새로 받지 못했다 — 계정이 없다고 단정할 수 없으니 컨트롤러의 기술자 조회 실패와 같은 문구로 거부한다.
      return Resolved.failed(e.getMessage());
    }
    if (!hasAccount.test(fresh)) {
      return Resolved.failed(missingMessage);
    }
    if (!Objects.equals(fresh.schema(), cached.schema())) {
      body = QueryRunner.validatedQuery(query, fresh.schema());
    }
    return Resolved.ok(fresh, body);
  }
}
