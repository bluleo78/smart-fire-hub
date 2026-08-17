-- P3-a Task 6 되돌리기: V109 의 dataset.table_name 유니크 접기를 취소하고 전역 유니크로 복원한다.
--
-- 무엇을: idx_dataset_table_name 을 (tenant_id, table_name) 에서 다시 (table_name) 전역 유니크로
--         되돌린다. V109 는 이미 공유 test DB 에 적용됐으므로 파일을 지우거나 고치지 않는다
--         (적용된 마이그레이션을 없애면 그것을 가진 모든 DB 가 체크섬 불일치로 기동 불가). 서로
--         상쇄하는 두 마이그레이션이 남는 것은 감수한다 — 그것이 정직한 이력이고, 순서가 틀렸다는
--         사실 자체를 기록한다.
--
-- 왜 되돌리는가(계획 결함): V109 의 근거는 "리네임이 이미 충돌하는 데이터를 만나지 않도록 먼저
--     접는다" 였는데, 이는 거꾸로다. 전역 유니크가 있는 동안 충돌 데이터는 **존재할 수 없다** —
--     그것이 전역 유니크가 보장하는 바로 그것이다. 접기는 충돌을 예방하지 않고, 물리 스키마가
--     아직 공유인 동안 **충돌 가능성을 새로 만든다**.
--
-- 접으면 무슨 일이 생기는가(코드 경로 추적. 인덱스 계층의 23505 소멸까지는 테스트로 실측했고,
-- 그 뒤의 DDL 파괴 단계는 서비스 경로를 실행해 재현하지는 않았다):
--   DataSchema.current() 는 오늘 상수 "data" 를 돌려준다(P3-b 에서야 data_t{tenantId}). 따라서
--   두 테넌트의 table_name='foo' 는 같은 물리 테이블 data."foo" 하나로 해석되고, data 스키마에는
--   RLS 가 없다. DatasetService.createDataset 순서는
--     existsByTableName(RLS 스코프 → false) → save() → DataTableService.createTable
--   인데 createTable 의 첫 문장이 `DROP TABLE IF EXISTS data."foo"` 다.
--     · 전역 유니크일 때: save() 가 23505 로 죽어 그 DDL 에 도달하지 못한다(손실 없음).
--     · 접은 뒤: save() 가 성공하고 createTable 이 앞선 테넌트의 물리 테이블을 지우고 빈 테이블로
--       재생성한 뒤 **예외 없이 커밋**한다 → 데이터 소실 + 두 테넌트가 한 물리 테이블 공유.
--
-- 결론(P3-b 가 반복하지 않도록): 유니크 접기는 P3-a 에 아무 가치가 없다. 그 유일한 목적은
--     리네임을 가능하게 하는 것이고 리네임은 P3-b 다. 따라서 접기는 **스키마 분리와 같은 커밋/밴드**
--     에서만 해야 한다. 같은 이유로 table_name 을 키로 삼는 다른 DDL 경로도 전역 유니크에 기대고
--     있다 — DataTableService 의 삭제 DROP, REPLACE 로드의 temp swap. P3-b 는 접기와 함께 그것들을
--     한 커밋에서 처리해야 한다.
--
-- DatasetDomainRlsTest.twoTenantsCannotShareTableNameWhileDataSchemaIsShared 와
-- DatasetDomainColumnTest 가 "지금은 전역이어야 한다"를 고정한다(앞당겨 접기 방지 가드).

DROP INDEX IF EXISTS idx_dataset_table_name;

CREATE UNIQUE INDEX idx_dataset_table_name ON dataset (table_name);
