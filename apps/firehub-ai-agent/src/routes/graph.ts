import { Router } from 'express';
import { z } from 'zod/v4';
import { internalAuth } from '../middleware/auth.js';
import { readWholeGraph } from '../graphrag/neo4j-client.js';
import { mergeEntities } from '../graphrag/synonym-merge.js';
import { EntityType } from '../graphrag/ontology.js';

// 온톨로지 시각화용 읽기 전용 + HITL 승인 병합 라우터. 온톨로지 스키마는 api DB 소유로 이관됨(이 라우트 제거).
const router = Router();

// 전체 지식그래프(Neo4j). 읽기 실패 시 502(상위 프록시가 그대로 전파).
router.get('/graph', internalAuth, async (_req, res) => {
  try {
    res.json(await readWholeGraph());
  } catch {
    res.status(502).json({ error: 'graph read failed' });
  }
});

const mergeBodySchema = z.object({
  entityType: z.string().min(1),
  nameA: z.string().min(1),
  nameB: z.string().min(1),
});

// HITL 승인된 근접쌍 동기 병합 — firehub-api(SynonymMergeClient)가 승인 시 호출한다.
router.post('/graph/merge-entities', internalAuth, async (req, res) => {
  const parsed = mergeBodySchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: 'invalid request body', details: parsed.error.issues });
    return;
  }
  try {
    await mergeEntities(parsed.data.entityType as EntityType, parsed.data.nameA, parsed.data.nameB);
    res.status(204).send();
  } catch {
    res.status(502).json({ error: 'entity merge failed' });
  }
});

export default router;
