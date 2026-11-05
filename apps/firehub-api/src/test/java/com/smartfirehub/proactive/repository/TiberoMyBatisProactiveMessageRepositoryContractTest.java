package com.smartfirehub.proactive.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.repository.mybatis.MyBatisProactiveMessageRepository;
import com.smartfirehub.proactive.repository.mybatis.ProactiveMessageMapper;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.support.tibero.TiberoPlatformPoc;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ProactiveMessageRepositoryContractTest 를 MyBatis 구현 + Tibero 실서버로 실행한다(WD-487 PoC).
 *
 * <p><b>테스트 트랜잭션을 끈다({@code NOT_SUPPORTED}).</b> 기반 클래스는 클래스 레벨 {@code @Transactional}(PG 롤백)인데, PG
 * 트랜잭션이 열린 스레드에서 Tibero 트랜잭션을 시작하면 Tibero 관리자가 트랜잭션 동기화를 소유하지 못해, SqlSessionTemplate 이 세션을 PG 트랜잭션
 * 쪽 동기화에 걸어 두고 이미 반납한 커넥션을 다음 호출에서 재사용하게 된다. 그래서 Tibero 픽스처 행은 롤백되지 않고 남는다 — 테스트마다 새 사용자를 만들고 단언이
 * 사용자 단위라 서로 섞이지 않으며, 클래스 끝({@link #cleanupTiberoFixtures()})에 지운다.
 *
 * <p><b>열람 자격(ClearanceResolver)은 스텁이다.</b> 실제 해석기는 PG 리포지토리·권한 서비스에 묶여 있어, Tibero {@code user_role
 * → role → security_level} 에서 최대 등급을 읽는 하위 클래스로 바꾼다. 멤버십·관리자 여부·권한은 보지 않는다(이 계약이 쓰는 것은 rank 하나다).
 */
@EnabledIfEnvironmentVariable(named = "TIBERO_TEST_HOST", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TiberoMyBatisProactiveMessageRepositoryContractTest
    extends ProactiveMessageRepositoryContractTest {

  @Autowired private ObjectMapper objectMapper;

  private TiberoPlatformPoc poc;
  private ProactiveMessageRepository repository;

  @BeforeAll
  void startTibero() {
    poc = TiberoPlatformPoc.shared();
    repository =
        poc.transactional(
            ProactiveMessageRepository.class,
            new MyBatisProactiveMessageRepository(
                poc.mapper(ProactiveMessageMapper.class),
                objectMapper,
                new TiberoClearanceResolver(poc)));
  }

  /**
   * 이 클래스가 넣은 픽스처 행을 지운다 — 테스트 트랜잭션이 없어 롤백되지 않고, PoC 스키마는 JVM 공용이라 다음 클래스가 본다. 기반 클래스의 사용자는 모두
   * {@code pmrepo} 접두사라 그 사용자에 딸린 메시지·작업·실행·역할 부여를 먼저 지운다.
   */
  @AfterAll
  void cleanupTiberoFixtures() {
    if (poc == null) return;
    String users = "SELECT id FROM \"USER\" WHERE username LIKE 'pmrepo%'";
    poc.jdbc().update("DELETE FROM proactive_message WHERE user_id IN (" + users + ")");
    poc.jdbc()
        .update(
            "DELETE FROM proactive_job_execution WHERE job_id IN"
                + " (SELECT id FROM proactive_job WHERE user_id IN ("
                + users
                + "))");
    poc.jdbc().update("DELETE FROM proactive_job WHERE user_id IN (" + users + ")");
    poc.jdbc().update("DELETE FROM user_role WHERE user_id IN (" + users + ")");
    poc.jdbc().update("DELETE FROM role WHERE name LIKE 'pmrepo_lv_%'");
    poc.jdbc().update("DELETE FROM \"USER\" WHERE username LIKE 'pmrepo%'");
  }

  @Override
  protected ProactiveMessageRepository repository() {
    return repository;
  }

  @Override
  protected Long insertUser(String username, String password, String name, String email) {
    return poc.insertReturningId(
        "INSERT INTO \"USER\"(username, password, name, email) VALUES (?, ?, ?, ?)",
        username,
        password,
        name,
        email);
  }

  /** 작업·COMPLETED 실행을 직접 넣는다(PG 는 작업·실행 리포지토리 경유 — 이 계약이 쓰는 것은 작업 주인과 실행 id 뿐이다). */
  @Override
  protected Long createReportExecution(Long ownerUserId) {
    long jobId =
        poc.insertReturningId(
            "INSERT INTO proactive_job (tenant_id, user_id, name) VALUES (?, ?, ?)",
            DEFAULT_TEST_TENANT_ID,
            ownerUserId,
            "주간 현황");
    return poc.insertReturningId(
        "INSERT INTO proactive_job_execution (tenant_id, job_id, status) VALUES (?, ?, 'COMPLETED')",
        DEFAULT_TEST_TENANT_ID,
        jobId);
  }

  @Override
  protected void giveLevel(Long uid, String levelName) {
    Long levelId =
        poc.jdbc()
            .queryForObject(
                "SELECT id FROM security_level WHERE tenant_id = ? AND name = ?",
                Long.class,
                DEFAULT_TEST_TENANT_ID,
                levelName);
    long roleId =
        poc.insertReturningId(
            "INSERT INTO role (tenant_id, name, max_security_level_id) VALUES (?, ?, ?)",
            DEFAULT_TEST_TENANT_ID,
            "pmrepo_lv_" + System.nanoTime(),
            levelId);
    poc.jdbc()
        .update(
            "INSERT INTO user_role (user_id, role_id, tenant_id) VALUES (?, ?, ?)",
            uid,
            roleId,
            DEFAULT_TEST_TENANT_ID);
  }

  @Override
  protected void setReportLevel(Long execId, String levelName) {
    poc.jdbc()
        .update(
            "UPDATE proactive_job_execution SET security_level_id ="
                + " (SELECT id FROM security_level WHERE tenant_id = ? AND name = ?) WHERE id = ?",
            DEFAULT_TEST_TENANT_ID,
            levelName,
            execId);
  }

  @Override
  protected void revokeRoles(Long uid) {
    poc.jdbc().update("DELETE FROM user_role WHERE user_id = ?", uid);
  }

  /**
   * Tibero 픽스처의 등급 부여로 자격을 계산하는 테스트용 해석기. 부모의 협력 객체(PG 리포지토리·권한 서비스)는 쓰지 않으므로 null 로 둔다 — {@link
   * #resolve(long)} 만 재정의하고 다른 메서드는 부르지 않는다.
   */
  static final class TiberoClearanceResolver extends ClearanceResolver {
    private final TiberoPlatformPoc poc;

    TiberoClearanceResolver(TiberoPlatformPoc poc) {
      super(null, null);
      this.poc = poc;
    }

    @Override
    public Clearance resolve(long userId) {
      long tenantId = TenantContext.require("열람 자격 계산(Tibero 스텁)");
      List<java.util.Map<String, Object>> rows =
          poc.jdbc()
              .queryForList(
                  "SELECT r.id AS role_id, sl.rank AS rnk FROM user_role ur"
                      + " JOIN role r ON r.id = ur.role_id"
                      + " JOIN security_level sl ON sl.id = r.max_security_level_id"
                      + " WHERE ur.user_id = ? AND ur.tenant_id = ?",
                  userId,
                  tenantId);
      if (rows.isEmpty()) return Clearance.none(userId, tenantId);
      int max = Integer.MIN_VALUE;
      Set<Long> roleIds = new HashSet<>();
      for (var r : rows) {
        roleIds.add(((Number) r.get("ROLE_ID")).longValue());
        max = Math.max(max, ((Number) r.get("RNK")).intValue());
      }
      return new Clearance(userId, tenantId, max, roleIds, false, Set.of());
    }
  }
}
