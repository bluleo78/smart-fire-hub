-- P3-a Task 6: dataset.table_name 유니크를 테넌트 안으로 접는다.
--
-- 무엇을: V2 가 만든 전역 UNIQUE INDEX idx_dataset_table_name(table_name) 을 버리고
--         (tenant_id, table_name) 유니크로 다시 만든다.
--
-- 왜: table_name 은 공유 data 스키마의 실제 테이블명이라 지금까지 전역 유니크여야 했다
--     (두 테넌트가 같은 물리 테이블을 주장하면 안 됐다). V87(38줄)이 dataset 도메인의 다른
--     유니크를 테넌트로 접으면서 이것만 의도적으로 남겨 두고 이 밴드로 미뤘다. P3-b 에서
--     data 스키마가 테넌트별로 갈라지면 같은 이름이 물리적으로 충돌하지 않으므로 전역 유니크는
--     근거를 잃는다.
--
-- 왜 지금(리네임 전에): 리네임 마이그레이션이 "이미 충돌하는 데이터"를 만나 실패하는 일을
--     막으려면 제약 완화가 먼저여야 한다. 반대 순서로 하면 리네임이 끝난 뒤에도 전역 유니크가
--     남아 있어 테넌트별로 갈라진 스키마에서 이름 재사용이 계속 막히고, 그 상태에서 접기를
--     하려면 이미 쌓인 데이터를 놓고 접어야 한다. 완화는 기존 데이터를 절대 위반시키지 않는
--     방향(전역 유니크 ⊃ 테넌트 유니크)이라 지금 하면 무조건 성공한다.
--
-- 동작 변화(의도된 것): DatasetRepository.existsByTableName 은 RLS 로 스코프된 public.dataset 을
--     읽어 남의 테넌트 행을 보지 못하는데 인덱스는 전역이었다 → 테넌트 B 가 A 의 table_name 을
--     고르면 사전검사를 통과하고 INSERT 가 23505 로 죽었다. 접은 뒤에는 그 충돌 자체가 사라져
--     사전검사와 INSERT 가 처음으로 일치한다(거짓 제약의 제거).
--     DatasetDomainRlsTest.twoTenantsCanUseTheSameTableNameAndPreCheckAgreesWithInsert 가 고정한다.
--
-- 42P10 위험 없음: dataset 에 대한 INSERT 는 DatasetRepository.save 하나뿐이고 onConflict 가
--     전혀 없다(실측). 게다가 대상이 UNIQUE CONSTRAINT 가 아니라 UNIQUE INDEX 라
--     onConflictOnConstraint 로 이름을 지목하는 것 자체가 애초에 불가능했다.

DROP INDEX IF EXISTS idx_dataset_table_name;

CREATE UNIQUE INDEX idx_dataset_table_name ON dataset (tenant_id, table_name);
