package com.smartfirehub.dbexecutor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Tibero 테넌트 파이프라인 SQL 실행기(WD-77). api 가 문장을 만들어 보내면 이 서비스가 테넌트 소유 계정으로 실행만 한다. 메타 DB 에는 접속하지 않는다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DbExecutorApplication {

  public static void main(String[] args) {
    SpringApplication.run(DbExecutorApplication.class, args);
  }
}
