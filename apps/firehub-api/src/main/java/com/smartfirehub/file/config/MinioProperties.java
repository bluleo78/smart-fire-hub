package com.smartfirehub.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** MinIO(S3 호환) 접속·기본 버킷 설정을 담는 바인딩 객체. */
@ConfigurationProperties(prefix = "firehub.minio")
public record MinioProperties(
    String endpoint,
    // presign 서명 전용 공개 엔드포인트(브라우저가 직접 도달하는 호스트). 내부 endpoint(앱→MinIO list/stat)와
    // 분리한다. prod는 브라우저 도달 가능한 공개 호스트, dev/단일호스트는 endpoint와 동일값으로 폴백된다.
    String publicEndpoint,
    // presign 서명에 쓰이는 S3 리전. 명시하면 SDK가 리전 조회용 네트워크 호출을 생략한다 →
    // presign 클라이언트가 (앱이 못 닿을 수도 있는) 공개 호스트로 접속 시도하지 않고 로컬에서만 서명한다.
    String region,
    String accessKey,
    String secretKey,
    String bucket,
    int presignExpirySeconds,
    // 업로드용 presign 기본 만료(초). 업로드는 GET 썸네일보다 느릴 수 있어 별도 설정으로 둔다.
    int uploadPresignExpirySeconds) {}
