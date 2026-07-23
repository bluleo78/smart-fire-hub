import { Router } from 'express';
import { internalAuth } from '../middleware/auth.js';
import { readWholeGraph, renameEntityType } from '../graphrag/neo4j-client.js';

// 온톨로지 시각화용 읽기 전용 라우터. 온톨로지 스키마는 api DB 소유로 이관됨(이 라우트 제거).
const router = Router();

// 전체 지식그래프(Neo4j). 읽기 실패 시 502(상위 프록시가 그대로 전파).
router.get('/graph', internalAuth, async (_req, res) => {
  try {
    res.json(await readWholeGraph());
  } catch {
    res.status(502).json({ error: 'graph read failed' });
  }
});

// 엔티티 타입 리네임(5-5) — api가 DB 리네임 커밋 직후 동기 호출하는 best-effort 마이그레이션.
// 실패(Neo4j 다운, key 충돌 등)는 502 — api는 이를 삼키고 로그만 남긴다(호출부 참조).
router.post('/graph/rename-type', internalAuth, async (req, res) => {
  const { oldType, newType } = req.body as { oldType?: string; newType?: string };
  if (!oldType || !newType) {
    res.status(400).json({ error: 'oldType/newType required' });
    return;
  }
  try {
    const migrated = await renameEntityType(oldType, newType);
    res.json({ migrated });
  } catch {
    res.status(502).json({ error: 'rename migration failed' });
  }
});

export default router;
