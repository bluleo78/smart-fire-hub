package com.smartfirehub.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 트랜잭션 템플릿 빈 두 개 — 기본(쓰기) 템플릿과 readOnly 템플릿(WD-207).
 *
 * <p>readOnly 템플릿이 필요한 이유: Tibero 테넌트는 외부 질의를 PG 트랜잭션 밖으로 빼야 해서 여러 서비스(차트·분석 대시보드·저장 쿼리·홈 대시보드)가
 * {@code @Transactional(readOnly = true)} 대신 조회 구간을 코드로 연다. 서비스마다 생성자에서 같은 템플릿을 만들던 것을 빈 하나로 모았다.
 *
 * <p>기본 템플릿을 여기서 직접 선언하는 이유: Boot 의 {@code TransactionAutoConfiguration} 은 {@code
 * TransactionOperations} 빈이 하나라도 있으면 기본 {@code transactionTemplate} 을 만들지 않는다. readOnly 빈만 두면 한정자
 * 없이 {@code TransactionTemplate} 을 받는 모든 곳(쓰기 경로)에 readOnly 템플릿이 주입되어 PG 쓰기가 전부 실패한다. 그래서 Boot 가
 * 만들던 것과 같은 기본 템플릿을 {@code @Primary} 로 선언한다.
 */
@Configuration
public class TransactionTemplateConfig {

  /** readOnly 템플릿 빈 이름 — 주입 지점의 {@code @Qualifier} 에 쓴다. */
  public static final String READ_ONLY = "readOnlyTransactionTemplate";

  /** 기본(쓰기) 템플릿 — Boot auto-config 의 {@code transactionTemplate} 과 같은 값(기본 전파·격리, readOnly 아님). */
  @Bean
  @Primary
  public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
    return new TransactionTemplate(transactionManager);
  }

  /**
   * readOnly 템플릿 — 예전 {@code @Transactional(readOnly = true)} 와 같은 값. 한정자 {@link #READ_ONLY} 로만
   * 주입된다.
   */
  @Bean(READ_ONLY)
  public TransactionTemplate readOnlyTransactionTemplate(
      PlatformTransactionManager transactionManager) {
    return readOnly(transactionManager);
  }

  /** 주어진 매니저로 readOnly 템플릿을 만든다. 빈과 서비스를 직접 조립하는 테스트가 같은 값을 쓰도록 공개한다. */
  public static TransactionTemplate readOnly(PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setReadOnly(true);
    return template;
  }
}
