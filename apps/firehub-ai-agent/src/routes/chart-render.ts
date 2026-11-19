import { Router, Request, Response } from 'express';
import { renderChartPng } from './chart-png.js';
import { authedJsonBody } from '../http/json-body.js';
import { isPositiveInteger } from '../utils.js';

/**
 * 렌더를 지원하는 차트 종류 — 런타임 검증과 `ChartRequest.type` 타입이 함께 쓰는 단일 목록(WD-806).
 * 요청 본문은 TS 타입으로 보장되지 않으므로 런타임에 이 목록으로 거른다.
 */
const ALLOWED_TYPES = ['bar', 'pie', 'line', 'doughnut'] as const;
type ChartType = (typeof ALLOWED_TYPES)[number];

/** 요청당 네이티브 캔버스 메모리 할당을 제한하기 위한 한 변 최대 픽셀(WD-806). */
const MAX_DIMENSION = 2000;

/**
 * 렌더 전에 모든 차트 요청을 검증한다(하나라도 잘못되면 아무것도 그리지 않는다).
 * width/height 는 주어졌다면 1..MAX_DIMENSION 의 정수여야 한다.
 * @returns 오류 메시지, 유효하면 null
 */
function validateCharts(charts: ChartRequest[]): string | null {
  for (let i = 0; i < charts.length; i++) {
    const c = charts[i];
    if (!c || !(ALLOWED_TYPES as readonly unknown[]).includes(c.type)) {
      return `charts[${i}].type must be one of: ${ALLOWED_TYPES.join(', ')}`;
    }
    for (const field of ['width', 'height'] as const) {
      const value: unknown = c[field];
      if (value !== undefined && !(isPositiveInteger(value) && value <= MAX_DIMENSION)) {
        return `charts[${i}].${field} must be an integer between 1 and ${MAX_DIMENSION}`;
      }
    }
  }
  return null;
}

/**
 * 한 요청에서 그릴 수 있는 최대 차트 수.
 * 차트를 차례로 그리므로 개수가 곧 CPU 점유 시간이 된다. 보고서는 카드 섹션마다 차트 하나라 50 개면 넉넉하다.
 */
const MAX_CHARTS = 50;

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
  type: ChartType;
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
  ...authedJsonBody(),
  async (req: Request, res: Response): Promise<void> => {
    const body = req.body as ChartRenderRequest;

    if (!body.charts || !Array.isArray(body.charts) || body.charts.length === 0) {
      res.status(400).json({ error: 'charts array is required and must not be empty' });
      return;
    }

    // 개수 상한 초과는 하나도 그리기 전에 막는다(CPU 시간 독점 방지)
    if (body.charts.length > MAX_CHARTS) {
      res.status(400).json({ error: `charts must contain at most ${MAX_CHARTS} items` });
      return;
    }

    const validationError = validateCharts(body.charts);
    if (validationError) {
      res.status(400).json({ error: validationError });
      return;
    }

    try {
      const images: ChartImage[] = [];

      for (let i = 0; i < body.charts.length; i++) {
        const chart = body.charts[i];
        const width = chart.width ?? 600;
        const height = chart.height ?? 400;

        const configuration = {
          type: chart.type,
          data: chart.data,
          options: {
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

        // WD-806: @napi-rs/canvas 기반 렌더(반응형·애니메이션 off·흰 배경은 renderChartPng 가 처리)
        const buffer = await renderChartPng(configuration, width, height);
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
