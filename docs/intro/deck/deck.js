/*
 * 슬라이드 보조 스크립트.
 *
 * 1) 잘라 보기 — [data-crop="x,y,w,h"](원본 px) 상자에 원본 캡처의 그 영역만 상자 폭에 맞춰 보여 준다.
 *    원본 PNG 는 가공하지 않는다(원본 파일이 곧 납품용 원본).
 * 2) 표시(annotation) — 화면 위 번호 표시를 촬영 때 기록한 실제 요소 좌표(shots/boxes.js 의 SHOT_BOXES)로 그린다.
 *    눈대중 퍼센트 좌표를 쓰지 않기 위해서다. 종류는 두 가지:
 *    - .ann.area  : 영역 — 빨간 테두리 투명 박스 + 왼쪽 위 번호
 *    - .ann.point : 지점 — 번호 원 + 대상 중심까지 이어진 지시선(data-side 로 번호 위치: left|right|top|bottom)
 *    data-box="샷이름:대상이름" 으로 좌표를 찾고, 없으면 data-xywh(원본 px)를 쓴다.
 */
(function () {
  const BOXES = window.SHOT_BOXES || {};

  /** 상자(.shot 또는 .crop) 안 원본 이미지의 배율과 잘라 낸 원점. */
  function geometry(box) {
    const img = box.querySelector(':scope > img');
    if (box.dataset.crop) {
      const [x, y, w] = box.dataset.crop.split(',').map(Number);
      return { scale: box.clientWidth / w, ox: x, oy: y, img };
    }
    return { scale: img.clientWidth / img.naturalWidth, ox: 0, oy: 0, img };
  }

  function layoutCrop(box) {
    const [x, y, w, h] = box.dataset.crop.split(',').map(Number);
    const img = box.querySelector(':scope > img');
    // data-max-h: 세로로 긴 영역이 슬라이드 밖으로 넘치지 않게 높이를 묶고, 그만큼 폭을 줄여 가운데 둔다
    const maxH = Number(box.dataset.maxH || Infinity);
    const scale = Math.min(box.clientWidth / w, maxH / h);
    if (w * scale < box.clientWidth) {
      box.style.width = `${w * scale}px`;
      box.style.margin = '0 auto';
    }
    box.style.height = `${h * scale}px`;
    img.style.width = `${img.naturalWidth * scale}px`;
    img.style.left = `${-x * scale}px`;
    img.style.top = `${-y * scale}px`;
  }

  function rectOf(ann) {
    if (ann.dataset.box) {
      const [shot, key] = ann.dataset.box.split(':');
      const r = BOXES[shot] && BOXES[shot][key];
      if (!r) {
        ann.classList.add('missing');
        console.warn('표시 좌표 없음:', ann.dataset.box);
        return null;
      }
      return r;
    }
    const [x, y, w, h] = ann.dataset.xywh.split(',').map(Number);
    return { x, y, w, h };
  }

  // 번호 원이 잘리지 않게 확인할 경계: 가장 가까운 overflow:hidden 조상(프레임·확대 패널), 없으면 슬라이드
  function clipRect(box) {
    for (let el = box; el; el = el.parentElement) {
      if (getComputedStyle(el).overflow !== 'visible' || el.classList.contains('slide')) return el.getBoundingClientRect();
    }
    return document.body.getBoundingClientRect();
  }

  const BADGE = 20; // 번호 원 반지름 + 흰 테두리 여유
  const inside = (c, x, y, m = BADGE + 4) => x - m >= c.left && x + m <= c.right && y - m >= c.top && y + m <= c.bottom;

  function placeAnnotations(box) {
    const g = geometry(box);
    const clip = clipRect(box);
    const origin = box.getBoundingClientRect();
    const pad = Number(box.dataset.pad || 6); // 영역 박스를 대상보다 조금 넉넉하게
    box.querySelectorAll(':scope > .ann').forEach((ann) => {
      const r = rectOf(ann);
      if (!r) return;
      const x = (r.x - g.ox) * g.scale;
      const y = (r.y - g.oy) * g.scale;
      const w = r.w * g.scale;
      const h = r.h * g.scale;
      ann.dataset.label = ann.dataset.n || '';
      if (ann.classList.contains('area')) {
        // 잘라 보기 밖으로 넘치는 박스는 상자 안쪽에 맞춰 네 변이 모두 보이게 한다
        const bw = box.clientWidth;
        const bh = box.clientHeight || Infinity;
        const l = Math.max(x - pad, 4);
        const t = Math.max(y - pad, 4);
        const r = Math.min(x + w + pad, bw - 4);
        const btm = Math.min(y + h + pad, bh - 4);
        Object.assign(ann.style, { left: `${l}px`, top: `${t}px`, width: `${r - l}px`, height: `${btm - t}px` });
        // 왼쪽 위 바깥 번호가 경계 밖으로 잘리면 박스 안쪽 모서리로 옮긴다
        if (!inside(clip, origin.left + l - 14, origin.top + t - 14)) {
          ann.style.setProperty('--bx', '6px');
          ann.style.setProperty('--by', '6px');
        }
      } else {
        // 지점: 대상의 side 쪽 가장자리 중앙에 점(글자를 가리지 않게), 거기서 바깥으로 len 만큼 지시선 + 번호 원
        const side = ann.dataset.side || 'left';
        let cx = side === 'left' ? x - 4 : side === 'right' ? x + w + 4 : x + w / 2;
        let cy = side === 'top' ? y - 4 : side === 'bottom' ? y + h + 4 : y + h / 2;
        const len = Number(ann.dataset.len || 56);
        let dx = side === 'left' ? -len : side === 'right' ? len : 0;
        let dy = side === 'top' ? -len : side === 'bottom' ? len : 0;
        // 번호 원이 경계 밖으로 잘리면 반대쪽 가장자리에서 반대 방향으로 뽑는다
        if (!inside(clip, origin.left + cx + dx, origin.top + cy + dy)) {
          const ox = side === 'left' ? w + 8 : side === 'right' ? -(w + 8) : 0;
          const oy = side === 'top' ? h + 8 : side === 'bottom' ? -(h + 8) : 0;
          cx += ox; cy += oy; dx = -dx; dy = -dy;
          console.warn('번호 위치 반전:', ann.dataset.box);
        }
        Object.assign(ann.style, { left: `${cx}px`, top: `${cy}px` });
        ann.style.setProperty('--dx', `${dx}px`);
        ann.style.setProperty('--dy', `${dy}px`);
        ann.style.setProperty('--len', `${Math.hypot(dx, dy)}px`);
        ann.style.setProperty('--ang', `${Math.atan2(dy, dx)}rad`);
      }
    });
  }

  /** data-crop-box="샷:대상" — 기록된 요소 위치를 중심으로 잘라 보기 영역을 만든다(data-crop-pad: 여백, 원본 px). */
  function resolveCropBox(el) {
    const [shot, key] = el.dataset.cropBox.split(':');
    const r = BOXES[shot] && BOXES[shot][key];
    if (!r) {
      console.warn('잘라 보기 좌표 없음:', el.dataset.cropBox);
      el.dataset.crop = '0,0,2880,1620';
      return;
    }
    const pad = Number(el.dataset.cropPad || 40);
    const x = Math.max(0, r.x - pad);
    const y = Math.max(0, r.y - pad);
    el.dataset.crop = [x, y, r.w + pad * 2, r.h + pad * 2].join(',');
  }

  function run() {
    document.querySelectorAll('[data-crop-box]').forEach(resolveCropBox);
    document.querySelectorAll('[data-crop]').forEach(layoutCrop);
    document.querySelectorAll('.shot, [data-crop]').forEach(placeAnnotations);
    document.body.dataset.ready = 'true';
  }

  const imgs = [...document.images];
  Promise.all(imgs.map((i) => (i.complete ? Promise.resolve() : new Promise((r) => (i.onload = i.onerror = r))))).then(run);
})();
