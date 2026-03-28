import express, { Router, Request, Response } from 'express';
import { ChartJSNodeCanvas } from 'chartjs-node-canvas';
import { internalAuth } from '../middleware/auth.js';

const canvasCache = new Map<string, ChartJSNodeCanvas>();

function getCanvas(width: number, height: number): ChartJSNodeCanvas {
  const key = `${width}x${height}`;
  let canvas = canvasCache.get(key);
  if (!canvas) {
    canvas = new ChartJSNodeCanvas({ width, height, backgroundColour: 'white' });
    canvasCache.set(key, canvas);
  }
  return canvas;
}

const router = Router();

interface ChartDataset {
  label?: string;
  data: number[];
  backgroundColor?: string | string[];
  borderColor?: string | string[];
  borderWidth?: number;
}

interface ChartData {
  labels: string[];
  datasets: ChartDataset[];
}

interface ChartRequest {
  type: 'bar' | 'pie' | 'line' | 'doughnut';
  title: string;
  data: ChartData;
  width?: number;
  height?: number;
}

interface ChartRenderRequest {
  charts: ChartRequest[];
}

interface ChartImage {
  id: string;
  base64: string;
  mimeType: 'image/png';
}

interface ChartRenderResponse {
  images: ChartImage[];
}

router.post(
  '/chart-render',
  express.json(),
  internalAuth,
  async (req: Request, res: Response): Promise<void> => {
    const body = req.body as ChartRenderRequest;

    if (!body.charts || !Array.isArray(body.charts) || body.charts.length === 0) {
      res.status(400).json({ error: 'charts array is required and must not be empty' });
      return;
    }

    try {
      const images: ChartImage[] = [];

      for (let i = 0; i < body.charts.length; i++) {
        const chart = body.charts[i];
        const width = chart.width ?? 600;
        const height = chart.height ?? 400;

        const canvas = getCanvas(width, height);

        const configuration = {
          type: chart.type,
          data: chart.data,
          options: {
            responsive: false,
            plugins: {
              title: {
                display: chart.title != null && chart.title !== '',
                text: chart.title,
                font: {
                  size: 16,
                  weight: 'bold' as const,
                },
                color: '#1a1a2e',
                padding: { bottom: 12 },
              },
              legend: {
                display: true,
                position: 'bottom' as const,
                labels: {
                  font: { size: 12 },
                  color: '#495057',
                  padding: 16,
                },
              },
            },
            scales:
              chart.type === 'bar' || chart.type === 'line'
                ? {
                    x: {
                      ticks: { font: { size: 12 }, color: '#495057' },
                      grid: { color: '#e9ecef' },
                    },
                    y: {
                      ticks: { font: { size: 12 }, color: '#495057' },
                      grid: { color: '#e9ecef' },
                      beginAtZero: true,
                    },
                  }
                : undefined,
          },
        };

        const buffer = await canvas.renderToBuffer(configuration);
        const base64 = buffer.toString('base64');

        images.push({
          id: `chart-${i}`,
          base64,
          mimeType: 'image/png',
        });
      }

      const response: ChartRenderResponse = { images };
      res.json(response);
    } catch (error: unknown) {
      const message = error instanceof Error ? error.message : String(error);
      console.error('[ChartRender] Error:', message);
      res.status(500).json({ error: 'Chart rendering failed', details: message });
    }
  },
);

export default router;
