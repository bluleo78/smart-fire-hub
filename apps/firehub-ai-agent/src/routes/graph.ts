import { Router } from 'express';
import { internalAuth } from '../middleware/auth.js';
import { readWholeGraph } from '../graphrag/neo4j-client.js';

// 온톨로지 시각화용 읽기 전용 라우터. 온톨로지 스키마는 api DB 소유로 이관됨(이 라우트 제거).
// 5-6: 엔티티 타입 리네임은 이제 순수 DB 연산(entity_type_id 보존)이라 Neo4j 마이그레이션 라우트가
// 불필요해져 제거했다(5-5의 POST /graph/rename-type — resolver.ts entityKey 참조).
const router = Router();

// 전체 지식그래프(Neo4j). 읽기 실패 시 502(상위 프록시가 그대로 전파).
router.get('/graph', internalAuth, async (_req, res) => {
  try {
    res.json(await readWholeGraph());
  } catch {
    res.status(502).json({ error: 'graph read failed' });
  }
});

export default router;
