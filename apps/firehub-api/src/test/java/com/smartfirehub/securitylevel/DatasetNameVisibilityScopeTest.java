package com.smartfirehub.securitylevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CloneDatasetRequest;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.UpdateDatasetRequest;
import com.smartfirehub.dataset.exception.DuplicateDatasetNameException;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * WD-155(B안): 데이터셋 이름 유일 범위는 「조회자가 볼 수 있는 데이터셋」이다 — 볼 수 없는(상위 등급) 데이터셋과 같은 이름으로 만들기·복제·이름 바꾸기가 그대로
 * 되어, 이름을 맞혀 보는 것으로 존재를 알 수 없다. 보이는 데이터셋과 겹치면 여전히 409 다(대조). 테이블명은 물리 제약이라 막히지만 받은 값을 싣지 않는 일반 문구로
 * 답한다.
 */
class DatasetNameVisibilityScopeTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private DatasetService datasetService;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> hiddenRows = new ArrayList<>();
  private final List<Long> created = new ArrayList<>();
  private String n;
  private String hiddenName;
  private String hiddenTable;
  private long user;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    n = Long.toString(System.nanoTime() % 1_000_000_000L);
    long creator = fx.createUser("wd155c");
    users.add(creator);
    hiddenTable = "wd155h" + n;
    hiddenRows.add(fx.createDatasetRow(hiddenTable, fx.levelId("기밀"), creator));
    hiddenName = "sf_" + hiddenTable;

    // 열람 등급 「내부」 사용자 — 기밀 데이터셋은 목록·상세에서 보이지 않는다
    user = fx.createUser("wd155u");
    users.add(user);
    fx.removeUserRole(user);
    long rid = fx.createRole("WD155_" + n, fx.levelId("내부"), "dataset:write");
    roles.add(rid);
    fx.assignRole(user, rid);
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    created.forEach(datasetService::deleteDataset);
    SecurityContextHolder.clearContext();
    hiddenRows.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private long create(String name, String tableName) {
    long id =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    name,
                    tableName,
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("c", "c", "TEXT", null, true, false, null, false)),
                    null),
                user)
            .id();
    created.add(id);
    return id;
  }

  @Test
  void create_withHiddenDatasetsName_succeeds_butVisibleDuplicateIsRejected() {
    create(hiddenName, "wd155a" + n);

    // 이제 같은 이름이 「내가 보는」 데이터셋에 있으므로 409 — 이름 겹침 검사 자체는 그대로 돈다
    assertThatThrownBy(() -> create(hiddenName, "wd155b" + n))
        .isInstanceOf(DuplicateDatasetNameException.class)
        .hasMessageContaining(hiddenName);
  }

  @Test
  void create_withHiddenTableName_isRejectedWithoutEchoingIt() {
    assertThatThrownBy(() -> create("wd155_any_" + n, hiddenTable))
        .isInstanceOf(DuplicateDatasetNameException.class)
        .hasMessage("이 테이블명은 사용할 수 없습니다 — 다른 이름을 입력하세요");
  }

  @Test
  void rename_toHiddenName_succeeds_butToVisibleNameIsRejected() {
    long a = create("wd155_a_" + n, "wd155ra" + n);
    long b = create("wd155_b_" + n, "wd155rb" + n);

    datasetService.updateDataset(a, new UpdateDatasetRequest(hiddenName, null, null), user);
    // 자기 이름 그대로 다시 저장하는 것은 겹침이 아니다
    datasetService.updateDataset(a, new UpdateDatasetRequest(hiddenName, "설명", null), user);

    assertThatThrownBy(
            () ->
                datasetService.updateDataset(
                    b, new UpdateDatasetRequest(hiddenName, null, null), user))
        .isInstanceOf(DuplicateDatasetNameException.class);
  }

  @Test
  void clone_withHiddenDatasetsName_succeeds() {
    long src = create("wd155_src_" + n, "wd155s" + n);
    long cloned =
        datasetService
            .cloneDataset(
                src, new CloneDatasetRequest(hiddenName, "wd155cl" + n, null, false, false), user)
            .id();
    created.add(cloned);
    assertThat(cloned).isPositive();
  }
}
