package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStorePasswordGenerator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tibero 계정 계획의 SQL·스크립트 생성 검증(순수 함수, DB 접속 없음). */
class TiberoAccountPlanTest {

  private static final long TENANT = 5L;

  /** 역할마다 서로 다른 고정 비밀번호를 가진 계정 목록. */
  private static List<PlannedAccount> accounts() {
    List<PlannedAccount> list = new ArrayList<>();
    for (DataStoreAccountRole role : DataStoreAccountRole.all()) {
      list.add(new PlannedAccount(role, role.usernameFor(TENANT), "Pw" + role.name() + "xyz"));
    }
    return list;
  }

  @Test
  void provisioningSteps_exactSqlForOwner() {
    List<ProvisioningStep> steps =
        TiberoAccountPlan.of(TENANT, null, accounts()).provisioningSteps();

    assertThat(steps.get(0).sql())
        .isEqualTo(
            "CREATE USER \"GENIA_T5\" IDENTIFIED BY \"PwOWNERxyz\" DEFAULT TABLESPACE \"USR\"");
    assertThat(steps.get(1).sql()).isEqualTo("ALTER USER \"GENIA_T5\" QUOTA UNLIMITED ON \"USR\"");
    assertThat(steps.get(2).sql())
        .isEqualTo(
            "GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE SEQUENCE, CREATE TRIGGER TO"
                + " \"GENIA_T5\"");
    assertThat(steps.get(0).description()).isEqualTo("GENIA_T5 계정 생성");
    assertThat(steps.get(1).description()).isEqualTo("GENIA_T5 테이블스페이스 할당");
    assertThat(steps.get(2).description()).isEqualTo("GENIA_T5 권한 부여");
  }

  @Test
  void provisioningSteps_roAndSlotsGetCreateSessionOnly() {
    List<ProvisioningStep> steps =
        TiberoAccountPlan.of(TENANT, null, accounts()).provisioningSteps();

    assertThat(steps).hasSize(25);
    List<PlannedAccount> accounts = accounts();
    for (int i = 1; i < accounts.size(); i++) {
      PlannedAccount a = accounts.get(i);
      int base = 3 + (i - 1) * 2;
      assertThat(steps.get(base).sql())
          .isEqualTo("CREATE USER \"" + a.username() + "\" IDENTIFIED BY \"" + a.password() + "\"");
      assertThat(steps.get(base + 1).sql())
          .isEqualTo("GRANT CREATE SESSION TO \"" + a.username() + "\"");
    }
    long grants = steps.stream().filter(s -> s.sql().startsWith("GRANT")).count();
    assertThat(grants).isEqualTo(12);
  }

  @Test
  void provisioningSteps_createdUsernameMarksOnlyCreateUserSteps() {
    List<ProvisioningStep> steps =
        TiberoAccountPlan.of(TENANT, null, accounts()).provisioningSteps();

    List<ProvisioningStep> creating =
        steps.stream().filter(s -> s.createdUsername() != null).toList();
    assertThat(creating).hasSize(12);
    assertThat(creating).allMatch(s -> s.sql().startsWith("CREATE USER"));
    assertThat(steps.stream().filter(s -> s.sql().startsWith("CREATE USER")).count()).isEqualTo(12);
    assertThat(creating.get(0).createdUsername()).isEqualTo("GENIA_T5");
    assertThat(creating.get(1).createdUsername()).isEqualTo("GENIA_T5_RO");
  }

  @Test
  void provisioningSteps_descriptionsAndToStringNeverContainPasswords() {
    TiberoAccountPlan plan = TiberoAccountPlan.of(TENANT, null, accounts());
    List<String> passwords = accounts().stream().map(PlannedAccount::password).toList();

    for (ProvisioningStep step : plan.provisioningSteps()) {
      for (String text : List.of(step.description(), step.toString())) {
        assertThat(text).doesNotContain("IDENTIFIED BY");
        for (String pw : passwords) {
          assertThat(text).doesNotContain(pw);
        }
      }
    }
    for (PlannedAccount account : plan.accounts()) {
      assertThat(account.toString()).doesNotContain(account.password());
    }
  }

  @Test
  void dropStatement_quotesSingleUsernameWithCascade() {
    TiberoAccountPlan plan = TiberoAccountPlan.of(TENANT, null, accounts());

    assertThat(plan.dropStatement("GENIA_T5_RO")).isEqualTo("DROP USER \"GENIA_T5_RO\" CASCADE");
  }

  /** 스크립트 상단의 DROP 주석은 생성의 역순(S10 … RO, OWNER)이다 — 나중에 만든 계정부터 지운다. */
  @Test
  void script_dropHeaderIsReverseCreationOrder() {
    List<String> drops =
        TiberoAccountPlan.of(TENANT, null, accounts())
            .script()
            .lines()
            .filter(l -> l.startsWith("-- DROP USER \""))
            .toList();

    assertThat(drops).hasSize(12);
    assertThat(drops.get(0)).isEqualTo("-- DROP USER \"GENIA_T5_S10\" CASCADE;");
    assertThat(drops.get(10)).isEqualTo("-- DROP USER \"GENIA_T5_RO\" CASCADE;");
    assertThat(drops.get(11)).isEqualTo("-- DROP USER \"GENIA_T5\" CASCADE;");
  }

  @Test
  void of_customTablespaceUsed() {
    List<ProvisioningStep> steps =
        TiberoAccountPlan.of(TENANT, "DATA_TS", accounts()).provisioningSteps();

    assertThat(steps.get(0).sql()).endsWith("DEFAULT TABLESPACE \"DATA_TS\"");
    assertThat(steps.get(1).sql()).endsWith("QUOTA UNLIMITED ON \"DATA_TS\"");
  }

  @Test
  void of_rejectsInjectedTablespace() {
    for (String bad : List.of("USR\" QUOTA", "usr;drop", "A".repeat(31))) {
      assertThatThrownBy(() -> TiberoAccountPlan.of(TENANT, bad, accounts()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void of_rejectsMismatchedAccounts() {
    List<PlannedAccount> missing = accounts().subList(0, 11);
    assertThatThrownBy(() -> TiberoAccountPlan.of(TENANT, null, missing))
        .isInstanceOf(IllegalArgumentException.class);

    List<PlannedAccount> wrongName = accounts();
    wrongName.set(1, new PlannedAccount(DataStoreAccountRole.RO, "GENIA_T6_RO", "pw"));
    assertThatThrownBy(() -> TiberoAccountPlan.of(TENANT, null, wrongName))
        .isInstanceOf(IllegalArgumentException.class);

    List<PlannedAccount> withNull = accounts();
    withNull.set(3, null);
    assertThatThrownBy(() -> TiberoAccountPlan.of(TENANT, null, withNull))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 비밀번호는 큰따옴표 안에 그대로 들어가므로 영문·숫자 밖이면 거부하고, 메시지에 비밀번호를 싣지 않는다. */
  @Test
  void of_rejectsNonAlphanumericPassword() {
    for (String bad : List.of("abc\" OR \"x", "with space", "semi;colon", "", "한글")) {
      List<PlannedAccount> list = accounts();
      list.set(2, new PlannedAccount(DataStoreAccountRole.S1, "GENIA_T5_S1", bad));
      assertThatThrownBy(() -> TiberoAccountPlan.of(TENANT, null, list))
          .isInstanceOf(IllegalArgumentException.class)
          .satisfies(e -> assertThat(e.getMessage()).contains("GENIA_T5_S1"));
    }
    List<PlannedAccount> nullPw = accounts();
    nullPw.set(0, new PlannedAccount(DataStoreAccountRole.OWNER, "GENIA_T5", null));
    assertThatThrownBy(() -> TiberoAccountPlan.of(TENANT, null, nullPw))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 인용 식별자는 대소문자를 구분하므로 소문자 테이블스페이스는 대문자로 맞춘다. */
  @Test
  void of_lowercaseTablespace_normalizedToUpperCase() {
    TiberoAccountPlan plan = TiberoAccountPlan.of(TENANT, "users", accounts());

    assertThat(plan.tablespace()).isEqualTo("USERS");
    List<ProvisioningStep> steps = plan.provisioningSteps();
    assertThat(steps.get(0).sql()).endsWith("DEFAULT TABLESPACE \"USERS\"");
    assertThat(steps.get(1).sql()).endsWith("QUOTA UNLIMITED ON \"USERS\"");
  }

  /** generate 는 12개 계정을 역할 순서로 만들고 비밀번호가 모두 서로 다르다. */
  @Test
  void generate_twelveAccountsWithDistinctPasswords() {
    TiberoAccountPlan plan =
        TiberoAccountPlan.generate(TENANT, null, new DataStorePasswordGenerator());

    assertThat(plan.accounts()).hasSize(12);
    assertThat(plan.accounts())
        .extracting(PlannedAccount::role)
        .isEqualTo(DataStoreAccountRole.all());
    assertThat(new HashSet<>(plan.accounts().stream().map(PlannedAccount::password).toList()))
        .hasSize(12);
    assertThat(plan.tablespace()).isEqualTo("USR");
  }

  @Test
  void script_containsAllStepsTerminatedAndCommentedDropHeader() {
    TiberoAccountPlan plan = TiberoAccountPlan.of(TENANT, null, accounts());
    String script = plan.script();
    List<String> lines = script.lines().toList();

    assertThat(script)
        .contains("테넌트 5")
        .contains("이 테넌트에 대해 이 스크립트를 이전에 실행해 만들어진 계정일 때만")
        .contains("CASCADE 는 해당 스키마의 모든 테이블을 삭제합니다")
        .contains("플랫폼 운영자에게 문의하세요");
    assertThat(lines.stream().filter(l -> l.startsWith("-- DROP USER \"")).count()).isEqualTo(12);
    assertThat(lines).contains("-- DROP USER \"GENIA_T5\" CASCADE;");
    for (ProvisioningStep step : plan.provisioningSteps()) {
      assertThat(lines).contains(step.sql() + ";");
    }
  }

  /** OWNER 의 트리거 생성 권한(WD-77)은 프로비저닝 단계와 운영자용 SCRIPT 문서 양쪽에 같은 문장으로 들어간다. */
  @Test
  void ownerGrant_includesCreateTrigger_inStepsAndScript() {
    TiberoAccountPlan plan = TiberoAccountPlan.of(TENANT, null, accounts());
    String grant =
        "GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE SEQUENCE, CREATE TRIGGER TO"
            + " \"GENIA_T5\"";

    assertThat(plan.provisioningSteps()).hasSize(25);
    assertThat(plan.provisioningSteps().get(2).sql()).isEqualTo(grant);
    assertThat(plan.script()).contains(grant + ";\n");
    // RO·슬롯에는 트리거 권한이 가지 않는다.
    assertThat(plan.script().split("CREATE TRIGGER", -1)).hasSize(2);
  }
}
