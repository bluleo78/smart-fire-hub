import express, { Router, Request, Response } from 'express';
import { z } from 'zod/v4';
import { internalAuth } from '../middleware/auth.js';
import { ProviderFactory } from '../providers/index.js';

const router = Router();

// 2mb body limit for batch requests (up to 100 rows with long text)
const jsonParser = express.json({ limit: '2mb' });

const outputColumnSchema = z.object({
  name: z.string().min(1),
  type: z.enum(['TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIMESTAMP']),
});

const classifyRequestSchema = z.object({
  rows: z.array(z.record(z.string(), z.unknown())).min(1, 'rows must not be empty'),
  prompt: z.string().min(1),
  outputColumns: z.array(outputColumnSchema).min(1, 'outputColumns must not be empty'),
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

  const { rows, prompt, outputColumns } = parseResult.data;

  const apiBaseUrl = process.env.API_BASE_URL || 'http://localhost:8080/api/v1';
  const internalToken = process.env.INTERNAL_SERVICE_TOKEN || '';
  const userId = parseInt(req.headers['x-on-behalf-of'] as string) || 1;

  try {
    const provider = ProviderFactory.createClassifyProvider(apiBaseUrl, internalToken);
    const result = await provider.classify({ rows, prompt, outputColumns, userId });
    res.json(result);
  } catch (error: unknown) {
    const errorMessage = error instanceof Error ? error.message : String(error);
    console.error('[Classify] Error:', errorMessage);
    res.status(500).json({ error: 'Classification failed', details: errorMessage });
  }
});

export default router;
