package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로컬 PYTHON 출력 적재가 실제 NUMERIC 컬럼에 비유한수를 executor 와 같게 넣는지(CR6). executor 는 Decimal NaN/±Infinity 를
 * psycopg2 가 전부 {@code 'NaN'::numeric} 으로 보내므로, 같은 stdout 이 로컬 경로에서도 NaN 행이 되어야 한다(예전엔 NULL).
 */
@Transactional
class LocalPythonOutputLoaderDbTest extends IntegrationTestBase {

  @Autowired private LocalPythonOutputLoader loader;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;

  @Test
  void nonFiniteDecimal_loadsAsNumericNaN() {
    Long userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "local_py_nan_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Local Py NaN")
            .set(USER.EMAIL, "local_py_nan@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
    datasetService.createDataset(
        new CreateDatasetRequest(
            "local_py_nan",
            "local_py_nan",
            null,
            null,
            "TABLE",
            "SOURCE",
            List.of(
                new DatasetColumnRequest("k", "K", "INTEGER", null, true, false, null),
                new DatasetColumnRequest("v", "V", "DECIMAL", null, true, false, null)),
            null),
        userId);

    // Python json.dumps 가 내는 그대로의 토큰 + 문자열 철자 + 유한수
    String stdout =
        "[{\"k\":1,\"v\":NaN},{\"k\":2,\"v\":Infinity},{\"k\":3,\"v\":-Infinity},"
            + "{\"k\":4,\"v\":\"nan\"},{\"k\":5,\"v\":1.5}]";
    long loaded = loader.load("local_py_nan", stdout, Map.of("k", "INTEGER", "v", "DECIMAL"));

    assertThat(loaded).isEqualTo(5);
    List<String> values =
        dsl.fetch("SELECT v::text FROM " + DataSchema.qualify("local_py_nan") + " ORDER BY k")
            .getValues(0, String.class);
    assertThat(values).containsExactly("NaN", "NaN", "NaN", "NaN", "1.500000");
  }
}
