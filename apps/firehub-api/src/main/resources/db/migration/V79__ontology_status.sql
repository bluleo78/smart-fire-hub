-- 온톨로지 생명주기 상태 도입.
--  draft    : AI 챗이 제안한 미완성 스키마. 사람이 검토·활성화하기 전까지 바인딩·적재 경로에서 격리한다.
--  active   : 운영 중. 바인딩·적재 가능.
--  archived : 은퇴. 신규 바인딩은 막되 기존 바인딩과 이미 적재된 데이터는 보존한다.
-- dataset_mapping(V78)의 draft/active 어휘를 확장한 형태로 맞췄다.
-- 기존 행은 DEFAULT로 'active'가 되어 동작이 변하지 않는다.
ALTER TABLE ontology ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active'
  CHECK (status IN ('draft','active','archived'));

-- 도메인 UNIQUE(V77)를 부분 인덱스로 교체한다.
-- 전역 UNIQUE를 유지하면 "구 온톨로지를 은퇴시키고 같은 도메인의 후속을 세운다"가 전 방향으로 막힌다
-- (같은 이름 생성은 UNIQUE가, 옛것의 개명은 archived 편집 금지가, 삭제는 참조 중 409가 막는다).
-- archived를 유니크 검사에서 빼면 은퇴가 실제로 쓸모 있어지고, 살아있는 것끼리의 중복 방지는 유지된다.
ALTER TABLE ontology DROP CONSTRAINT ontology_domain_unique;
CREATE UNIQUE INDEX ontology_domain_unique
  ON ontology (domain) WHERE status <> 'archived';

-- 목록 화면이 상태로 필터링하므로 인덱스를 둔다(행 수는 적지만 기본 조회 경로다).
CREATE INDEX IF NOT EXISTS idx_ontology_status ON ontology (status);
