# FILE(오브젝트) 데이터셋 PoC 설계

- 작성일: 2026-07-20
- 상태: 설계 확정 (구현 계획 대기)
- 범위: 대량 파일(로봇 캡처 이미지 등)을 MinIO에 업로드·서빙하기 위한 **오브젝트 데이터셋** 최소 수직 슬라이스(PoC)

## 1. 배경 / 문제

- 현재 데이터셋 타입은 `TABLE`(PostgreSQL `data` 스키마 물리 테이블), `DOCUMENT`(로컬 파일 blob + pgvector)만 지원.
- 대량·대용량 파일(로봇이 하루 수천 장씩 캡처하는 이미지)을 담고 다시 서빙할 컨테이너가 없음.
- 제약: **Private/온프레미스 환경 — 외부 클라우드(S3) 사용 불가.**
- 제약: nginx/멀티파트 업로드 **256MB 한계** → 앱 경유 대량 업로드에 부적합.

## 2. 목표 / 비목표

**목표(PoC가 증명할 것)**
- 대량 파일을 앱을 통과시키지 않고 오브젝트 스토리지(MinIO)에 담고, 앱에서 등록·조회·서빙(다운로드)까지 end-to-end로 동작.
- 로봇을 S3 클라이언트로 취급하는 직접 업로드 경로 검증.

**비목표(의도적 제외, YAGNI)**
- ML 학습 전용 기능(샤딩, WebDataset/TFRecord, split, manifest, 버전 스냅샷).
- `backend_type` 전면 추상화 / `FileUploadService` 리팩터링 (기존 DOCUMENT·chat-file 경로는 그대로 로컬 유지).
- MinIO HA(MNMD)·멀티테넌트(Operator)·자동 자격증명 발급 API.
- 파일별 메타데이터 검색/라벨링.

## 3. 규모 가정

- 유입: 하루 수천 장 규모(연 100만 장 남짓). MinIO 단일 노드가 문제없이 감당.
- 업로드 캐던스/로봇 대수/네트워크 토폴로지: **미정** → 설계는 여기에 의존하지 않음(지속 스트림·배치 모두 동작).

## 4. 아키텍처 개요

데이터 평면(로봇↔MinIO)과 제어/메타 평면(앱)을 분리한다. 대량 이미지는 앱을 통과하지 않는다.

```
   ┌──────────┐   S3 PUT (직접)     ┌─────────────┐
   │  로봇들   │ ──────────────────▶ │    MinIO     │  s3://firehub-datasets/
   └──────────┘  (스코프 키)         │  (컨테이너1) │    <dataset>/robot-xx/<date>/*.jpg
                                     └─────────────┘
                                          ▲   ▲
        등록/조회/서빙 URL 발급           │   │ S3 API (list/stat/presign)
   ┌──────────┐   REST              ┌─────────────┐
   │ firehub  │ ◀─────────────────▶ │  firehub-api │  (신규 FILE 데이터셋 + S3 클라이언트)
   │  -web    │                     └─────────────┘
   └──────────┘                          │ JDBC
                                     ┌─────────────┐
                                     │  PostgreSQL  │  dataset 메타 1건 (파일 목록 X)
                                     └─────────────┘
```

**구성요소 (신규/변경)**
- **MinIO 컨테이너 1대** — docker-compose에 추가(SNSD). API 9000 / 콘솔 9001.
- **firehub-api**: (1) 신규 `storageType=FILE` 데이터셋(물리 테이블 생성 없음, 프리픽스 URI + 메타만), (2) S3 클라이언트 빈(AWS SDK v2 또는 MinIO SDK), (3) 오브젝트 목록·presigned GET URL 발급 엔드포인트.
- **firehub-web**: FILE 데이터셋 생성/상세 화면(오브젝트 브라우저 — 썸네일 그리드 + 다운로드).
- **PostgreSQL**: 데이터셋 메타 **1건**만. 개별 이미지는 DB에 저장하지 않음(목록은 S3 list로 실시간 조회).

## 5. 데이터 모델 (앱)

기존 `dataset` 테이블 재사용 + FILE 전용 설정만 추가. **개별 파일 행 없음.**

```
dataset (기존)
  storage_type = 'FILE'          ← enum 값 추가 (TABLE | DOCUMENT | FILE)
  origin_type  = 'SOURCE'
  ...

file_dataset_config (신규, dataset 1:1)   ← 또는 dataset.config JSONB 컬럼
  dataset_id    FK
  bucket        예) 'firehub-datasets'
  prefix        예) '장비-학습-데이터/'
  object_count  (선택, 캐시값 — S3 list 기반 주기 갱신)
```

- `DatasetService`의 `rejectIfDocument` 계열 가드에 **FILE도 컬럼/PK/클론 등 테이블 전용 연산 차단** 추가.
- 목록·개수는 조회 시점에 S3 list로 계산(DB에 파일 미저장).
- 마이그레이션: `storage_type` enum/체크제약에 `FILE` 추가, `file_dataset_config` 테이블(또는 JSONB 컬럼) 추가. 현재 마이그레이션 버전 이후 신규 V 부여.

## 6. 업로드 경로 (로봇 → MinIO)

- **PoC 범위**: 자격증명 **수동 발급**. 운영자가 `mc`로 프리픽스 한정 정책 + 유저 생성 → 로봇에 액세스키 주입. (자동 발급 API는 후속 스펙.)
- 로봇은 S3 SDK로 MinIO에 직접 PUT. 앱·nginx·256MB와 무관. 멀티파트 업로드는 SDK가 처리.
- **오브젝트 키 규약**:
  ```
  <prefix>/<robot-id>/<yyyy-MM-dd>/<uuid>.jpg
  예) 장비-학습-데이터/robot-01/2026-07-20/a1b2c3d4....jpg
  ```

## 7. 서빙·브라우징 경로 (앱)

**API (신규, firehub-api)**
- `GET /datasets/{id}/objects?prefix=&token=` → S3 list(페이지네이션, continuation token 반환).
- `GET /datasets/{id}/objects/{key}/url` → **presigned GET URL** 발급(단기 TTL, 예 5분).
- 앱은 바이트를 프록시하지 않음 → 브라우저/클라이언트가 presigned URL로 MinIO에서 직접 GET.

**Web (firehub-web)**
- FILE 데이터셋 생성 화면: 버킷/프리픽스 지정(`DatasetTypeModal`에 FILE 선택지 추가).
- 오브젝트 브라우저: 날짜/로봇 프리픽스 트리 + 썸네일 그리드(presigned `<img src>`) + 다운로드, 무한스크롤.

## 8. 인프라

- `docker-compose`(dev/prod)에 MinIO 서비스 1개, 데이터 볼륨, 초기 버킷 부트스트랩(`mc mb`).
- firehub-api 환경변수: `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `MINIO_BUCKET`.
- 배포 반영 시 `.claude/docs/deploy.md` 규칙 준수(개별 빌드/배포 옵션 포함).

## 9. 테스트 (CLAUDE.md 규칙)

- **backend TC**: 오브젝트 list / presigned URL 발급 로직 — 로컬 MinIO 대상 통합 테스트.
- **web E2E (Playwright)**: FILE 데이터셋 생성 → 오브젝트 브라우저 목록/썸네일 노출 확인.

## 10. 리스크 / 열린 질문

- **네트워크 도달성**: 로봇·브라우저가 MinIO 엔드포인트에 직접 도달 가능해야 함(presigned URL 호스트). 사내망/게이트웨이 토폴로지 확정 시 엔드포인트 노출 방식 조정 필요.
- **자격증명 관리**: PoC는 수동. 로봇 다수화 시 프리픽스별 정책 자동 발급 필요(후속).
- **파일별 메타/검색 요구가 생기면** DB에 파일 행을 두는 모델로 확장 분기(현재는 S3 list 기반).
- **CORS**: 브라우저가 presigned GET으로 직접 접근하려면 MinIO 버킷 CORS 설정 필요.
```
