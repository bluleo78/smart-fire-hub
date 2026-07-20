package com.smartfirehub.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** MinIO(S3 호환) 접속·기본 버킷 설정을 담는 바인딩 객체. */
@ConfigurationProperties(prefix = "firehub.minio")
public record MinioProperties(
    String endpoint,
    String accessKey,
    String secretKey,
    String bucket,
    int presignExpirySeconds,
    // 업로드용 presign 기본 만료(초). 업로드는 GET 썸네일보다 느릴 수 있어 별도 설정으로 둔다.
    int uploadPresignExpirySeconds) {}
