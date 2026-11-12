package com.smartfirehub.securitylevel.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * EmbeddingAiPolicyRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-711).
 *
 * <p>허용 규칙(스펙 D4): ai_policy ALL 은 언제나, SELF_HOSTED_ONLY 는 자체 호스팅일 때만, DENY 는 언제나 불허. 호스팅 null 은
 * 외부로 본다. jOOQ({@code JooqDatasetVisibility.aiPolicyAllows})와 MyBatis({@code
 * MyBatisDatasetVisibility.aiPolicyValues} + {@code aiPolicyAllowedDataset} 조각) 두 벌의 일치를 이 행렬이
 * 고정한다.
 *
 * <p>dataset 은 정책(RLS) 아래 있지만 jOOQ 는 dataset.tenant_id 를 인자로 명시한다 — A 컨텍스트에서 B 를 인자로 물으면 A 의 데이터셋이
 * 답에 섞이지 않아야 한다(RLS 만으로는 보이는 행이므로 명시 조건을 가려내는 시험).
 */
public abstract class EmbeddingAiPolicyRepositoryContractTest extends TenantPairContractBase {

  protected abstract EmbeddingAiPolicyRepository repository();

  /**
   * 문자열 술어 렌더({@code allowedDatasetSql})를 구현하는가 — jOOQ 참, MyBatis 거짓(VECTOR_SEARCH 꺼진 기능, 스펙 D3).
   */
  protected boolean supportsRenderedSql() {
    return true;
  }

  private long dsAll;
  private long dsSelf;
  private long dsDeny;

  /** 테넌트 A 에 ai_policy 별 등급·데이터셋 하나씩을 둔다. */
  @BeforeEach
  void seedMatrix() {
    dsAll = datasetWithPolicy(tenantA, "all", 3, "ALL");
    dsSelf = datasetWithPolicy(tenantA, "self", 4, "SELF_HOSTED_ONLY");
    dsDeny = datasetWithPolicy(tenantA, "deny", 5, "DENY");
  }

  private long datasetWithPolicy(long tenantId, String suffix, int rank, String aiPolicy) {
    long level = fx.createSecurityLevel(tenantId, rank, prefix + "-" + suffix);
    fx.setSecurityLevelAiPolicy(level, aiPolicy);
    long ds = fx.createDataset(tenantId, prefix + "-ds-" + suffix);
    fx.setDatasetField(ds, "security_level_id", level);
    return ds;
  }

  /** (ai_policy, 호스팅, 허용 여부) 행렬 — 호스팅 null 은 외부와 같다. */
  static Stream<Arguments> matrix() {
    return Stream.of(
        Arguments.of("ALL", ProviderHosting.EXTERNAL, true),
        Arguments.of("ALL", ProviderHosting.SELF_HOSTED, true),
        Arguments.of("ALL", null, true),
        Arguments.of("SELF_HOSTED_ONLY", ProviderHosting.EXTERNAL, false),
        Arguments.of("SELF_HOSTED_ONLY", ProviderHosting.SELF_HOSTED, true),
        Arguments.of("SELF_HOSTED_ONLY", null, false),
        Arguments.of("DENY", ProviderHosting.EXTERNAL, false),
        Arguments.of("DENY", ProviderHosting.SELF_HOSTED, false),
        Arguments.of("DENY", null, false));
  }

  private long datasetOf(String aiPolicy) {
    return switch (aiPolicy) {
      case "ALL" -> dsAll;
      case "SELF_HOSTED_ONLY" -> dsSelf;
      default -> dsDeny;
    };
  }

  @ParameterizedTest(name = "{0} × {1} → {2}")
  @MethodSource("matrix")
  @DisplayName("datasetAllowed·datasetDisallowed 는 ai_policy × 호스팅 행렬에서 서로 반대 답을 낸다")
  void allowedAndDisallowed_matrix(String aiPolicy, ProviderHosting hosting, boolean allowed) {
    long ds = datasetOf(aiPolicy);

    assertThat(inA(() -> repository().datasetAllowed(ds, tenantA, hosting))).isEqualTo(allowed);
    assertThat(inA(() -> repository().datasetDisallowed(ds, tenantA, hosting))).isEqualTo(!allowed);
  }

  @Test
  @DisplayName("없는 데이터셋은 허용도 불허도 아니다(false·false)")
  void missingDataset() {
    for (ProviderHosting h : Arrays.asList(ProviderHosting.EXTERNAL, null)) {
      assertThat(inA(() -> repository().datasetAllowed(-1L, tenantA, h))).isFalse();
      assertThat(inA(() -> repository().datasetDisallowed(-1L, tenantA, h))).isFalse();
    }
  }

  @Test
  @DisplayName("인자 테넌트가 다르면 A 의 데이터셋은 허용·불허 어느 쪽으로도 보이지 않는다(명시 dataset.tenant_id)")
  void explicitTenant() {
    // A 컨텍스트(RLS 로는 A 행이 보인다)에서 인자만 B 로 묻는다.
    assertThat(inA(() -> repository().datasetAllowed(dsAll, tenantB, ProviderHosting.EXTERNAL)))
        .isFalse();
    assertThat(inA(() -> repository().datasetDisallowed(dsDeny, tenantB, ProviderHosting.EXTERNAL)))
        .isFalse();
    assertThat(inA(() -> repository().disallowedDatasetIds(tenantB, ProviderHosting.EXTERNAL)))
        .isEmpty();
    // B 컨텍스트에서는 A 의 데이터셋이 RLS 로 보이지 않는다.
    assertThat(inB(() -> repository().datasetAllowed(dsAll, tenantA, ProviderHosting.EXTERNAL)))
        .isFalse();
  }

  @Test
  @DisplayName("disallowedDatasetIds 는 호스팅으로 보낼 수 없는 이 테넌트 데이터셋 id 를 오름차순으로 돌려준다")
  void disallowedDatasetIds() {
    // 기본 등급(ALL) 데이터셋은 허용이라 빠진다. 다른 테넌트의 DENY 데이터셋은 섞이지 않는다.
    long plain = fx.createDataset(tenantA, prefix + "-plain");
    datasetWithPolicy(tenantB, "bdeny", 3, "DENY");
    long deny2 = datasetWithPolicy(tenantA, "deny2", 6, "DENY");

    List<Long> external = new ArrayList<>(List.of(dsSelf, dsDeny, deny2));
    external.sort(Long::compare);
    List<Long> self = new ArrayList<>(List.of(dsDeny, deny2));
    self.sort(Long::compare);

    assertThat(inA(() -> repository().disallowedDatasetIds(tenantA, ProviderHosting.EXTERNAL)))
        .containsExactlyElementsOf(external)
        .doesNotContain(plain, dsAll);
    assertThat(inA(() -> repository().disallowedDatasetIds(tenantA, null)))
        .containsExactlyElementsOf(external);
    assertThat(inA(() -> repository().disallowedDatasetIds(tenantA, ProviderHosting.SELF_HOSTED)))
        .containsExactlyElementsOf(self);
  }

  @Test
  @DisplayName("allowedDatasetSql — 렌더하는 구현은 호출부 식을 담은 술어를, 꺼진 구현은 VECTOR_SEARCH 꺼진 기능 예외를 던진다")
  void allowedDatasetSql_rendersOrIsOff() {
    if (supportsRenderedSql()) {
      assertThat(
              inA(() -> repository().allowedDatasetSql("de.dataset_id", ProviderHosting.EXTERNAL)))
          .contains("de.dataset_id");
      return;
    }
    assertThatThrownBy(
            () ->
                inA(
                    () ->
                        repository().allowedDatasetSql("de.dataset_id", ProviderHosting.EXTERNAL)))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("VECTOR_SEARCH");
  }
}
