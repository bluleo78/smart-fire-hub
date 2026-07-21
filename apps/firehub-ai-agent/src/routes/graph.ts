import { Router } from 'express';
import { internalAuth } from '../middleware/auth.js';
import { readWholeGraph } from '../graphrag/neo4j-client.js';
import { serializeOntology } from '../graphrag/ontology.js';

// 온톨로지 시각화용 읽기 전용 라우터. /agent 프리픽스로 마운트된다.
const router = Router();

// 온톨로지 스키마(정적) — CORE_ONTOLOGY 직렬화.
router.get('/ontology', internalAuth, (_req, res) => {
  res.json(serializeOntology());
});

// 전체 지식그래프(Neo4j). 읽기 실패 시 502(상위 프록시가 그대로 전파).
router.get('/graph', internalAuth, async (_req, res) => {
  try {
    res.json(await readWholeGraph());
  } catch {
    res.status(502).json({ error: 'graph read failed' });
  }
});

export default router;
