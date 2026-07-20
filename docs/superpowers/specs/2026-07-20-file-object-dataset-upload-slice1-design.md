# FILE(오브젝트) 데이터셋 — 업로드 경로 설계 (Slice 1)

- 작성일: 2026-07-20
- 상태: 설계 확정 (구현 계획 대기)
- 부모 스펙: `docs/superpowers/specs/2026-07-20-file-object-dataset-poc-design.md`
- 범위: FILE 데이터셋에 **presigned PUT URL 발급**으로 업로드 경로를 실동작시키는 증분(Slice 1)

## 1. 배경 / 문제

- Slice 0(PoC)에서 **서빙 경로**(오브젝트 목록 + presigned GET + 브라우징)는 end-to-end 검증 완료.
- 그러나 정작 핵심이던 **업로드**는 스펙 §6에서 "자격증명 수동 발급(`mc`)"만 가정 — 앱을 통한 실제 업로드 경로가 없다.
- 이 slice는 "로봇/사람이 앱을 통해 대량 파일을 MinIO에 올린다"는 가치 루프를 닫는다.

## 2. 목표 / 비목표

**목표**
- 앱이 **프리픽스 한정 presigned PUT URL**을 발급하고, 클라이언트(로봇/브라우저)가 그 URL로 MinIO에 **바이트를 직접 PUT**(앱 미경유)한다.
- 앱이 오브젝트 키를 생성하여 스펙 §6 키 규약과 데이터셋 프리픽스 격리를 **강제**한다.
- 웹 오브젝트 브라우저에서 드래그앤드롭 업로드 → 목록 즉시 반영까지 확인.

**비목표(YAGNI)**
- 자동 자격증명(MinIO `madmin`) 발급 API — 후속 slice.
- 멀티파트 업로드 세분 제어(SDK 자동에 위임), 업로드 재개(resumable).
- 파일별 DB 메타데이터/검증/썸네일 사전 생성.

## 3. 아키텍처

데이터 평면(클라이언트↔MinIO)은 Slice 0과 동일하게 앱을 통과하지 않는다. 앱은 **키를 정하고 PUT URL을 서명**하는 제어 평면 역할만 추가한다.

```
   ┌──────────┐  ① POST upload-urls (robotId, files[])   ┌──────────────┐
   │ 클라이언트 │ ───────────────────────────────────────▶ │  firehub-api │  키 생성 + PUT URL 서명
   │(로봇/웹)  │ ◀─────────────────────────────────────── │              │
   └──────────┘   [{key, uploadUrl}] (단기 TTL)           └──────────────┘
        │
        │ ② PUT bytes (직접, 앱 미경유)
        ▼
   ┌──────────────┐
   │    MinIO     │  s3://firehub-files/<prefix><robotId>/<date>/<uuid>.<ext>
   └──────────────┘
```

## 4. API

**신규 엔드포인트 (기존 `FileObjectController`에 추가)**

```
POST /api/v1/datasets/{datasetId}/objects/upload-urls
  @RequirePermission("dataset:write")

  Request:
    {
      "robotId": "robot-01",          // 선택, 없으면 "web"
      "files": [ { "ext": "jpg" }, { "ext": "jpg" } ]
    }

  Response 200:
    {
      "targets": [
        { "key": "test-images/robot-01/2026-07-20/<uuid>.jpg",
          "uploadUrl": "http://<minio>/firehub-files/...?X-Amz-..." },
        ...
      ],
      "expiresInSeconds": 900
    }
```

- 비FILE 데이터셋(=`file_dataset_config` 없음)이면 400.
- 클라이언트는 응답의 `uploadUrl`로 각 파일을 `PUT`(body=바이트)한다. 앱은 바이트를 프록시하지 않는다.

## 5. 키 생성 규칙 (앱 전담)

- 형식: `<prefix><robotId>/<yyyy-MM-dd>/<uuid>.<ext>`
  - `prefix`는 `file_dataset_config.prefix` (항상 trailing `/` 보장 — Slice 0에서 정규화됨) → 격리 자동 유지, 클라이언트가 프리픽스 밖으로 못 나감.
  - `yyyy-MM-dd`는 서버 기준 `LocalDate.now()`.
  - `uuid`는 `UUID.randomUUID()` → 충돌 방지.
- **입력 정제(방어)**:
  - `robotId`: 소문자화 후 `[a-z0-9-]` 외 문자를 `-`로 치환, 앞뒤 `-` 트림. 빈 값/전부 제거되면 `web`.
  - `ext`: 소문자화 후 `[a-z0-9]` 외 제거, 최대 10자. 빈 값이면 `bin`. (앞의 `.`은 붙이지 않는다.)
- **배치 상한**: `files`는 1개 이상 1000개 이하. 0개 또는 1000 초과면 400. (대량 유입 대비 1회 호출 다건, 남용 방지 상한.)

## 6. 백엔드 변경

**`FileObjectStorageService`**
- 신규 `presignedPutUrl(String bucket, String objectKey, int expirySeconds)`: 기존 `presignedGetUrl`을 `Method.PUT`으로 미러링, `PresignedUrlResponse(url, expirySeconds)` 반환.
- 신규 `defaultUploadPresignExpiry()`: `props.uploadPresignExpirySeconds()` 반환.

**`MinioProperties`**
- 신규 필드 `uploadPresignExpirySeconds` (record 컴포넌트 추가).

**`application.yml`**
- `firehub.minio.upload-presign-expiry-seconds: ${MINIO_UPLOAD_PRESIGN_EXPIRY:900}` 추가. (업로드는 썸네일 GET(300s)보다 느릴 수 있어 분리, 기본 15분.)

**DTO (신규, `com.smartfirehub.file.dto`)**
- `UploadUrlRequest(String robotId, List<FileSpec> files)` + 중첩/동거 `FileSpec(String ext)`.
- `UploadTarget(String key, String uploadUrl)`.
- `UploadUrlResponse(List<UploadTarget> targets, int expiresInSeconds)`.

**`FileObjectController`**
- 신규 `@PostMapping("/upload-urls")` `@RequirePermission("dataset:write")`:
  1. `config(datasetId)`로 FILE config 조회(비FILE 거부).
  2. `files` 크기 검증(1~1000).
  3. `robotId`/각 `ext` 정제 → 키 생성 → `presignedPutUrl` 발급 → `targets` 구성.
  4. `expiresInSeconds`는 `storage.defaultUploadPresignExpiry()`.
- 키 생성·정제 로직은 컨트롤러 private 헬퍼 또는 서비스로 배치(테스트 용이하게 순수 함수 지향).

## 7. 프론트엔드 변경 (오브젝트 브라우저에 업로드)

**`src/api/objects.ts`**
- `requestUploadUrls(datasetId, { robotId, files })` → `POST .../upload-urls`.
- `putToPresignedUrl(uploadUrl, file)` → axios/fetch **raw PUT** (앱 baseURL/인터셉터 미경유, `Content-Type`은 파일 타입).

**`src/hooks/queries/useObjects.ts`**
- `useUploadObjects(datasetId)` 뮤테이션:
  1. 파일 목록 → `requestUploadUrls`로 `targets` 획득(각 파일의 확장자 추출),
  2. `targets`와 파일을 짝지어 각각 `putToPresignedUrl`,
  3. 성공 시 오브젝트 목록 쿼리 `invalidateQueries`.

**`src/pages/data/tabs/DatasetObjectsTab.tsx`**
- 드래그앤드롭 영역 + 파일 선택 버튼, 업로드 진행/결과 표시. 웹 업로드 `robotId` 기본 `web`.

## 8. CORS (알려진 한계 확장)

- 브라우저가 MinIO로 직접 PUT하려면 버킷에 **웹 오리진 허용 CORS**(PUT/GET + 프리플라이트)가 필요하다.
- **dev**: `minio-init`/`mc` 대신 MinIO 서버 환경변수 `MINIO_API_CORS_ALLOW_ORIGIN`(`docker-compose.yml`)으로 로컬 웹 오리진(기본값 `http://localhost:5173`)에 대해 PUT/GET을 허용한다.
- **prod**: 부모 스펙 §10 "알려진 한계"에 **PUT CORS**도 포함됨을 명시. `MINIO_ENDPOINT`가 브라우저 도달 가능한 공개 호스트여야 하고 같은 `MINIO_API_CORS_ALLOW_ORIGIN` 방식으로 해당 웹 오리진의 CORS가 구성되어야 브라우저 업로드/썸네일이 동작한다. (로봇은 서버-투-서버 PUT이므로 CORS 무관.)

## 9. 테스트 (CLAUDE.md 규칙)

**백엔드 TC (로컬 MinIO 통합, 기존 FILE 테스트 패턴 재사용)**
- `presignedPutUrl`이 `PUT` 메서드로 서명된 URL(오브젝트 키 포함, `X-Amz-Signature` 포함)을 반환.
- `upload-urls`: `files` N개 → `targets` N개, 각 `key`가 `<prefix><robotId>/<date>/<uuid>.<ext>` 규약을 만족하고 모두 prefix 하위.
- `robotId`/`ext` 정제(대문자·특수문자·빈 값 케이스).
- `files` 0개/1001개 → 400.
- 비FILE 데이터셋 → 400.
- `dataset:write` 권한 요구(권한 없으면 403).

**웹 E2E (Playwright, API 모킹)**
- 오브젝트 브라우저에서 파일 드롭 → `upload-urls` 응답 모킹 + presigned PUT 라우트 200 모킹 → 목록 재조회에 새 오브젝트가 노출되는지 확인.

## 10. 리스크 / 열린 질문

- **CORS 도달성**: dev 자동 구성으로 해소하되, MinIO 릴리스별 CORS 설정 방식(자동 허용 vs 명시 설정) 차이가 있을 수 있어 구현 시 실제 프리플라이트로 검증한다.
- **키 충돌/정합성**: UUID로 충돌은 사실상 배제. 동일 파일 재업로드는 새 키가 되므로 중복 오브젝트가 생길 수 있음(업로드 측 책임, 이 slice 범위 밖).
- **부분 실패**: 배치 중 일부 PUT 실패 시 프론트는 실패 건을 사용자에게 표시하고 재시도 유도(트랜잭션 롤백 대상 아님 — 오브젝트 단위 독립).
- **알려진 후속 과제(Promise.all 전부-아니면-전무)**: 현재 프론트 배치 업로드 구현은 `Promise.all`을 사용해 한 파일의 PUT이라도 실패하면 배치 전체가 에러로 처리되고, 이미 업로드에 성공한 파일들도 화면 새로고침 전까지 반영되지 않는다. 이는 위 §9/§145가 의도한 "부분 실패 시 실패 건 표시"와 어긋나며, `Promise.allSettled` 전환 + 무조건적인 목록 invalidate로 해소 가능한 후속 작업으로 남긴다.
