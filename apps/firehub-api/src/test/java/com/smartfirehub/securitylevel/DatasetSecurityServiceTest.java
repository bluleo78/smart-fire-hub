package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.service.DatasetSecurityService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 데이터셋 단위 보안 규칙(스펙 §4.5 clone 행, §4.7 데이터셋 하향·허용 목록). */
class DatasetSecurityServiceTest extends IntegrationTestBase {

  @Autowired protected DSLContext dsl;
  @Autowired protected PasswordEncoder encoder;
  @Autowired protected DatasetSecurityService service;

  protected SecurityFixture fx;
  protected final List<Long> datasets = new ArrayList<>();
  protected final List<Long> users = new ArrayList<>();
  protected final List<Long> roles = new ArrayList<>();
  protected long creatorId;

  @BeforeEach
  void baseSetUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    creatorId = fx.createUser("dss_creator");
    users.add(creatorId);
  }

  @AfterEach
  void baseTearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    datasets.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  protected long dataset(String level) {
    long id = fx.createDatasetRow("dss_" + System.nanoTime(), fx.levelId(level), creatorId);
    datasets.add(id);
    return id;
  }

  @Test
  void clone_inheritsSourceLevel() {
    long src = dataset("민감");
    long copy = dataset("내부");
    service.inheritFromSource(src, copy, creatorId);
    Long level =
        inTenantFixture(
            () ->
                dsl.select(DATASET.SECURITY_LEVEL_ID)
                    .from(DATASET)
                    .where(DATASET.ID.eq(copy))
                    .fetchSingle(DATASET.SECURITY_LEVEL_ID));
    assertThat(level).isEqualTo(fx.levelId("민감"));
  }

  @Test
  void clone_copiesAllowlist_soCopyIsNotOrphaned() {
    long src = dataset("기밀");
    long viewer = fx.createUser("dss_viewer");
    users.add(viewer);
    fx.grantUser(src, viewer);
    long copy = dataset("내부");
    service.inheritFromSource(src, copy, creatorId);
    List<Long> grantedUsers =
        inTenantFixture(
            () ->
                dsl.select(DATASET_ACCESS_GRANT.USER_ID)
                    .from(DATASET_ACCESS_GRANT)
                    .where(DATASET_ACCESS_GRANT.DATASET_ID.eq(copy))
                    .fetch(DATASET_ACCESS_GRANT.USER_ID));
    assertThat(grantedUsers).containsExactly(viewer);
  }
}
