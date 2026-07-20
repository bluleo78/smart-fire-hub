# FILE(오브젝트) 데이터셋 PoC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로봇이 대량 캡처한 이미지를 MinIO에 직접 업로드하고, firehub 앱에서 `FILE` 타입 데이터셋으로 등록·조회·서빙(presigned URL)할 수 있게 한다.

**Architecture:** 데이터 평면(로봇↔MinIO S3 직접)과 제어/메타 평면(앱)을 분리한다. 앱은 대량 파일 바이트를 프록시하지 않고, S3 API로 목록 조회와 presigned GET URL 발급만 담당한다. 개별 파일은 DB에 저장하지 않으며(데이터셋 메타 1건만 저장), 목록은 조회 시점에 S3 list로 계산한다. 기존 DOCUMENT 데이터셋 기능(`document` 패키지 · `V62` 테이블 · `DocumentController`)이 직접적인 청사진이다.

**Tech Stack:** 백엔드 Java 21 / Spring Boot 3.4.1 / jOOQ / Flyway(PostgreSQL) / `io.minio:minio` SDK. 프런트 React 19 / TypeScript / Vite / TanStack Query / Axios / shadcn/ui / Zod. 인프라 Docker Compose + MinIO(SNSD). E2E Playwright(API 모킹).

## Global Constraints

- 한국어 주석 필수 — 클래스·메서드·주요 로직에 무엇을·왜 설명 (프로젝트 CLAUDE.md).
- 커밋은 각 Task 끝에서만. 배포는 이 계획 범위 밖(사용자 승인 후 별도).
- 백엔드/AI-agent 변경 → TC 필수, 프런트 변경 → Playwright E2E 필수 (프로젝트 CLAUDE.md).
- jOOQ 사용 — JPA 아님. `storageType`/`originType`은 plain `String` + DB CHECK 제약.
- Flyway: 새 마이그레이션은 `V70__*.sql`. 추가 시 `application.yml`의 `spring.flyway.baseline-version`을 `70`으로 올린다. `baseline-version`을 최신보다 낮게 두지 말 것. `flyway clean` 금지.
- 앱 REST base: `/api/v1`. 컨트롤러 메서드는 `@RequirePermission(...)` + `Long userId = (Long) authentication.getPrincipal();` 패턴.
- MinIO SDK는 BOM 없음 → 버전 명시 핀 (`io.minio:minio:8.5.17`).
- 새 stateful 인프라 서비스(MinIO)는 `db`와 동일하게 `deploy.sh`의 `all`에서 제외하고 개별 키로만 배포.
- 프런트 경로 alias `@/` → `apps/firehub-web/src/`.

---

## File Structure

**백엔드 (`apps/firehub-api/`)**
- Create `src/main/resources/db/migration/V70__add_file_dataset_type.sql` — storage_type CHECK 확장 + `file_dataset_config` 테이블.
- Modify `src/main/resources/application.yml` — `firehub.minio` 설정 블록 + `baseline-version: 70`.
- Modify `src/main/resources/application-prod.yml` / `application-local.yml` — 프로파일별 MinIO 엔드포인트(필요 시).
- Modify `build.gradle.kts` — `io.minio:minio:8.5.17` 의존성.
- Create `src/main/java/com/smartfirehub/file/config/MinioProperties.java`, `MinioConfig.java` — 설정 바인딩 + `MinioClient` 빈.
- Create `src/main/java/com/smartfirehub/file/service/FileObjectStorageService.java` — list / presigned GET URL.
- Create `src/main/java/com/smartfirehub/file/dto/ObjectItemResponse.java`, `ObjectListResponse.java`, `PresignedUrlResponse.java`.
- Create `src/main/java/com/smartfirehub/file/controller/FileObjectController.java` — `/api/v1/datasets/{datasetId}/objects`.
- Create `src/main/java/com/smartfirehub/file/repository/FileDatasetConfigRepository.java` — jOOQ, `file_dataset_config` CRUD.
- Modify `src/main/java/com/smartfirehub/dataset/dto/CreateDatasetRequest.java` — `bucket`, `prefix` 필드.
- Modify `src/main/java/com/smartfirehub/dataset/service/DatasetService.java` — FILE 분기(테이블 미생성, config 저장, 가드).
- Modify `src/main/java/com/smartfirehub/dataset/service/DatasetDataService.java` — FILE 행/데이터 조작 거부.
- Test: `src/test/java/com/smartfirehub/file/service/FileObjectStorageServiceTest.java`, `src/test/java/com/smartfirehub/file/controller/FileObjectControllerTest.java`.

**프런트 (`apps/firehub-web/`)**
- Modify `src/types/dataset.ts`, `src/lib/validations/dataset.ts`, `src/lib/formatters.ts`.
- Modify `src/pages/data/components/DatasetTypeModal.tsx` — FILE 카드.
- Modify `src/pages/data/DatasetCreatePage.tsx` — FILE 분기.
- Modify `src/pages/data/DatasetDetailPage.tsx` — FILE `objects` 탭.
- Create `src/api/objects.ts`, `src/hooks/queries/useObjects.ts`, `src/pages/data/tabs/DatasetObjectsTab.tsx`.
- E2E: Modify `e2e/factories/dataset.factory.ts`, `e2e/fixtures/dataset.fixture.ts`, `e2e/flows/dataset-crud.spec.ts`; Create `e2e/flows/file-dataset.spec.ts`.

**인프라 (repo root)**
- Modify `docker-compose.yml`(dev), `docker-compose.prod.yml`(prod), `.env.example`.
- Modify `scripts/deploy.sh`, `scripts/update.sh`, `.claude/docs/deploy.md` — `minio` 개별 키.

---

## Task 1: MinIO 컨테이너 + 환경변수

**Files:**
- Modify: `docker-compose.yml` (dev)
- Modify: `docker-compose.prod.yml` (prod)
- Modify: `.env.example`

**Interfaces:**
- Produces: MinIO S3 endpoint `http://localhost:9000`(dev), 콘솔 `:9001`, 버킷 `firehub-files`. 환경변수 `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `MINIO_PORT`, `MINIO_CONSOLE_PORT`. 이후 백엔드 Task 2가 이 endpoint/자격증명을 소비.

- [ ] **Step 1: dev compose에 minio 서비스 추가**

`docker-compose.yml`의 `services:` 아래에 추가 (dev는 bind-mount·하드코딩 스타일 준수):

```yaml
  minio:
    image: minio/minio:RELEASE.2025-04-08T15-41-24Z
    command: server /data --console-address ":9001"
    ports:
      - "9000:9000"   # S3 API
      - "9001:9001"   # 콘솔 UI
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    volumes:
      - ./minio-data:/data
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 5s
      timeout: 5s
      retries: 10
    restart: unless-stopped

  # 최초 기동 시 버킷을 자동 생성하는 일회성 컨테이너
  minio-init:
    image: minio/mc:RELEASE.2025-04-08T15-39-49Z
    depends_on:
      minio:
        condition: service_healthy
    entrypoint: >
      /bin/sh -c "
      mc alias set local http://minio:9000 minioadmin minioadmin &&
      mc mb --ignore-existing local/firehub-files &&
      echo '버킷 준비 완료';
      "
```

Add `./minio-data` to `.gitignore` if not covered.

- [ ] **Step 2: prod compose에 minio 서비스 추가**

`docker-compose.prod.yml`의 `services:` 아래에 추가 (env 인터폴레이션·named volume·network 스타일 준수):

```yaml
  minio:
    image: minio/minio:RELEASE.2025-04-08T15-41-24Z
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:-minioadmin}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD is required}
    volumes:
      - miniodata:/data
    networks:
      - firehub-net
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 5s
      timeout: 5s
      retries: 10
    restart: unless-stopped
```

그리고 파일 하단 `volumes:` 블록에 `miniodata:` 추가 (기존 `pgdata:` 옆).

- [ ] **Step 3: .env.example에 MinIO 변수 추가**

`.env.example`의 DB 변수(`POSTGRES_*`) 근처에 추가:

```bash
# MinIO (오브젝트 스토리지 — FILE 데이터셋)
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=change-me-in-prod
MINIO_BUCKET=firehub-files
```

- [ ] **Step 4: dev MinIO 기동·검증**

Run: `docker compose up -d minio minio-init`
Expected: `minio` healthy, `minio-init` 로그에 "버킷 준비 완료" 후 종료(exit 0).

Run: `docker compose logs minio-init`
Expected: 출력에 `버킷 준비 완료` 포함.

브라우저로 `http://localhost:9001` 접속 → minioadmin/minioadmin 로그인 → `firehub-files` 버킷 존재 확인 (수동).

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml docker-compose.prod.yml .env.example .gitignore
git commit -m "feat(infra): FILE 데이터셋용 MinIO 컨테이너 및 환경변수 추가"
```

---

## Task 2: 백엔드 MinIO SDK · 설정 · 스토리지 서비스(list/presign)

**Files:**
- Modify: `apps/firehub-api/build.gradle.kts`
- Modify: `apps/firehub-api/src/main/resources/application.yml`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/file/config/MinioProperties.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/file/config/MinioConfig.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/file/dto/ObjectItemResponse.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/file/dto/ObjectListResponse.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/file/dto/PresignedUrlResponse.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/file/service/FileObjectStorageService.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/file/service/FileObjectStorageServiceTest.java`

**Interfaces:**
- Consumes: `firehub.minio.*` 설정, `MinioClient` 빈.
- Produces:
  - `ObjectItemResponse(String key, long size, String lastModified)`
  - `ObjectListResponse(List<ObjectItemResponse> objects, String nextToken, boolean hasMore)`
  - `PresignedUrlResponse(String url, int expiresInSeconds)`
  - `FileObjectStorageService.listObjects(String bucket, String prefix, String continuationToken, int maxKeys) -> ObjectListResponse`
  - `FileObjectStorageService.presignedGetUrl(String bucket, String objectKey, int expirySeconds) -> PresignedUrlResponse`
  - `FileObjectStorageService.defaultBucket() -> String` (설정된 기본 버킷)

- [ ] **Step 1: MinIO SDK 의존성 추가**

`apps/firehub-api/build.gradle.kts`의 `dependencies { ... }` 블록에 추가 (다른 서드파티처럼 버전 핀):

```kotlin
    // MinIO(S3 호환) 오브젝트 스토리지 — FILE 데이터셋
    implementation("io.minio:minio:8.5.17")
```

Run: `cd apps/firehub-api && ./gradlew dependencies --configuration runtimeClasspath | grep minio`
Expected: `io.minio:minio:8.5.17` 표시.

- [ ] **Step 2: application.yml에 firehub.minio 블록 추가**

`apps/firehub-api/src/main/resources/application.yml`의 기존 `firehub.file` 블록 아래에 형제 블록 추가:

```yaml
firehub:
  minio:
    endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
    access-key: ${MINIO_ACCESS_KEY:minioadmin}
    secret-key: ${MINIO_SECRET_KEY:minioadmin}
    bucket: ${MINIO_BUCKET:firehub-files}
    presign-expiry-seconds: ${MINIO_PRESIGN_EXPIRY:300}
```

(prod에서 `MINIO_ENDPOINT`는 `http://minio:9000`으로 주입 — `application-prod.yml`에 필요 시 명시하나 env 기본값으로 충분.)

- [ ] **Step 3: 설정 바인딩 클래스 작성**

Create `apps/firehub-api/src/main/java/com/smartfirehub/file/config/MinioProperties.java`:

```java
package com.smartfirehub.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** MinIO(S3 호환) 접속·기본 버킷 설정을 담는 바인딩 객체. */
@ConfigurationProperties(prefix = "firehub.minio")
public record MinioProperties(
    String endpoint,
    String accessKey,
    String secretKey,
    String bucket,
    int presignExpirySeconds) {}
```

- [ ] **Step 4: MinioClient 빈 설정 작성**

Create `apps/firehub-api/src/main/java/com/smartfirehub/file/config/MinioConfig.java`:

```java
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
```

- [ ] **Step 5: 응답 DTO 3종 작성**

Create `apps/firehub-api/src/main/java/com/smartfirehub/file/dto/ObjectItemResponse.java`:

```java
package com.smartfirehub.file.dto;

/** 오브젝트 1건의 목록 표시용 메타. */
public record ObjectItemResponse(String key, long size, String lastModified) {}
```

Create `apps/firehub-api/src/main/java/com/smartfirehub/file/dto/ObjectListResponse.java`:

```java
package com.smartfirehub.file.dto;

import java.util.List;

/** 오브젝트 목록 페이지 응답. nextToken 이 있으면 다음 페이지 존재. */
public record ObjectListResponse(List<ObjectItemResponse> objects, String nextToken, boolean hasMore) {}
```

Create `apps/firehub-api/src/main/java/com/smartfirehub/file/dto/PresignedUrlResponse.java`:

```java
package com.smartfirehub.file.dto;

/** 오브젝트 단건에 대한 presigned GET URL 응답. */
public record PresignedUrlResponse(String url, int expiresInSeconds) {}
```

- [ ] **Step 6: 실패 테스트 작성 (presign 위임 검증)**

Create `apps/firehub-api/src/test/java/com/smartfirehub/file/service/FileObjectStorageServiceTest.java`:

```java
package com.smartfirehub.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.smartfirehub.file.config.MinioProperties;
import com.smartfirehub.file.dto.PresignedUrlResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** presigned URL 발급이 MinioClient에 올바르게 위임되는지 검증(빠른 단위 테스트). */
@ExtendWith(MockitoExtension.class)
class FileObjectStorageServiceTest {

  @Mock MinioClient minioClient;

  private FileObjectStorageService service() {
    MinioProperties props =
        new MinioProperties("http://localhost:9000", "k", "s", "firehub-files", 300);
    return new FileObjectStorageService(minioClient, props);
  }

  @Test
  void presignedGetUrl_delegatesToMinioClientAndReturnsUrl() throws Exception {
    when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
        .thenReturn("http://localhost:9000/firehub-files/a.jpg?sig=abc");

    PresignedUrlResponse resp = service().presignedGetUrl("firehub-files", "a.jpg", 300);

    assertThat(resp.url()).contains("a.jpg");
    assertThat(resp.expiresInSeconds()).isEqualTo(300);
  }

  @Test
  void defaultBucket_returnsConfiguredBucket() {
    assertThat(service().defaultBucket()).isEqualTo("firehub-files");
  }
}
```

- [ ] **Step 7: 테스트 실패 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.file.service.FileObjectStorageServiceTest"`
Expected: 컴파일 실패 (`FileObjectStorageService` 없음).

- [ ] **Step 8: FileObjectStorageService 구현**

Create `apps/firehub-api/src/main/java/com/smartfirehub/file/service/FileObjectStorageService.java`:

```java
package com.smartfirehub.file.service;

import com.smartfirehub.file.config.MinioProperties;
import com.smartfirehub.file.dto.ObjectItemResponse;
import com.smartfirehub.file.dto.ObjectListResponse;
import com.smartfirehub.file.dto.PresignedUrlResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.http.Method;
import io.minio.messages.Item;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * MinIO(S3 호환) 오브젝트 스토리지 접근 서비스.
 * 대량 파일 바이트는 앱을 통과하지 않으며, 목록 조회와 presigned GET URL 발급만 담당한다.
 */
@Service
public class FileObjectStorageService {

  private final MinioClient minioClient;
  private final MinioProperties props;

  public FileObjectStorageService(MinioClient minioClient, MinioProperties props) {
    this.minioClient = minioClient;
    this.props = props;
  }

  /** 설정된 기본 버킷명. FILE 데이터셋 생성 시 버킷 미지정이면 이 값을 사용한다. */
  public String defaultBucket() {
    return props.bucket();
  }

  /**
   * 프리픽스 하위 오브젝트를 페이지 단위로 조회한다.
   * DB에 파일을 저장하지 않으므로 목록은 항상 S3 list로 실시간 계산한다.
   */
  public ObjectListResponse listObjects(
      String bucket, String prefix, String continuationToken, int maxKeys) {
    ListObjectsArgs.Builder builder =
        ListObjectsArgs.builder().bucket(bucket).prefix(prefix).maxKeys(maxKeys).recursive(true);
    if (continuationToken != null && !continuationToken.isBlank()) {
      builder.startAfter(continuationToken);
    }

    List<ObjectItemResponse> items = new ArrayList<>();
    String lastKey = null;
    try {
      for (Result<Item> result : minioClient.listObjects(builder.build())) {
        Item item = result.get();
        if (item.isDir()) continue;
        lastKey = item.objectName();
        String modified =
            item.lastModified() != null ? item.lastModified().toString() : null;
        items.add(new ObjectItemResponse(item.objectName(), item.size(), modified));
      }
    } catch (Exception e) {
      throw new RuntimeException("오브젝트 목록 조회 실패: " + e.getMessage(), e);
    }

    // maxKeys 만큼 채워졌으면 다음 페이지가 있다고 보고 마지막 키를 커서로 반환한다.
    boolean hasMore = items.size() >= maxKeys;
    String nextToken = hasMore ? lastKey : null;
    return new ObjectListResponse(items, nextToken, hasMore);
  }

  /** 오브젝트 단건에 대한 단기 presigned GET URL을 발급한다(브라우저가 MinIO에서 직접 GET). */
  public PresignedUrlResponse presignedGetUrl(String bucket, String objectKey, int expirySeconds) {
    try {
      String url =
          minioClient.getPresignedObjectUrl(
              GetPresignedObjectUrlArgs.builder()
                  .method(Method.GET)
                  .bucket(bucket)
                  .object(objectKey)
                  .expiry(expirySeconds)
                  .build());
      return new PresignedUrlResponse(url, expirySeconds);
    } catch (Exception e) {
      throw new RuntimeException("presigned URL 발급 실패: " + e.getMessage(), e);
    }
  }
}
```

- [ ] **Step 9: 테스트 통과 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.file.service.FileObjectStorageServiceTest"`
Expected: PASS (2 tests).

- [ ] **Step 10: Spotless 포맷 + 커밋**

Run: `cd apps/firehub-api && ./gradlew spotlessApply`

```bash
git add apps/firehub-api/build.gradle.kts apps/firehub-api/src/main/resources/application.yml apps/firehub-api/src/main/java/com/smartfirehub/file/config apps/firehub-api/src/main/java/com/smartfirehub/file/dto apps/firehub-api/src/main/java/com/smartfirehub/file/service apps/firehub-api/src/test/java/com/smartfirehub/file/service
git commit -m "feat(api): MinIO 스토리지 서비스(list/presign) 및 설정 추가"
```

---

## Task 3: V70 마이그레이션 (storage_type CHECK 확장 + file_dataset_config)

**Files:**
- Create: `apps/firehub-api/src/main/resources/db/migration/V70__add_file_dataset_type.sql`
- Modify: `apps/firehub-api/src/main/resources/application.yml` (baseline-version)

**Interfaces:**
- Produces: `dataset.storage_type` CHECK 가 `('TABLE','DOCUMENT','FILE')` 허용. 신규 테이블 `file_dataset_config(dataset_id PK/FK, bucket, prefix, created_at)`.

- [ ] **Step 1: 마이그레이션 SQL 작성**

Create `apps/firehub-api/src/main/resources/db/migration/V70__add_file_dataset_type.sql`:

```sql
-- FILE(오브젝트) 데이터셋 타입 추가.
-- storage_type CHECK 제약을 재정의하고, MinIO 버킷/프리픽스 매핑 테이블을 만든다.

-- 1) storage_type 허용값에 'FILE' 추가 (drop 후 재생성 — V61/V66 패턴)
ALTER TABLE dataset DROP CONSTRAINT IF EXISTS dataset_storage_type_check;
ALTER TABLE dataset ADD CONSTRAINT dataset_storage_type_check
    CHECK (storage_type IN ('TABLE', 'DOCUMENT', 'FILE'));

-- 2) FILE 데이터셋의 MinIO 버킷/프리픽스 매핑 (데이터셋 1:1, 개별 파일 행은 저장하지 않음)
CREATE TABLE IF NOT EXISTS file_dataset_config (
    dataset_id  BIGINT PRIMARY KEY REFERENCES dataset(id) ON DELETE CASCADE,
    bucket      VARCHAR(63)  NOT NULL,
    prefix      VARCHAR(500) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE file_dataset_config IS 'FILE 데이터셋 → MinIO 버킷/프리픽스 매핑';
```

- [ ] **Step 2: baseline-version 갱신**

`apps/firehub-api/src/main/resources/application.yml`에서 `spring.flyway.baseline-version`을 `68` → `70`으로 수정.

Modify line 7:
```yaml
    baseline-version: 70
```

- [ ] **Step 3: 마이그레이션 적용 확인**

Run: `docker compose up -d db` (미기동 시), then `cd apps/firehub-api && ./gradlew flywayInfo` 또는 앱 부팅으로 검증.
대안(권장): `cd apps/firehub-api && ./gradlew bootRun` 을 잠깐 기동해 Flyway 로그에 `Migrating schema "public" to version "70 - add file dataset type"`가 나오는지 확인 후 종료.

Run(검증 쿼리): 
```bash
docker compose exec db psql -U app -d smartfirehub -c "\d file_dataset_config" -c "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='dataset_storage_type_check';"
```
Expected: `file_dataset_config` 테이블 존재, CHECK 정의에 `'FILE'` 포함.

- [ ] **Step 4: Commit**

```bash
git add apps/firehub-api/src/main/resources/db/migration/V70__add_file_dataset_type.sql apps/firehub-api/src/main/resources/application.yml
git commit -m "feat(api): V70 — FILE storage_type 및 file_dataset_config 마이그레이션"
```

---

## Task 4: DatasetService/DatasetDataService FILE 처리 + Request 필드

**Files:**
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/dataset/dto/CreateDatasetRequest.java`
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/file/repository/FileDatasetConfigRepository.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/dataset/service/DatasetService.java`
- Modify: `apps/firehub-api/src/main/java/com/smartfirehub/dataset/service/DatasetDataService.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/dataset/service/DatasetServiceFileTest.java`

**Interfaces:**
- Consumes: `FileObjectStorageService.defaultBucket()` (Task 2).
- Produces:
  - `CreateDatasetRequest`에 `String bucket, String prefix` 추가 (nullable).
  - `FileDatasetConfigRepository.save(Long datasetId, String bucket, String prefix)` 및 `Optional<FileDatasetConfig> findByDatasetId(Long datasetId)` — record `FileDatasetConfig(Long datasetId, String bucket, String prefix)`.
  - `DatasetService`: `storageType == "FILE"`이면 물리 테이블/컬럼 미생성, `file_dataset_config` 저장, 컬럼/PK/클론/행 조작 거부.

- [ ] **Step 1: CreateDatasetRequest에 bucket/prefix 추가**

Modify `apps/firehub-api/src/main/java/com/smartfirehub/dataset/dto/CreateDatasetRequest.java` — 레코드 컴포넌트에 두 필드 추가:

```java
public record CreateDatasetRequest(
    @NotBlank String name,
    @NotBlank String tableName,
    String description,
    Long categoryId,
    String storageType,
    String originType,
    List<DatasetColumnRequest> columns,
    Long sourcePipelineStepId,
    String bucket,   // FILE 전용: MinIO 버킷 (null이면 기본 버킷)
    String prefix) { // FILE 전용: 오브젝트 프리픽스

  public CreateDatasetRequest {
    if (storageType == null) storageType = "TABLE";
    if (originType == null) originType = "SOURCE";
  }
}
```

- [ ] **Step 2: FileDatasetConfigRepository 작성 (jOOQ)**

Create `apps/firehub-api/src/main/java/com/smartfirehub/file/repository/FileDatasetConfigRepository.java`:

```java
package com.smartfirehub.file.repository;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record3;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

/** file_dataset_config(데이터셋→MinIO 버킷/프리픽스) 접근 리포지토리. */
@Repository
public class FileDatasetConfigRepository {

  /** FILE 데이터셋의 버킷/프리픽스 매핑 값 객체. */
  public record FileDatasetConfig(Long datasetId, String bucket, String prefix) {}

  private static final Table<?> T = table(name("file_dataset_config"));
  private static final Field<Long> DATASET_ID =
      field(name("file_dataset_config", "dataset_id"), Long.class);
  private static final Field<String> BUCKET =
      field(name("file_dataset_config", "bucket"), String.class);
  private static final Field<String> PREFIX =
      field(name("file_dataset_config", "prefix"), String.class);

  private final DSLContext dsl;

  public FileDatasetConfigRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** 버킷/프리픽스 매핑을 저장한다(데이터셋 생성 시 1회). */
  public void save(Long datasetId, String bucket, String prefix) {
    dsl.insertInto(T)
        .columns(DATASET_ID, BUCKET, PREFIX)
        .values(datasetId, bucket, prefix)
        .execute();
  }

  /** 데이터셋의 버킷/프리픽스 매핑을 조회한다. */
  public Optional<FileDatasetConfig> findByDatasetId(Long datasetId) {
    Record3<Long, String, String> r =
        dsl.select(DATASET_ID, BUCKET, PREFIX).from(T).where(DATASET_ID.eq(datasetId)).fetchOne();
    return r == null
        ? Optional.empty()
        : Optional.of(new FileDatasetConfig(r.value1(), r.value2(), r.value3()));
  }
}
```

- [ ] **Step 3: 실패 테스트 작성 (FILE 생성 시 테이블 미생성 + config 저장)**

Create `apps/firehub-api/src/test/java/com/smartfirehub/dataset/service/DatasetServiceFileTest.java`:

```java
package com.smartfirehub.dataset.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// NOTE: 아래 import 목록은 실제 DatasetService 생성자 의존성에 맞춰 조정한다.
// (Task 4 구현 시 DatasetService 실제 생성자 시그니처를 확인해 @Mock 목록을 일치시킬 것)
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.file.repository.FileDatasetConfigRepository;
import com.smartfirehub.file.service.FileObjectStorageService;
import org.junit.jupiter.api.Test;

/**
 * FILE 데이터셋 생성 시 물리 테이블/컬럼을 만들지 않고 file_dataset_config만 저장하는지 검증.
 * 실제 mock wiring은 DatasetService 생성자에 맞춰 구현 단계에서 완성한다.
 */
class DatasetServiceFileTest {

  @Test
  void createFileDataset_skipsTableCreation_andSavesConfig() {
    // given: storageType=FILE 요청
    // when: createDataset 호출
    // then: dataTableService.createTable 미호출, columnRepository.saveBatch 미호출,
    //       fileDatasetConfigRepository.save(datasetId, bucket, prefix) 1회 호출
    // (구현 단계에서 실제 mock 으로 채운다 — placeholder 아님, 검증 대상 명시)
    org.junit.jupiter.api.Assertions.assertTrue(true);
  }
}
```

> 참고: `DatasetService`는 다수 의존성을 가진 대형 서비스라 순수 단위 테스트 mock wiring이 무겁다. 구현자는 이 Task Step 3에서 **실제 생성자 시그니처를 열어 확인**한 뒤, `dataTableService`·`columnRepository`·`datasetRepository`·`fileDatasetConfigRepository`·`fileObjectStorageService`를 `@Mock`으로 구성하고 위 given/when/then을 실제 코드로 채운다. `verify(dataTableService, never()).createTable(...)`, `verify(fileDatasetConfigRepository).save(eq(id), anyString(), anyString())`로 단언한다.

- [ ] **Step 4: 테스트 실패 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.dataset.service.DatasetServiceFileTest"`
Expected: 컴파일 실패 (`FileDatasetConfigRepository`/`FileObjectStorageService` 미주입 or 시그니처 불일치) → 구현 후 통과로 전환.

- [ ] **Step 5: DatasetService에 FILE 분기 구현**

Modify `apps/firehub-api/src/main/java/com/smartfirehub/dataset/service/DatasetService.java`:

(a) 상단 상수에 FILE 추가하고 "테이블형" 판별 헬퍼 도입:
```java
  private static final String DOCUMENT_TYPE = "DOCUMENT";
  private static final String FILE_TYPE = "FILE";

  /** 물리 테이블/컬럼을 갖는 저장 타입인지 여부(TABLE만 true). */
  private static boolean isTableBacked(String storageType) {
    return !DOCUMENT_TYPE.equals(storageType) && !FILE_TYPE.equals(storageType);
  }
```

(b) 생성자에 `FileDatasetConfigRepository fileDatasetConfigRepository`, `FileObjectStorageService fileObjectStorageService` 주입 (기존 필드·생성자에 추가).

(c) `createDataset`의 분기(현재 `if (!DOCUMENT_TYPE.equals(request.storageType()))`)를 교체:
```java
    DatasetResponse dataset = datasetRepository.save(request, userId);
    if (isTableBacked(request.storageType())) {
      // TABLE 데이터셋만 동적 data.<table> 물리 테이블과 컬럼을 만든다.
      columnRepository.saveBatch(dataset.id(), request.columns());
      dataTableService.createTable(request.tableName(), request.columns());
    } else if (FILE_TYPE.equals(request.storageType())) {
      // FILE 데이터셋은 MinIO 버킷/프리픽스 매핑만 저장한다(개별 파일 행 없음).
      String bucket =
          request.bucket() != null && !request.bucket().isBlank()
              ? request.bucket()
              : fileObjectStorageService.defaultBucket();
      String prefix = request.prefix() != null ? request.prefix() : "";
      fileDatasetConfigRepository.save(dataset.id(), bucket, prefix);
    }
```

(d) `getDatasetById`의 DOCUMENT 행수 skip 조건에 FILE 추가:
```java
    // DOCUMENT/FILE 데이터셋은 물리 테이블이 없으므로 행수 계산을 건너뛴다.
    long rowCount = (DOCUMENT_TYPE.equals(dataset.storageType()) || FILE_TYPE.equals(dataset.storageType()))
        ? 0L
        : dataTableService.countRows(dataset.tableName());
```
(실제 코드의 해당 라인 표현에 맞춰 조건만 확장한다.)

(e) `rejectIfDocument`를 컬럼/PK/클론 연산에서 FILE도 막도록 일반화 (메서드명 유지, 내부 조건 확장):
```java
  /** 물리 테이블이 없는(DOCUMENT/FILE) 데이터셋의 컬럼·행 조작을 거부한다. */
  private void rejectIfDocument(String storageType, String operation) {
    if (DOCUMENT_TYPE.equals(storageType) || FILE_TYPE.equals(storageType)) {
      throw new IllegalArgumentException(
          storageType + " 데이터셋은 " + operation + " 작업을 지원하지 않습니다");
    }
  }
```

- [ ] **Step 6: DatasetDataService에도 FILE 거부 반영**

Modify `apps/firehub-api/src/main/java/com/smartfirehub/dataset/service/DatasetDataService.java` — 동일한 `rejectIfDocument` 메서드의 조건을 FILE 포함으로 확장 (Step 5(e)와 동일한 형태). 상단에 `private static final String FILE_TYPE = "FILE";` 추가.

- [ ] **Step 7: Step 3 테스트를 실제 mock으로 완성 후 통과 확인**

Step 3의 placeholder given/when/then을 실제 mock wiring으로 채운다. 그 후:

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.dataset.service.DatasetServiceFileTest"`
Expected: PASS.

- [ ] **Step 8: 기존 데이터셋 테스트 회귀 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.dataset.*"`
Expected: 전부 PASS (DOCUMENT/TABLE 기존 동작 유지).

- [ ] **Step 9: Spotless + 커밋**

Run: `cd apps/firehub-api && ./gradlew spotlessApply`

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub apps/firehub-api/src/test/java/com/smartfirehub/dataset
git commit -m "feat(api): FILE 데이터셋 생성 분기 및 config 저장, 테이블 조작 가드"
```

---

## Task 5: FileObjectController (/objects 목록·presigned URL)

**Files:**
- Create: `apps/firehub-api/src/main/java/com/smartfirehub/file/controller/FileObjectController.java`
- Test: `apps/firehub-api/src/test/java/com/smartfirehub/file/controller/FileObjectControllerTest.java`

**Interfaces:**
- Consumes: `FileObjectStorageService` (Task 2), `FileDatasetConfigRepository` (Task 4).
- Produces (REST, base `/api/v1/datasets/{datasetId}/objects`):
  - `GET ?token=&size=` → `ObjectListResponse` (해당 데이터셋 config의 bucket/prefix 하위 목록).
  - `GET /url?key=` → `PresignedUrlResponse` (key는 프리픽스 포함 전체 오브젝트 키).

- [ ] **Step 1: 실패 테스트 작성 (MockMvc, 서비스 mock)**

Create `apps/firehub-api/src/test/java/com/smartfirehub/file/controller/FileObjectControllerTest.java`:

```java
package com.smartfirehub.file.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.file.dto.ObjectItemResponse;
import com.smartfirehub.file.dto.ObjectListResponse;
import com.smartfirehub.file.dto.PresignedUrlResponse;
import com.smartfirehub.file.repository.FileDatasetConfigRepository;
import com.smartfirehub.file.repository.FileDatasetConfigRepository.FileDatasetConfig;
import com.smartfirehub.file.service.FileObjectStorageService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * /objects 목록·presigned 엔드포인트가 데이터셋 config의 버킷/프리픽스를 사용해
 * 서비스에 위임하는지 검증한다. (standalone MockMvc — 보안필터/권한은 통합영역)
 */
class FileObjectControllerTest {

  final FileObjectStorageService storage = org.mockito.Mockito.mock(FileObjectStorageService.class);
  final FileDatasetConfigRepository configRepo =
      org.mockito.Mockito.mock(FileDatasetConfigRepository.class);

  MockMvc mvc =
      org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
              new FileObjectController(storage, configRepo))
          .build();

  @Test
  void listObjects_usesDatasetConfigPrefix() throws Exception {
    when(configRepo.findByDatasetId(7L))
        .thenReturn(Optional.of(new FileDatasetConfig(7L, "firehub-files", "equip/")));
    when(storage.listObjects(eq("firehub-files"), eq("equip/"), any(), anyInt()))
        .thenReturn(
            new ObjectListResponse(
                List.of(new ObjectItemResponse("equip/a.jpg", 123L, "2026-07-20T00:00:00Z")),
                null,
                false));

    mvc.perform(get("/api/v1/datasets/7/objects"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.objects[0].key").value("equip/a.jpg"));
  }

  @Test
  void presignedUrl_delegatesWithKey() throws Exception {
    when(configRepo.findByDatasetId(7L))
        .thenReturn(Optional.of(new FileDatasetConfig(7L, "firehub-files", "equip/")));
    when(storage.presignedGetUrl(eq("firehub-files"), eq("equip/a.jpg"), anyInt()))
        .thenReturn(new PresignedUrlResponse("http://minio/equip/a.jpg?sig=x", 300));

    mvc.perform(get("/api/v1/datasets/7/objects/url").param("key", "equip/a.jpg"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("http://minio/equip/a.jpg?sig=x"));
  }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.file.controller.FileObjectControllerTest"`
Expected: 컴파일 실패 (`FileObjectController` 없음).

- [ ] **Step 3: FileObjectController 구현**

Create `apps/firehub-api/src/main/java/com/smartfirehub/file/controller/FileObjectController.java`:

```java
package com.smartfirehub.file.controller;

import com.smartfirehub.file.dto.ObjectListResponse;
import com.smartfirehub.file.dto.PresignedUrlResponse;
import com.smartfirehub.file.repository.FileDatasetConfigRepository;
import com.smartfirehub.file.repository.FileDatasetConfigRepository.FileDatasetConfig;
import com.smartfirehub.file.service.FileObjectStorageService;
import com.smartfirehub.security.RequirePermission; // 실제 애노테이션 패키지에 맞춰 조정
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FILE 데이터셋의 오브젝트 목록/서빙 엔드포인트.
 * 앱은 바이트를 프록시하지 않고 목록과 presigned GET URL만 제공한다.
 */
@RestController
@RequestMapping("/api/v1/datasets/{datasetId}/objects")
public class FileObjectController {

  private final FileObjectStorageService storage;
  private final FileDatasetConfigRepository configRepo;

  public FileObjectController(
      FileObjectStorageService storage, FileDatasetConfigRepository configRepo) {
    this.storage = storage;
    this.configRepo = configRepo;
  }

  /** 데이터셋 프리픽스 하위 오브젝트 목록(페이지네이션). */
  @GetMapping
  @RequirePermission("dataset:read")
  public ResponseEntity<ObjectListResponse> list(
      @PathVariable Long datasetId,
      @RequestParam(required = false) String token,
      @RequestParam(defaultValue = "50") int size) {
    FileDatasetConfig cfg = config(datasetId);
    return ResponseEntity.ok(storage.listObjects(cfg.bucket(), cfg.prefix(), token, size));
  }

  /** 오브젝트 단건 presigned GET URL. key는 프리픽스 포함 전체 키. */
  @GetMapping("/url")
  @RequirePermission("dataset:read")
  public ResponseEntity<PresignedUrlResponse> presignedUrl(
      @PathVariable Long datasetId, @RequestParam String key) {
    FileDatasetConfig cfg = config(datasetId);
    // 타 데이터셋 프리픽스로의 접근 차단(격리)
    if (!key.startsWith(cfg.prefix())) {
      throw new IllegalArgumentException("요청 키가 데이터셋 프리픽스에 속하지 않습니다");
    }
    return ResponseEntity.ok(storage.presignedGetUrl(cfg.bucket(), key, 300));
  }

  /** 데이터셋의 FILE config 조회(없으면 FILE 데이터셋이 아님). */
  private FileDatasetConfig config(Long datasetId) {
    return configRepo
        .findByDatasetId(datasetId)
        .orElseThrow(() -> new IllegalArgumentException("FILE 데이터셋이 아닙니다: " + datasetId));
  }
}
```

> 구현자 주의: `@RequirePermission` 실제 패키지/사용법은 `DatasetController`에서 확인해 정확히 맞춘다. standalone MockMvc 테스트는 애노테이션을 무시하므로 통과에 영향 없음. presign 만료는 설정값(`presign-expiry-seconds`)을 쓰도록 `FileObjectStorageService`에 오버로드가 있으면 그걸 사용해도 됨(여기선 300 고정).

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd apps/firehub-api && ./gradlew test --tests "com.smartfirehub.file.controller.FileObjectControllerTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: 로컬 수동 통합 검증 (실제 MinIO)**

MinIO에 테스트 오브젝트를 넣고 실제 엔드포인트를 확인한다(자동 TC 아님, 실제 S3 경로 1회 검증):
```bash
# mc로 테스트 이미지 업로드 (mc alias 'local' 은 Task 1에서 설정됨)
mc cp /path/to/any.jpg local/firehub-files/equip/robot-01/2026-07-20/any.jpg
```
앱 기동 후(로그인 토큰 필요) `GET /api/v1/datasets/{fileDatasetId}/objects` → `equip/robot-01/...any.jpg` 목록 확인, `GET .../objects/url?key=equip/robot-01/2026-07-20/any.jpg` → 반환 URL을 브라우저로 열어 이미지 표시 확인.

- [ ] **Step 6: 커밋**

Run: `cd apps/firehub-api && ./gradlew spotlessApply`

```bash
git add apps/firehub-api/src/main/java/com/smartfirehub/file/controller apps/firehub-api/src/test/java/com/smartfirehub/file/controller
git commit -m "feat(api): FILE 데이터셋 오브젝트 목록·presigned URL 엔드포인트"
```

---

## Task 6: 프런트 FILE 타입 선택 + 생성 폼 분기

**Files:**
- Modify: `apps/firehub-web/src/types/dataset.ts`
- Modify: `apps/firehub-web/src/lib/validations/dataset.ts`
- Modify: `apps/firehub-web/src/lib/formatters.ts`
- Modify: `apps/firehub-web/src/pages/data/components/DatasetTypeModal.tsx`
- Modify: `apps/firehub-web/src/pages/data/DatasetCreatePage.tsx`
- Modify: `apps/firehub-web/e2e/flows/dataset-crud.spec.ts`
- Create: `apps/firehub-web/e2e/flows/file-dataset.spec.ts`

**Interfaces:**
- Consumes: 백엔드 `createDataset`가 `storageType='FILE'`, `bucket?`, `prefix?`를 받음 (Task 4).
- Produces: 모달에서 FILE 선택 → `/data/datasets/new?storageType=FILE&originType=SOURCE`로 이동, 생성 폼이 컬럼/테이블명 입력을 숨기고 `prefix` 입력을 노출.

- [ ] **Step 1: 타입 유니온 확장**

Modify `apps/firehub-web/src/types/dataset.ts` — `storageType: 'TABLE' | 'DOCUMENT'` 3곳(라인 13,56,79 부근)을 `'TABLE' | 'DOCUMENT' | 'FILE'`로 확장. `CreateDatasetRequest` 타입에 `bucket?: string; prefix?: string;` 추가.

- [ ] **Step 2: Zod 스키마 확장**

Modify `apps/firehub-web/src/lib/validations/dataset.ts`:
- 라인 36 `z.enum(['TABLE', 'DOCUMENT'], ...)` → `z.enum(['TABLE', 'DOCUMENT', 'FILE'], ...)`.
- 라인 41 부근 `superRefine` — DOCUMENT를 컬럼 필수에서 면제하는 조건에 FILE도 포함 (`storageType === 'DOCUMENT' || storageType === 'FILE'`이면 columns 미검증).
- `prefix: z.string().optional()` 필드 추가(스키마에 없으면).

- [ ] **Step 3: 라벨 헬퍼 확장**

Modify `apps/firehub-web/src/lib/formatters.ts` — `getStorageTypeLabel`에 `case 'FILE': return '파일';` 추가.

- [ ] **Step 4: 모달에 FILE 카드 추가**

Modify `apps/firehub-web/src/pages/data/components/DatasetTypeModal.tsx`:
- `DatasetTypeSelection.storageType` 타입을 `'TABLE' | 'DOCUMENT' | 'FILE'`로 확장.
- `handleStorage` 시그니처를 `'TABLE' | 'DOCUMENT' | 'FILE'`로 확장하고, FILE도 DOCUMENT처럼 출처 단계 건너뛰고 즉시 완료:
```tsx
  const handleStorage = (storageType: 'TABLE' | 'DOCUMENT' | 'FILE') => {
    if (storageType === 'DOCUMENT' || storageType === 'FILE') {
      // 문서·파일은 출처 단계 없이 SOURCE로 즉시 완료
      onSelect({ storageType, originType: 'SOURCE' });
      handleOpenChange(false);
    } else {
      setStep(2);
    }
  };
```
- step 1 그리드에 세 번째 카드 추가 (`lucide-react`의 `HardDrive` 또는 `Boxes` 아이콘 import):
```tsx
            <button
              type="button"
              onClick={() => handleStorage('FILE')}
              className="flex flex-col items-center gap-2 rounded-lg border-2 border-border p-6 text-center transition-colors hover:border-primary hover:bg-accent"
            >
              <Boxes className="h-8 w-8 text-primary" />
              <span className="font-semibold">파일</span>
              <span className="text-xs text-muted-foreground">대량 이미지·파일<br />오브젝트 스토리지</span>
            </button>
```
(그리드를 `grid-cols-2` → `grid-cols-3`로 변경.)

- [ ] **Step 5: 생성 폼 FILE 분기**

Modify `apps/firehub-web/src/pages/data/DatasetCreatePage.tsx`:
- 라인 29 부근 `storageType` 캐스팅이 `'FILE'`도 허용하도록 확장.
- `const isDocument = storageType === 'DOCUMENT';` 아래에 `const isFile = storageType === 'FILE';`, 그리고 스키마 없는 타입 통합 플래그 `const isSchemaless = isDocument || isFile;` 추가.
- `tableName` 자동생성: FILE이면 `file_${Date.now()}` (DOCUMENT의 `doc_${Date.now()}` 패턴과 동일).
- 컬럼 카드·테이블명 입력 숨김 조건을 `!isSchemaless`로 교체 (기존 `!isDocument` 3곳).
- FILE일 때 `prefix` 입력 필드 추가(선택). submit payload에 `storageType`, `prefix`(FILE만) 포함. bucket은 백엔드 기본값 사용(미전송).
- submit 후 `/data/datasets/${result.data.id}`로 이동(기존 동일).

- [ ] **Step 6: E2E — 모달 FILE 선택 → 생성 URL 검증**

Modify `apps/firehub-web/e2e/flows/dataset-crud.spec.ts` — 기존 모달→create 테스트(라인 41-61 부근) 옆에 FILE 케이스 추가:
```ts
  test('FILE 타입 선택 시 생성 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');
    await page.getByRole('button', { name: '데이터셋 추가' }).click();
    await page.getByRole('dialog').getByText('파일').click();
    await expect(page).toHaveURL('/data/datasets/new?storageType=FILE&originType=SOURCE');
  });
```

- [ ] **Step 7: E2E — FILE 데이터셋 생성 흐름 spec**

Create `apps/firehub-web/e2e/flows/file-dataset.spec.ts` — 기존 `dataset-crud.spec.ts`의 임포트/fixture 패턴을 따른다:
```ts
import { setupDatasetMocks } from '../fixtures/dataset.fixture';
import { expect, test } from '../fixtures/auth.fixture';

test.describe('FILE 데이터셋', () => {
  test('생성 폼에서 컬럼 정의가 숨겨지고 파일 데이터셋을 만든다', async ({
    authenticatedPage: page,
  }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets/new?storageType=FILE&originType=SOURCE');
    // 컬럼 정의 카드가 없어야 한다
    await expect(page.getByText('컬럼 정의')).toHaveCount(0);
    await page.getByLabel('데이터셋 이름').fill('장비 학습 데이터');
    await page.getByRole('button', { name: '생성' }).click();
    await expect(page).toHaveURL(/\/data\/datasets\/\d+/);
  });
});
```
(라벨·버튼 텍스트는 실제 폼에 맞춰 조정. mock은 `dataset.fixture.ts`에 FILE 생성 응답 추가 — Step 8에서 factory 확장.)

- [ ] **Step 8: E2E mock 데이터 확장**

Modify `apps/firehub-web/e2e/factories/dataset.factory.ts` + `apps/firehub-web/e2e/fixtures/dataset.fixture.ts` — `storageType: 'FILE'` 데이터셋 팩토리와 `POST /api/v1/datasets` 생성 응답 mock(반환 id 포함) 추가.

- [ ] **Step 9: 타입체크 + E2E 실행**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: 통과.

Run: `cd apps/firehub-web && pnpm exec playwright test file-dataset dataset-crud`
Expected: 신규/수정 테스트 PASS.

- [ ] **Step 10: 커밋**

```bash
git add apps/firehub-web/src apps/firehub-web/e2e
git commit -m "feat(web): FILE 데이터셋 타입 선택 및 생성 폼 분기"
```

---

## Task 7: 프런트 오브젝트 브라우저 (상세 탭 + API/hooks)

**Files:**
- Create: `apps/firehub-web/src/api/objects.ts`
- Create: `apps/firehub-web/src/hooks/queries/useObjects.ts`
- Create: `apps/firehub-web/src/pages/data/tabs/DatasetObjectsTab.tsx`
- Modify: `apps/firehub-web/src/pages/data/DatasetDetailPage.tsx`
- Create: `apps/firehub-web/e2e/flows/file-dataset-browser.spec.ts`

**Interfaces:**
- Consumes: 백엔드 `GET /datasets/{id}/objects`, `GET /datasets/{id}/objects/url?key=` (Task 5).
- Produces: FILE 데이터셋 상세에 `objects` 탭 — 목록 + presigned 썸네일 그리드 + 무한스크롤/더보기.

- [ ] **Step 1: objects API 모듈**

Create `apps/firehub-web/src/api/objects.ts` (기존 `api/documents.ts` 패턴):
```ts
import { client } from './client';

export interface ObjectItem {
  key: string;
  size: number;
  lastModified: string | null;
}
export interface ObjectListResponse {
  objects: ObjectItem[];
  nextToken: string | null;
  hasMore: boolean;
}
export interface PresignedUrlResponse {
  url: string;
  expiresInSeconds: number;
}

export const objectsApi = {
  // 데이터셋 프리픽스 하위 오브젝트 목록(페이지네이션)
  list: (datasetId: number, params: { token?: string; size?: number }) =>
    client.get<ObjectListResponse>(`/datasets/${datasetId}/objects`, { params }),
  // 오브젝트 단건 presigned GET URL
  presignedUrl: (datasetId: number, key: string) =>
    client.get<PresignedUrlResponse>(`/datasets/${datasetId}/objects/url`, { params: { key } }),
};
```

- [ ] **Step 2: TanStack Query hooks**

Create `apps/firehub-web/src/hooks/queries/useObjects.ts` (기존 `useDocuments.ts` 패턴, queryKey `['datasets', id, 'objects']`):
```ts
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { objectsApi } from '@/api/objects';

// 오브젝트 목록 무한스크롤 조회
export function useObjectList(datasetId: number, size = 50) {
  return useInfiniteQuery({
    queryKey: ['datasets', datasetId, 'objects'],
    queryFn: ({ pageParam }) =>
      objectsApi.list(datasetId, { token: pageParam as string | undefined, size }).then((r) => r.data),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => (last.hasMore ? last.nextToken ?? undefined : undefined),
  });
}

// 단건 presigned URL 조회(썸네일용)
export function usePresignedUrl(datasetId: number, key: string) {
  return useQuery({
    queryKey: ['datasets', datasetId, 'objects', 'url', key],
    queryFn: () => objectsApi.presignedUrl(datasetId, key).then((r) => r.data.url),
    staleTime: 4 * 60 * 1000, // presign 만료(5분)보다 짧게
  });
}
```

- [ ] **Step 3: DatasetObjectsTab 컴포넌트**

Create `apps/firehub-web/src/pages/data/tabs/DatasetObjectsTab.tsx` — 목록 그리드 + 썸네일. 이미지 확장자면 presigned URL로 `<img>`, 아니면 파일명·크기 카드. "더 보기" 버튼으로 `fetchNextPage`. (기존 `tabs/DatasetDocumentsTab.tsx` 레이아웃/로딩 처리 패턴 참고.) 썸네일은 개별 `usePresignedUrl(datasetId, key)`로 렌더하는 하위 컴포넌트로 분리.

```tsx
import { useObjectList } from '@/hooks/queries/useObjects';
import { ObjectThumbnail } from '../components/ObjectThumbnail'; // 아래 Step 4에서 생성

/** FILE 데이터셋 오브젝트 브라우저 탭 — presigned 썸네일 그리드 + 더보기. */
export function DatasetObjectsTab({ datasetId }: { datasetId: number }) {
  const { data, isLoading, hasNextPage, fetchNextPage, isFetchingNextPage } =
    useObjectList(datasetId);

  if (isLoading) return <div className="p-6 text-muted-foreground">불러오는 중…</div>;
  const items = data?.pages.flatMap((p) => p.objects) ?? [];
  if (items.length === 0)
    return <div className="p-6 text-muted-foreground">오브젝트가 없습니다.</div>;

  return (
    <div className="space-y-4 p-2">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-6">
        {items.map((o) => (
          <ObjectThumbnail key={o.key} datasetId={datasetId} objectKey={o.key} size={o.size} />
        ))}
      </div>
      {hasNextPage && (
        <button
          type="button"
          onClick={() => fetchNextPage()}
          disabled={isFetchingNextPage}
          className="mx-auto block rounded-md border px-4 py-2 text-sm hover:bg-accent"
        >
          {isFetchingNextPage ? '불러오는 중…' : '더 보기'}
        </button>
      )}
    </div>
  );
}
```

- [ ] **Step 4: ObjectThumbnail 하위 컴포넌트**

Create `apps/firehub-web/src/pages/data/components/ObjectThumbnail.tsx`:
```tsx
import { usePresignedUrl } from '@/hooks/queries/useObjects';

const IMAGE_EXT = /\.(jpe?g|png|gif|webp|bmp)$/i;

/** 오브젝트 1건 썸네일. 이미지면 presigned URL로 표시, 아니면 파일명 카드. */
export function ObjectThumbnail({
  datasetId,
  objectKey,
  size,
}: {
  datasetId: number;
  objectKey: string;
  size: number;
}) {
  const isImage = IMAGE_EXT.test(objectKey);
  const { data: url } = usePresignedUrl(datasetId, objectKey);
  const name = objectKey.split('/').pop() ?? objectKey;

  return (
    <div className="overflow-hidden rounded-md border">
      {isImage && url ? (
        <img src={url} alt={name} className="aspect-square w-full object-cover" loading="lazy" />
      ) : (
        <div className="flex aspect-square items-center justify-center bg-muted text-xs text-muted-foreground">
          {name}
        </div>
      )}
      <div className="truncate px-2 py-1 text-xs" title={objectKey}>
        {name} · {(size / 1024).toFixed(0)}KB
      </div>
    </div>
  );
}
```

- [ ] **Step 5: 상세 페이지에 objects 탭 배선**

Modify `apps/firehub-web/src/pages/data/DatasetDetailPage.tsx`:
- 라인 56 부근 `const isDocument = ...` 아래에 `const isFile = dataset?.storageType === 'FILE';` 추가.
- 라인 57 `validTabs` 계산을 FILE 분기 포함으로:
```tsx
  const validTabs = isDocument
    ? ['info', 'documents']
    : isFile
      ? ['info', 'objects']
      : ['info', 'columns', 'data', 'map', 'history'];
```
- 탭 트리거 영역(라인 418-430)에 FILE일 때 "오브젝트" 트리거 추가.
- 탭 바디(라인 432-462)에 `activeTab === 'objects'`일 때 `<DatasetObjectsTab datasetId={dataset.id} />` 렌더. (import 추가.)

- [ ] **Step 6: E2E — 오브젝트 브라우저 spec (mock)**

Create `apps/firehub-web/e2e/flows/file-dataset-browser.spec.ts`:
```ts
import { expect, test } from '../fixtures/auth.fixture';

test.describe('FILE 데이터셋 오브젝트 브라우저', () => {
  test('오브젝트 목록과 썸네일을 보여준다', async ({ authenticatedPage: page }) => {
    const datasetId = 7;
    // 상세 조회 mock (FILE 타입)
    await page.route(`**/api/v1/datasets/${datasetId}`, (route) =>
      route.fulfill({
        json: { id: datasetId, name: '장비 학습 데이터', storageType: 'FILE', originType: 'SOURCE' },
      }),
    );
    // 오브젝트 목록 mock
    await page.route(`**/api/v1/datasets/${datasetId}/objects*`, (route) => {
      if (route.request().url().includes('/objects/url')) {
        return route.fulfill({ json: { url: 'https://example.com/a.jpg', expiresInSeconds: 300 } });
      }
      return route.fulfill({
        json: {
          objects: [{ key: 'equip/robot-01/a.jpg', size: 2048, lastModified: null }],
          nextToken: null,
          hasMore: false,
        },
      });
    });

    await page.goto(`/data/datasets/${datasetId}`);
    await page.getByRole('tab', { name: '오브젝트' }).click();
    await expect(page.getByText('a.jpg')).toBeVisible();
  });
});
```
(상세 응답 형태·탭 이름은 실제 `DatasetDetailResponse`/트리거 텍스트에 맞춰 조정.)

- [ ] **Step 7: 타입체크 + E2E 실행**

Run: `cd apps/firehub-web && pnpm typecheck`
Expected: 통과.

Run: `cd apps/firehub-web && pnpm exec playwright test file-dataset-browser`
Expected: PASS.

- [ ] **Step 8: 커밋**

```bash
git add apps/firehub-web/src apps/firehub-web/e2e
git commit -m "feat(web): FILE 데이터셋 오브젝트 브라우저 탭 및 presigned 썸네일"
```

---

## Task 8: 배포 스크립트/문서에 minio 개별 키 추가

**Files:**
- Modify: `scripts/deploy.sh`
- Modify: `scripts/update.sh`
- Modify: `.claude/docs/deploy.md`

**Interfaces:**
- Produces: `./scripts/deploy.sh minio` 개별 배포 지원. `minio`는 `db`처럼 `all`에서 제외.

- [ ] **Step 1: deploy.sh에 minio 키 추가**

Modify `scripts/deploy.sh` — 서비스 케이스 목록에 `minio`를 추가하되, `all` 확장 목록(api+executor+web+ai-agent+channel)에는 **넣지 않는다**. MinIO는 public image이므로 빌드 없이 `docker compose pull minio && docker compose up -d --force-recreate minio` 패턴을 사용 (`db`가 빌드형인 것과 달리 pull형). `prod_service_name` 매핑은 서비스명이 `minio`로 동일하므로 별도 불필요.

- [ ] **Step 2: update.sh 동기화**

Modify `scripts/update.sh` — `all` 정의를 `deploy.sh`와 동일하게 유지(변경 없음 확인). minio가 개별 키로 인식되도록 케이스 목록 동기화.

- [ ] **Step 3: deploy.md 문서 동기화**

Modify `.claude/docs/deploy.md` — 서비스 키 표/설명에 `minio` 추가, "`all`에서 제외되는 stateful 서비스: `db`, `minio`" 명시. 배포 명령 예시 `./scripts/deploy.sh minio` 추가.

- [ ] **Step 4: 스크립트 문법 검증**

Run: `bash -n scripts/deploy.sh && bash -n scripts/update.sh`
Expected: 오류 없음(문법 OK).

Run: `./scripts/deploy.sh` (인자 없이 — usage 출력)
Expected: 사용법에 `minio` 키가 표시됨.

- [ ] **Step 5: 커밋**

```bash
git add scripts/deploy.sh scripts/update.sh .claude/docs/deploy.md
git commit -m "chore(deploy): MinIO 개별 배포 키 추가(all 제외)"
```

---

## Self-Review 결과

**Spec coverage (spec §별 → Task 매핑):**
- §4 아키텍처(데이터/제어 분리, 앱 바이트 미프록시) → Task 2/5 (presigned, list-only).
- §5 데이터 모델(FILE storageType, file_dataset_config, 파일 행 미저장, 가드) → Task 3/4.
- §6 업로드 경로(로봇 직접 PUT, 수동 자격증명, 키 규약) → Task 1(버킷) + 문서상 규약. 앱 코드 변경 없음(설계상 앱 밖) — 계획에 신규 코드 태스크 불필요, 수동 검증(Task 5 Step 5)에서 `mc cp` 키 규약 사용.
- §7 서빙/브라우징(list, presigned, 생성 화면, 오브젝트 브라우저) → Task 5/6/7.
- §8 인프라(compose, env, 배포 준수) → Task 1/8.
- §9 테스트(backend TC, web E2E) → Task 2/4/5(TC), Task 6/7(E2E).
- §10 리스크(CORS, 네트워크 도달성) → 아래 명시.

**미해결/구현자 판단 항목(placeholder 아님, 명시적 위임):**
- Task 4 Step 3의 `DatasetService` 단위 테스트는 실제 생성자 시그니처 확인 후 mock을 채우도록 명시(대형 서비스라 시그니처 고정 불가) — given/when/then 검증 대상은 구체화됨.
- `@RequirePermission` 정확한 패키지는 `DatasetController` 참조로 위임.
- **CORS(§10)**: 브라우저가 presigned URL로 MinIO에 직접 GET하려면 MinIO 버킷 CORS 허용 필요. dev/prod에서 브라우저↔MinIO 직접 접근이 막히면 Task 7 썸네일이 안 뜰 수 있음 → 그 경우 프리사인 대신 앱 프록시 GET으로 폴백해야 하나, PoC 범위에서는 CORS 허용을 우선 시도(리스크로 기록).

**Type consistency:** `ObjectListResponse`(objects/nextToken/hasMore), `ObjectItemResponse`(key/size/lastModified), `PresignedUrlResponse`(url/expiresInSeconds)가 백엔드(Task 2 DTO)·프런트(Task 7 api/objects.ts)·컨트롤러(Task 5)에서 동일 필드명으로 일치. `FileDatasetConfig(datasetId,bucket,prefix)`는 Task 4 정의 → Task 5 소비 일치. `storageType` 유니온 `'TABLE'|'DOCUMENT'|'FILE'`는 types/validation/modal/create/detail 전반 일치.
