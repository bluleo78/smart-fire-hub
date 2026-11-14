-- V162: 벡터 아웃박스와 순번 시퀀스 (WD-721, ADR 0002 결정 6)
--
-- 왜: 벡터를 별도 저장소(Qdrant)에 두면 같은 DB 의 CASCADE·트랜잭션 삭제를 쓸 수 없다. 삭제를 커밋 뒤에 바로 보내면
-- ① 저장소가 잠깐 내려가 있을 때 삭제가 사라지고 ② 늦게 도착한 삭제가 그 사이 다시 쓴 새 벡터까지 지운다
-- (예: 등급 불허 → 허용 → 재임베딩 → 늦은 삭제, 문서 재적재). 그래서
--   * 삭제는 부모 행 삭제·표시 열 비우기와 같은 트랜잭션에서 이 테이블에 쌓고, 커밋 뒤 워커가 저장소에 반영한다(유실 방지).
--   * 벡터를 쓸 때마다 vector_seq 에서 순번을 받아 점 payload seq 에 싣고, 삭제 행에도 순번을 싣는다. 워커는 "그 순번보다 작은 점" 만
--     지운다(순서 보장). 시각을 쓰지 않는 이유: api 파드 시계와 DB 시계를 비교하게 되고, Tibero 는 TSTZ 열을 금지한다(WD-260).
--
-- 열:
--   seq       — 삭제 순번(vector_seq). 기본 키이자 처리 순서. 이 값보다 작은 seq 를 가진 점만 지운다.
--   kind      — 무엇을 지우나(VectorOutboxKind: DATASET_DENSE, DATASET_ALL, CHUNK_DENSE, CHUNK_ALL_OF_DATASET, CHUNK_ALL_OF_DOCUMENT).
--   target_id — 대상 데이터셋 id 또는 문서 파일 id. NULL 이면 테넌트 전체(차원 정리).
--   keep_dim  — 이 차원 컬렉션은 건드리지 않는다(차원 정리·재임베딩 뒤 다른 차원 정리). NULL 이면 모든 차원.
--   attempts·last_error — 반영 실패 횟수와 마지막 오류(운영 확인용). 실패한 행은 남아 다음 폴링이 다시 한다.
--
-- 부모(dataset·document_file)에 FK 를 두지 않는다 — 부모 삭제와 같은 트랜잭션에 쌓는 행이라 CASCADE 가 걸리면 같이 지워진다.
-- 테넌트 FK 만 ON DELETE CASCADE 다 — 테넌트 행이 지워지면 그 테넌트의 대기 행도 함께 지운다(FK 가 테넌트 삭제를 막지 않게).
-- 지금 앱에는 테넌트 행을 지우는 경로가 없다(비활성 전환만 있다). 테넌트를 지우는 기능이 생기면 그 테넌트 점을 Qdrant 에서 tenant_id 로
-- 지우는 정리를 함께 넣어야 한다 — 대기 행이 사라지고 대조 스윕도 활성 테넌트만 돌기 때문이다(ADR 0002 결정 6, 후속).
-- RLS 는 V102 표준 형태 (a). FORCE ROW LEVEL SECURITY 금지. app_tenant 권한은 V83 의 ALTER DEFAULT PRIVILEGES 가 부여한다.
-- 워커는 TenantScopedRunner.forEachActiveTenant 로 테넌트마다 폴링한다 — 비활성 테넌트의 행은 남는다(ADR 0002 결정 6, 감수).
--
-- Tibero 짝: db/migration-tibero/V162 (MigrationParityTest).
SET LOCAL lock_timeout = '3s';

CREATE SEQUENCE IF NOT EXISTS vector_seq;

CREATE TABLE IF NOT EXISTS vector_outbox (
  seq        bigint       PRIMARY KEY,
  tenant_id  bigint       NOT NULL DEFAULT NULLIF(current_setting('app.tenant_id', true), '')::bigint REFERENCES tenant(id) ON DELETE CASCADE,
  kind       varchar(40)  NOT NULL,
  target_id  bigint,
  keep_dim   int,
  attempts   int          NOT NULL DEFAULT 0,
  last_error varchar(500),
  created_at timestamptz  NOT NULL DEFAULT now()
);

ALTER TABLE vector_outbox ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS vector_outbox_tenant_isolation ON vector_outbox;
CREATE POLICY vector_outbox_tenant_isolation ON vector_outbox
  USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

-- 워커 폴링(테넌트별 seq 순서)이 이 인덱스를 탄다.
CREATE INDEX IF NOT EXISTS idx_vector_outbox_tenant_seq ON vector_outbox (tenant_id, seq);
