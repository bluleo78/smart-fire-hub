package com.smartfirehub.ontology.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.platformdb.mybatis.InChunks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * GraphOntologySourceRepositoryContractTest 를 MyBatis(PostgreSQL) 구현으로 실행한다(WD-711) — 빈 이름으로 꺼낸다.
 */
class MyBatisGraphOntologySourceRepositoryContractTest
    extends GraphOntologySourceRepositoryContractTest {

  @Autowired private ApplicationContext applicationContext;

  @Override
  protected GraphOntologySourceRepository repository() {
    return applicationContext.getBean(
        "myBatisGraphOntologySourceRepository", GraphOntologySourceRepository.class);
  }

  @Test
  @DisplayName("findDatasetsWithGraphHistory 문장은 id 조각을 한 번만 바인딩한다(1201 개 id + 테넌트 2 개 = 1203 바인드)")
  void selectDatasetsWithGraphHistory_bindsIdsOnce() {
    List<Long> ids = new ArrayList<>();
    for (long i = 0; i < 1201; i++) {
      ids.add(600_000L + i);
    }
    Map<String, Object> params = new HashMap<>();
    params.put("tenantId", 1L);
    params.put("chunks", InChunks.of(ids));

    int binds =
        applicationContext
            .getBean("platformSqlSessionFactory", SqlSessionFactory.class)
            .getConfiguration()
            .getMappedStatement(
                "com.smartfirehub.ontology.repository.mybatis.GraphOntologySourceMapper"
                    + ".selectDatasetsWithGraphHistory")
            .getBoundSql(params)
            .getParameterMappings()
            .size();

    // UNION 가지마다 조각을 걸면 2 × 1201 + 2 = 2404 가 된다.
    assertThat(binds).isEqualTo(1201 + 2);
  }
}
