package com.smartfirehub.global.jobrunr;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.IntegrationTestBase;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/** JobRunr 전용 풀이 업무 풀과 분리돼 있고 설정값이 반영되는지 확인한다(WD-555). */
class JobRunrDataSourceIntegrationTest extends IntegrationTestBase {

  @Autowired
  @Qualifier("jobRunrDataSource")
  private HikariDataSource jobRunrDataSource;

  @Autowired private DataSource businessDataSource;

  @Test
  void 전용_풀은_업무_풀과_다른_인스턴스이고_설정값을_따른다() throws Exception {
    assertThat(jobRunrDataSource).isNotSameAs(businessDataSource);
    assertThat(jobRunrDataSource.getPoolName()).isEqualTo("jobrunr");
    assertThat(jobRunrDataSource.getMaximumPoolSize()).isEqualTo(4);
    assertThat(jobRunrDataSource.getMinimumIdle()).isEqualTo(0);
    assertThat(jobRunrDataSource.getConnectionTimeout()).isEqualTo(10_000L);
    try (Connection c = jobRunrDataSource.getConnection()) {
      assertThat(c.isValid(2)).isTrue();
    }
  }
}
