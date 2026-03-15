import express, { Router, Request, Response } from 'express';
import { z } from 'zod/v4';
import { internalAuth } from '../middleware/auth.js';
import { classifyBatch } from '../services/classification-service.js';

const router = Router();

// 2mb body limit for batch requests (up to 100 rows with long text)
const jsonParser = express.json({ limit: '2mb' });

const classifyRowSchema = z.object({
  rowId: z.string().min(1),
  text: z.string(),
});

const classifyRequestSchema = z.object({
  rows: z.array(classifyRowSchema).min(1, 'rows must not be empty'),
  labels: z.array(z.string().min(1)).min(2, 'at least 2 labels are required'),
  promptTemplate: z.string().min(1),
  promptVersion: z.string().min(1),
});

router.post('/classify', jsonParser, internalAuth, async (req: Request, res: Response) => {
  const parseResult = classifyRequestSchema.safeParse(req.body);
  if (!parseResult.success) {
    res.status(400).json({
      error: 'Invalid request',
      details: parseResult.error.issues,
    });
    return;
  }

  const { rows, labels, promptTemplate, promptVersion } = parseResult.data;

  const apiBaseUrl = process.env.API_BASE_URL || 'http://localhost:8080/api/v1';
  const internalToken = process.env.INTERNAL_SERVICE_TOKEN || '';
  const userId = parseInt(req.headers['x-on-behalf-of'] as string) || 1;

  try {
    const result = await classifyBatch(
      { rows, labels, promptTemplate, promptVersion },
      apiBaseUrl,
      internalToken,
      userId,
    );
    res.json(result);
  } catch (error: unknown) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    console.error('[Classify] Error:', errorMessage);
    res.status(500).json({ error: 'Classification failed', details: errorMessage });
  }
});

export default router;
