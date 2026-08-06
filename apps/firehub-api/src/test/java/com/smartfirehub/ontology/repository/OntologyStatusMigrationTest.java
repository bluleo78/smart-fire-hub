package com.smartfirehub.ontology.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// V79 마이그레이션 검증 — status 컬럼의 기본값·CHECK 제약과, archived를 제외하는 부분 유니크 인덱스.
// 부분 인덱스가 없으면 "은퇴시키고 같은 도메인의 후속을 세운다"가 불가능해지므로 여기서 고정한다.
class OntologyStatusMigrationTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;

  // 이 테스트가 만든 행만 정리한다(시드 id=1은 건드리지 않는다).
  private final List<Long> createdIds = new ArrayList<>();

  @AfterEach
  void cleanup() {
    for (Long id : createdIds) {
      dsl.deleteFrom(table(name("ontology"))).where(field(name("id"), Long.class).eq(id)).execute();
    }
    createdIds.clear();
  }

  // status를 지정하지 않고 삽입한 행에 도메인명을 붙여 넣는다.
  private long insert(String domain, String status) {
    Long id =
        dsl.insertInto(table(name("ontology")))
            .set(field(name("domain"), String.class), domain)
            .set(field(name("schema_version"), Integer.class), 1)
            .set(field(name("status"), String.class), status)
            .returning(field(name("id"), Long.class))
            .fetchOne()
            .get(field(name("id"), Long.class));
    createdIds.add(id);
    return id;
  }

  @Test
  void 기존_시드_온톨로지는_active_기본값을_가진다() {
    String status =
        dsl.select(field(name("status"), String.class))
            .from(table(name("ontology")))
            .where(field(name("id"), Long.class).eq(1L))
            .fetchOne()
            .value1();
    assertThat(status).isEqualTo("active");
  }

  @Test
  void 허용되지_않는_status는_CHECK_제약에_걸린다() {
    assertThatThrownBy(() -> insert("V79 잘못된 상태 테스트", "retired"))
        .hasMessageContaining("ontology_status_check");
  }

  @Test
  void archived는_도메인명을_선점하지_않는다() {
    insert("V79 도메인 재사용 테스트", "archived");
    // 같은 도메인으로 active를 새로 세울 수 있어야 한다 — 은퇴 후 후속 온톨로지 시나리오.
    long successor = insert("V79 도메인 재사용 테스트", "active");
    assertThat(successor).isPositive();
  }

  @Test
  void 살아있는_온톨로지끼리는_도메인명이_중복될_수_없다() {
    insert("V79 중복 방지 테스트", "active");
    assertThatThrownBy(() -> insert("V79 중복 방지 테스트", "draft"))
        .hasMessageContaining("ontology_domain_unique");
  }
}
