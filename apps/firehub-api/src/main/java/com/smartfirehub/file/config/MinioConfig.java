package com.smartfirehub.file.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MinIO 접속 클라이언트를 스프링 빈으로 제공한다. */
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

  /** 설정값(endpoint/자격증명)으로 MinioClient 싱글턴을 생성한다. */
  @Bean
  public MinioClient minioClient(MinioProperties props) {
    return MinioClient.builder()
        .endpoint(props.endpoint())
        .credentials(props.accessKey(), props.secretKey())
        .build();
  }
}
