package com.smartfirehub.file.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** MinIO 접속 클라이언트를 스프링 빈으로 제공한다. 내부 통신용/presign 서명용 2개를 분리 제공한다. */
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

  /** 내부 통신용(앱→MinIO: 목록/조회) 클라이언트. 컨테이너 네트워크 내부 endpoint로 접속한다. */
  @Bean
  @Primary
  public MinioClient minioClient(MinioProperties props) {
    return MinioClient.builder()
        .endpoint(props.endpoint())
        .region(props.region())
        .credentials(props.accessKey(), props.secretKey())
        .build();
  }

  /**
   * presigned URL 서명 전용 클라이언트. 공개 엔드포인트로 빌드하므로 서명된 host가 브라우저 도달 가능하다.
   * region을 명시해 서명 시 리전 조회 네트워크 호출을 생략한다 → 앱이 공개 host에 실제로 닿지 못해도
   * 서명이 로컬에서 완결되어 유효하다.
   */
  @Bean
  public MinioClient presignMinioClient(MinioProperties props) {
    return MinioClient.builder()
        .endpoint(props.publicEndpoint())
        .region(props.region())
        .credentials(props.accessKey(), props.secretKey())
        .build();
  }
}
