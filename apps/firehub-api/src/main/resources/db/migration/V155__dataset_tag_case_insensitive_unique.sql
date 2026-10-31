-- V155: 데이터셋 태그를 대소문자 무시로 유일하게 만든다 (WD-389).
--
-- 왜 필요한가: 태그 중복 금지는 대소문자를 무시하는 규칙(#671)인데, 서버가 목록을 읽어 비교한 뒤 넣는 방식이라
-- 동시에 들어온 Fire·fire 같은 변형은 모두 통과했다. 기존 유일 제약 UNIQUE(dataset_id, tag_name) 은 대소문자를 구분해
-- 막지 못했고, 제거는 대소문자 무시(WD-242)라 칩 하나를 지우면 변형 전부가 사라졌다.
--
-- 1) 이미 생긴 변형은 데이터셋마다 가장 먼저 넣은 행(id 가 가장 작은 행)만 남긴다 — 사용자가 처음 고른 표기를 지킨다.
-- 2) (dataset_id, lower(tag_name)) 유일 인덱스로 이후 동시 요청도 DB 에서 하나만 들어가게 한다.
DELETE FROM dataset_tag t
USING dataset_tag k
WHERE t.dataset_id = k.dataset_id
  AND lower(t.tag_name) = lower(k.tag_name)
  AND t.id > k.id;

CREATE UNIQUE INDEX uq_dataset_tag_dataset_lower_name ON dataset_tag (dataset_id, lower(tag_name));
