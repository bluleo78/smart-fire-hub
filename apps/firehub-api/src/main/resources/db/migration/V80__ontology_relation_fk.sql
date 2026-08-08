-- 관계의 subject/object를 타입 "이름"(TEXT)에서 entity_type_id FK로 옮긴다.
-- 이름 참조였기 때문에 타입 리네임 때마다 관계 행의 문자열까지 따라 고쳐야 했고, 그것이 편집 API의
-- renames 힌트(UpdateOntologyRequest.TypeRename)가 존재하는 유일한 이유였다. FK로 바꾸면 리네임이
-- 엔티티 행 하나의 UPDATE로 끝나고, 타입 삭제 시 관계 정리도 DB(ON DELETE CASCADE)가 보장한다.

-- ① FK 컬럼 추가(백필 전이므로 nullable).
ALTER TABLE ontology_relation ADD COLUMN subject_type_id BIGINT;
ALTER TABLE ontology_relation ADD COLUMN object_type_id  BIGINT;

-- ② 같은 온톨로지 안에서 타입명으로 매칭해 백필. (ontology_id, type)은 V71의 UNIQUE라 1:1이다.
UPDATE ontology_relation r
   SET subject_type_id = s.id,
       object_type_id  = o.id
  FROM ontology_entity_type s,
       ontology_entity_type o
 WHERE s.ontology_id = r.ontology_id AND s.type = r.subject
   AND o.ontology_id = r.ontology_id AND o.type = r.object;

-- ③ 백필 가드. 매칭 실패 행을 방치하면 아래 NOT NULL에서 터지는데, 그 에러 메시지로는 "어떤 데이터가
--    왜 안 맞는지"를 알 수 없다. 여기서 건수를 세어 명시적으로 실패시킨다.
DO $$
DECLARE orphan_count INT;
BEGIN
  SELECT COUNT(*) INTO orphan_count
    FROM ontology_relation
   WHERE subject_type_id IS NULL OR object_type_id IS NULL;
  IF orphan_count > 0 THEN
    RAISE EXCEPTION 'V80 백필 실패: 엔티티 타입에 매칭되지 않는 관계 %건. 해당 행의 subject/object를 정정한 뒤 재시도하세요.', orphan_count;
  END IF;
END $$;

-- ④ 중복 트리플 정리. 지금까지 유니크 제약이 없어 DB 차원에서 중복이 합법이었고(서비스 검증만 막았다),
--    요소 단위 추가 API를 세우려면 DB가 중복을 거절해야 한다.
--    보존 규칙: sort_order 오름차순, 동률이면 id 오름차순으로 첫 행만 남긴다.
--    sort_order를 우선하는 이유는 프롬프트 조립 순서에서 앞서던 행을 살리기 위함이다.
--    삭제 행은 NOTICE로 남긴다 — 조용한 데이터 손실을 만들지 않는다.
DO $$
DECLARE dup RECORD;
BEGIN
  FOR dup IN
    SELECT id, ontology_id, subject_type_id, relation, object_type_id
      FROM (
        SELECT id, ontology_id, subject_type_id, relation, object_type_id,
               ROW_NUMBER() OVER (
                 PARTITION BY ontology_id, subject_type_id, relation, object_type_id
                 ORDER BY sort_order, id
               ) AS rn
          FROM ontology_relation
      ) t
     WHERE t.rn > 1
  LOOP
    RAISE NOTICE 'V80 중복 관계 삭제: id=% ontology=% (%)-[%]->(%)',
      dup.id, dup.ontology_id, dup.subject_type_id, dup.relation, dup.object_type_id;
    DELETE FROM ontology_relation WHERE id = dup.id;
  END LOOP;
END $$;

-- ⑤ 제약 확정. CASCADE는 "타입을 지우면 그 타입이 등장하는 관계도 무의미해진다"는 도메인 규칙 그대로다.
ALTER TABLE ontology_relation
  ALTER COLUMN subject_type_id SET NOT NULL,
  ALTER COLUMN object_type_id  SET NOT NULL,
  ADD CONSTRAINT ontology_relation_subject_fk
    FOREIGN KEY (subject_type_id) REFERENCES ontology_entity_type(id) ON DELETE CASCADE,
  ADD CONSTRAINT ontology_relation_object_fk
    FOREIGN KEY (object_type_id)  REFERENCES ontology_entity_type(id) ON DELETE CASCADE,
  ADD CONSTRAINT ontology_relation_unique
    UNIQUE (ontology_id, subject_type_id, relation, object_type_id);

-- ⑥ 이름 컬럼 제거. 남겨두면 두 표현이 갈라져 어느 쪽이 진실인지 알 수 없게 된다.
ALTER TABLE ontology_relation DROP COLUMN subject;
ALTER TABLE ontology_relation DROP COLUMN object;

-- ⑦ 삭제 CASCADE와 역방향 조회를 위한 인덱스.
CREATE INDEX IF NOT EXISTS idx_ontology_relation_subject ON ontology_relation (subject_type_id);
CREATE INDEX IF NOT EXISTS idx_ontology_relation_object  ON ontology_relation (object_type_id);
