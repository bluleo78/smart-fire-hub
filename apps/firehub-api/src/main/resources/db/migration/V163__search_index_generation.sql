-- V163: 행 검색 색인 세대 (WD-722, ADR 0002 결정 9)
--
-- 왜: 원본 테이블이 교체(swap)되면 row_id 가 새로 매겨진다. PG 색인은 테이블 이름(fh_search_{id} / _prev)으로 지금 색인과 옛 색인을
-- 가르지만, Qdrant 색인은 한 컬렉션 안에 옛 점과 새 점이 함께 있다. 그래서 「지금 세대」를 여기 기록하고, swap 마다 1 씩 올리며,
-- Qdrant 검색은 이 값으로만 거른다. 거르지 않으면 옛 점의 row_id 가 새 테이블의 엉뚱한 행을 가리킨다.
--
--   dataset_search_index.generation — 지금 세대. 1 부터 시작하고 markSourceOid(swap 재구축) 가 같은 UPDATE 에서 올린다.
--
-- PG 색인(PgRowSearchIndex)은 이 값을 쓰지 않는다(테이블 이름이 세대 역할). 0 은 앱에서 「세대 무관(전체 삭제 등)」 표시라 쓰지 않는다.
--
-- Tibero 짝: db/migration-tibero/V163 (MigrationParityTest).
SET LOCAL lock_timeout = '3s';

ALTER TABLE dataset_search_index ADD COLUMN IF NOT EXISTS generation BIGINT NOT NULL DEFAULT 1;

COMMENT ON COLUMN dataset_search_index.generation IS
  '행 검색 색인 세대 — swap 재구축마다 1 증가. Qdrant 색인은 이 세대의 점만 검색한다(WD-722)';
