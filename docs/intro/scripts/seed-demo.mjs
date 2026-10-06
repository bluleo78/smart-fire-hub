#!/usr/bin/env node
/**
 * 소개 자료 촬영용 가상 회사 데이터를 격리 스택에 넣는다.
 *
 *   INTRO_AI_TOKEN=<AI 에이전트 토큰> node docs/intro/scripts/seed-demo.mjs
 *
 * 왜 이 주제인가: 제품의 핵심은 "밖에 내보낼 수 없는 데이터를 조직 안에서 다룬다"이다. 그래서 시연 데이터도
 * 회사가 Public AI·SaaS 플랫폼에 함부로 올리지 않는 매출·매입 전표와 거래처(고객) 정보로 잡는다.
 *
 * 왜 가상 데이터인가: 소개 자료는 고객사에 나가므로 운영 데이터를 찍지 않는다.
 * 회사(누리테크)·거래처·공급사·담당자 이름은 모두 지어낸 것이다. 연락처는 뒷자리를 가린 형태로만 넣고
 * 메일은 예약 도메인(example.com)만 쓴다. 행 데이터는 고정 시드 난수로 만든다 —
 * 다시 돌려도 같은 숫자가 나와야 차트·대시보드 장면이 매번 같은 모양이 된다.
 *
 * 빈 DB(첫 가입 전)에서만 돈다 — 첫 가입자가 ADMIN 이 되는 규칙을 이용하고, 이미 사용자가 있으면
 * 공유 DB 를 오염시키지 않도록 즉시 멈춘다.
 *
 * AI 결과물(채팅 답변·리포트·분류 결과)은 여기서 만들지 않는다 — seed-ai.mjs 가 실제로 요청해 만든다.
 */
import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const API = (process.env.INTRO_API ?? 'http://localhost:5010') + '/api/v1';
export const PASSWORD = process.env.INTRO_PASS ?? 'IntroShot1!';
const DOMAIN = 'example.com';
export const ORG = '㈜누리테크';

/** 등장인물 — 첫 항목이 첫 가입자(ADMIN)이자 촬영 계정이다. */
export const PEOPLE = [
  { key: 'admin', username: `jihyun@${DOMAIN}`, name: '김지현', role: 'ADMIN' },
  { key: 'finance', username: `minsu@${DOMAIN}`, name: '이민수', role: 'USER' },
  { key: 'salesops', username: `sora@${DOMAIN}`, name: '박소라', role: 'USER' },
  { key: 'sales', username: `taeho@${DOMAIN}`, name: '정태호', role: 'USER' },
];

/** JSON API 호출. 실패하면 응답 본문을 붙여 던져 원인을 바로 보이게 한다. */
export async function call(token, method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${await res.text()}`);
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

export async function login(username, password = PASSWORD) {
  return (await call(null, 'POST', '/auth/login', { username, password })).accessToken;
}

/** 고정 시드 난수(mulberry32) — 실행마다 같은 데이터를 만든다. */
function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = rng(20261006);
const pick = (arr) => arr[Math.floor(rand() * arr.length)];
/** 가중치 선택: [[값, 가중치], ...] */
function weighted(pairs) {
  const total = pairs.reduce((s, [, w]) => s + w, 0);
  let r = rand() * total;
  for (const [v, w] of pairs) if ((r -= w) <= 0) return v;
  return pairs[pairs.length - 1][0];
}
/** 대략 정규분포(평균 m, 표준편차 s) — 12개 균등 합 근사. */
function normal(m, s) {
  let x = 0;
  for (let i = 0; i < 12; i++) x += rand();
  return m + (x - 6) * s;
}
const pad = (n) => String(n).padStart(2, '0');
/** 금액은 만 원 단위로 끊는다(전표처럼 보이게). */
const won = (v) => Math.round(v / 10000) * 10000;

/** 오늘 기준 상대 날짜(KST 기준 YYYY-MM-DD). 촬영 시점이 달라도 "최근 12개월"이 지금 주변에 놓인다. */
function day(offset) {
  const d = new Date(Date.now() + 9 * 3600 * 1000);
  d.setUTCDate(d.getUTCDate() + offset);
  return d.toISOString().slice(0, 10);
}
const TODAY = day(0);
/** YYYY-MM-DD 에 일수를 더한다. */
function addDays(date, n) {
  const d = new Date(date + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

/** 영업권역 — 중심 좌표·흩어짐·거래처 가중치·담당 영업. 좌표는 지도 바탕(실제 지도) 위에 거래처를 흩뿌리는 용도다. */
const REGIONS = [
  { name: '수도권', lng: 126.98, lat: 37.48, spread: 0.16, w: 38, reps: ['최유진', '한서준'] },
  { name: '충청권', lng: 127.38, lat: 36.4, spread: 0.18, w: 18, reps: ['오지훈'] },
  { name: '영남권', lng: 128.85, lat: 35.45, spread: 0.32, w: 26, reps: ['윤하린', '강민재'] },
  { name: '호남권', lng: 126.88, lat: 35.12, spread: 0.18, w: 12, reps: ['서예린'] },
  { name: '강원권', lng: 128.25, lat: 37.65, spread: 0.25, w: 6, reps: ['문태오'] },
];

/** 거래처 이름 — 실존 회사와 겹치지 않도록 흔치 않은 순우리말 어간 + 업종 꼬리로 짓는다. */
const STEMS = ['가온', '온새', '다솜', '해솔', '로운', '새길', '미리내', '나래', '윤슬', '라온', '다올', '하람', '여울', '버금', '새온', '든솔'];
const TAILS = ['정밀', '오토텍', '이엔지', '메디칼', '시스템즈', '전장'];
const INDUSTRIES = ['자동차 부품', '전자 제조', '조선 기자재', '의료기기', '산업 설비', '에너지 저장'];
const SURNAMES = ['김', '이', '박', '최', '정', '강', '조', '윤', '장', '임', '한', '오'];
const GIVEN = ['민준', '서연', '지후', '하은', '도현', '수아', '예준', '지민', '현우', '유나', '건우', '다인'];

/** 제품군 — 기준 단가(공급가액)와 매출총이익률. */
const PRODUCTS = {
  '센서 모듈': { w: 30, base: 9_000_000, margin: 0.32 },
  '제어 보드': { w: 24, base: 14_000_000, margin: 0.27 },
  '배터리 팩': { w: 16, base: 22_000_000, margin: 0.17 },
  '커넥터·케이블': { w: 20, base: 4_000_000, margin: 0.24 },
  '유지보수 서비스': { w: 10, base: 6_000_000, margin: 0.55 },
};

/** 공급사(매입처) — 이름은 지어낸 것. */
const SUPPLIERS = [
  ['청람소재', '원자재'], ['정우금속', '원자재'], ['한별전자부품', '전자부품'], ['라임PCB', '전자부품'],
  ['에이온반도체유통', '전자부품'], ['다인셀', '셀·배터리'], ['이음케미칼', '원자재'], ['새벽정공', '외주가공'],
  ['민솔가공', '외주가공'], ['소망물류', '물류'], ['온결포장', '물류'], ['태림설비', '설비·유지보수'],
];

function makeCustomers() {
  const customers = [];
  let i = 0;
  for (const stem of STEMS) {
    for (const tail of [TAILS[i % TAILS.length], TAILS[(i * 5 + 3) % TAILS.length], TAILS[(i * 7 + 1) % TAILS.length]].slice(0, 3)) {
      if (customers.length >= 48) break;
      const name = `${stem}${tail}`;
      if (customers.some((c) => c.name === name)) continue;
      const r = weighted(REGIONS.map((x) => [x, x.w]));
      const n = customers.length + 1;
      customers.push({
        id: `C${String(n).padStart(4, '0')}`,
        name,
        industry: pick(INDUSTRIES),
        region: r.name,
        rep: pick(r.reps),
        size: Math.exp(normal(0, 0.8)), // 거래 규모(로그정규) — 등급과 주문 빈도를 정한다
        contact: `${pick(SURNAMES)}${pick(GIVEN)}`,
        phone: `010-${1000 + Math.floor(rand() * 9000)}-****`,
        email: `buyer${String(n).padStart(2, '0')}@${DOMAIN}`,
        lng: Math.round((r.lng + normal(0, r.spread)) * 1e5) / 1e5,
        lat: Math.round((r.lat + normal(0, r.spread * 0.7)) * 1e5) / 1e5,
      });
    }
    i++;
  }
  // 거래 규모 순위로 등급을 매긴다: 상위 10% VIP, 다음 20% A, 40% B, 나머지 C
  const ranked = [...customers].sort((a, b) => b.size - a.size);
  ranked.forEach((c, k) => {
    const q = k / ranked.length;
    c.grade = q < 0.1 ? 'VIP' : q < 0.3 ? 'A' : q < 0.7 ? 'B' : 'C';
    c.onTime = { VIP: 0.97, A: 0.93, B: 0.87, C: 0.7 }[c.grade]; // 만기 내 입금 확률
    c.credit = { VIP: 3000, A: 1500, B: 800, C: 300 }[c.grade]; // 여신 한도(백만 원)
  });
  return customers;
}

function makeLedgers(customers) {
  const sales = [];
  const purchases = [];
  let sSeq = 1;
  let pSeq = 1;
  for (let offset = -364; offset <= 0; offset++) {
    const date = day(offset);
    const dow = new Date(date + 'T00:00:00Z').getUTCDay();
    if (dow === 0 || dow === 6) continue; // 전표는 영업일에만
    const m = Number(date.slice(5, 7));
    const dd = Number(date.slice(8, 10));
    // 1년 동안 완만히 성장(+15%), 분기말(3·6·9·12월 하순)에 매출이 몰린다
    const growth = 1 + 0.15 * ((offset + 364) / 364);
    const qEnd = [3, 6, 9, 12].includes(m) && dd >= 20 ? 1.5 : 1;
    const n = Math.max(3, Math.round(normal(10 * growth * qEnd, 2.5)));
    for (let i = 0; i < n; i++) {
      const c = weighted(customers.map((x) => [x, x.size]));
      const product = weighted(Object.entries(PRODUCTS).map(([k, v]) => [k, v.w]));
      const p = PRODUCTS[product];
      const gradeF = { VIP: 1.8, A: 1.3, B: 1, C: 0.7 }[c.grade];
      const amount = won(Math.min(400_000_000, p.base * gradeF * Math.exp(normal(0, 0.55))));
      const margin = p.margin - (c.grade === 'VIP' ? 0.04 : 0) + normal(0, 0.03);
      const cost = won(amount * (1 - margin));
      const due = addDays(date, c.grade === 'VIP' ? 90 : 60);
      // 수금: 만기 전 조기 입금 / 만기 내 입금 / 연체(늦게 입금되거나 아직 미수)
      let paid = null;
      if (rand() < c.onTime) {
        const d = addDays(due, -Math.floor(rand() * 20));
        paid = d <= TODAY ? d : null;
      } else {
        const d = addDays(due, 5 + Math.floor(rand() * 45));
        paid = d <= TODAY ? d : null;
      }
      sales.push({
        invoice_no: `S${date.replace(/-/g, '').slice(2)}-${String(sSeq++).padStart(5, '0')}`,
        invoice_date: date,
        customer_id: c.id,
        customer_name: c.name,
        region: c.region,
        sales_rep: c.rep,
        product_line: product,
        amount,
        cost,
        due_date: due,
        paid_date: paid,
      });
    }
    const pn = Math.max(2, Math.round(normal(5 * growth, 1.5)));
    for (let i = 0; i < pn; i++) {
      const [supplier, category] = pick(SUPPLIERS);
      const base = { 원자재: 9e6, 전자부품: 12e6, '셀·배터리': 18e6, 외주가공: 7e6, 물류: 2.5e6, '설비·유지보수': 5e6 }[category];
      purchases.push({
        po_no: `P${date.replace(/-/g, '').slice(2)}-${String(pSeq++).padStart(5, '0')}`,
        po_date: date,
        supplier_name: supplier,
        category,
        amount: base * Math.exp(normal(0, 0.5)) * growth * qEnd,
      });
    }
  }
  // 매입 총액을 매출원가의 95% 근처로 맞춘다 — 월별 추이에서 매출 > 매입 > 매출총이익 순서가 자연스럽게 보이도록
  const cogs = sales.reduce((s, r) => s + r.cost, 0);
  const raw = purchases.reduce((s, r) => s + r.amount, 0);
  for (const r of purchases) r.amount = won(r.amount * ((cogs * 0.95) / raw));
  return { sales, purchases };
}

async function batch(token, datasetId, rows, size = 100) {
  for (let i = 0; i < rows.length; i += size) {
    await call(token, 'POST', `/datasets/${datasetId}/data/rows/batch`, { rows: rows.slice(i, i + size) });
  }
}

async function seed() {
  const status = await call(null, 'GET', '/auth/signup-status');
  if (!status.open) throw new Error('이미 사용자가 있는 DB 다 — 격리 스택(reset-stack.sh)에서만 실행한다');

  const [admin, ...others] = PEOPLE;
  await call(null, 'POST', '/auth/signup', { username: admin.username, email: admin.username, password: PASSWORD, name: admin.name });
  const token = await login(admin.username);

  // 동료 계정 — 관리자가 추가한다(공개 가입은 첫 1명만 열린다). 첫 로그인 비밀번호 변경 강제는 촬영 계정이 아니라 상관없다.
  for (const p of others) {
    await call(token, 'POST', '/users', { email: p.username, name: p.name, temporaryPassword: PASSWORD }).catch((e) =>
      console.warn(`  동료 추가 실패(${p.name}): ${e.message.split('\n')[0]}`),
    );
  }

  // AI 에이전트 자격증명 — 토큰은 환경변수로만 받는다(파일·커밋에 남기지 않는다).
  if (process.env.INTRO_AI_TOKEN) {
    const cred = { agentType: 'sdk', payload: {}, secret: { oauthToken: process.env.INTRO_AI_TOKEN } };
    await call(token, 'PUT', '/settings/ai-credential', cred);
    await call(token, 'PUT', '/settings/ai-classify-credential', { ...cred, model: process.env.INTRO_CLASSIFY_MODEL ?? 'claude-haiku-4-5-20251001' }).catch((e) => console.warn(`  분류 자격증명 실패: ${e.message.split('\n')[0]}`));
  }

  const catFin = await call(token, 'POST', '/dataset-categories', { name: '영업·재무', description: '매출·매입 전표와 수금 기록' });
  const catCust = await call(token, 'POST', '/dataset-categories', { name: '고객', description: '거래처 정보와 고객 문의' });

  const customers = makeCustomers();
  const { sales, purchases } = makeLedgers(customers);

  const dsSales = await call(token, 'POST', '/datasets', {
    name: '매출 전표',
    tableName: 'sales_ledger',
    description: 'ERP 에서 매일 받아 오는 매출 전표(최근 12개월). 거래처·제품군·공급가액·원가·만기일·입금일을 담는다.',
    categoryId: catFin.id,
    columns: [
      { columnName: 'invoice_no', displayName: '전표번호', dataType: 'VARCHAR', maxLength: 20, isNullable: false, isPrimaryKey: true },
      { columnName: 'invoice_date', displayName: '전표일자', dataType: 'DATE', isIndexed: true },
      { columnName: 'customer_id', displayName: '거래처코드', dataType: 'VARCHAR', maxLength: 10, isIndexed: true },
      { columnName: 'customer_name', displayName: '거래처명', dataType: 'VARCHAR', maxLength: 40 },
      { columnName: 'region', displayName: '영업권역', dataType: 'VARCHAR', maxLength: 10, isIndexed: true },
      { columnName: 'sales_rep', displayName: '영업담당', dataType: 'VARCHAR', maxLength: 20 },
      { columnName: 'product_line', displayName: '제품군', dataType: 'VARCHAR', maxLength: 20, isIndexed: true },
      { columnName: 'amount', displayName: '공급가액(원)', dataType: 'DECIMAL' },
      { columnName: 'cost', displayName: '원가(원)', dataType: 'DECIMAL' },
      { columnName: 'due_date', displayName: '수금만기일', dataType: 'DATE' },
      { columnName: 'paid_date', displayName: '입금일', dataType: 'DATE', isNullable: true, description: '아직 입금되지 않았으면 비어 있다' },
    ],
  });
  await batch(token, dsSales.id, sales);
  console.log(`  매출 전표 ${sales.length}건`);

  const dsPurchase = await call(token, 'POST', '/datasets', {
    name: '매입 전표',
    tableName: 'purchase_ledger',
    description: 'ERP 에서 매일 받아 오는 매입(발주) 전표(최근 12개월)',
    categoryId: catFin.id,
    columns: [
      { columnName: 'po_no', displayName: '발주번호', dataType: 'VARCHAR', maxLength: 20, isNullable: false, isPrimaryKey: true },
      { columnName: 'po_date', displayName: '발주일자', dataType: 'DATE', isIndexed: true },
      { columnName: 'supplier_name', displayName: '공급사', dataType: 'VARCHAR', maxLength: 40 },
      { columnName: 'category', displayName: '매입구분', dataType: 'VARCHAR', maxLength: 20 },
      { columnName: 'amount', displayName: '매입금액(원)', dataType: 'DECIMAL' },
    ],
  });
  await batch(token, dsPurchase.id, purchases);
  console.log(`  매입 전표 ${purchases.length}건`);

  const dsCustomer = await call(token, 'POST', '/datasets', {
    name: '거래처 정보',
    tableName: 'customers',
    description: '거래처 기본 정보와 구매 담당자 연락처, 등급·여신 한도·위치. 개인정보가 들어 있어 영업·재무 역할만 조회한다.',
    categoryId: catCust.id,
    columns: [
      { columnName: 'customer_id', displayName: '거래처코드', dataType: 'VARCHAR', maxLength: 10, isNullable: false, isPrimaryKey: true },
      { columnName: 'customer_name', displayName: '거래처명', dataType: 'VARCHAR', maxLength: 40 },
      { columnName: 'industry', displayName: '업종', dataType: 'VARCHAR', maxLength: 20 },
      { columnName: 'grade', displayName: '등급', dataType: 'VARCHAR', maxLength: 5 },
      { columnName: 'region', displayName: '영업권역', dataType: 'VARCHAR', maxLength: 10 },
      { columnName: 'contact_name', displayName: '구매담당자', dataType: 'VARCHAR', maxLength: 20 },
      { columnName: 'contact_phone', displayName: '연락처', dataType: 'VARCHAR', maxLength: 20 },
      { columnName: 'contact_email', displayName: '이메일', dataType: 'VARCHAR', maxLength: 60 },
      { columnName: 'credit_limit', displayName: '여신한도(백만원)', dataType: 'INTEGER' },
      { columnName: 'location', displayName: '위치', dataType: 'GEOMETRY' },
    ],
  });
  await batch(
    token,
    dsCustomer.id,
    customers.map((c) => ({
      customer_id: c.id,
      customer_name: c.name,
      industry: c.industry,
      grade: c.grade,
      region: c.region,
      contact_name: c.contact,
      contact_phone: c.phone,
      contact_email: c.email,
      credit_limit: c.credit,
      location: JSON.stringify({ type: 'Point', coordinates: [c.lng, c.lat] }),
    })),
  );

  // 오늘 접수된 고객 문의 원문 — AI 분류 파이프라인(긴급도 판정)의 입력. 분류 결과는 seed-ai.mjs 가 파이프라인을 돌려 AI 가 만든다.
  const dsInquiry = await call(token, 'POST', '/datasets', {
    name: '고객 문의 접수',
    tableName: 'customer_inquiries',
    description: '메일·전화·영업담당을 통해 들어온 거래처 문의·클레임 원문(당일분). 긴급도 분류 파이프라인의 입력',
    categoryId: catCust.id,
    columns: [
      { columnName: 'inquiry_no', displayName: '접수번호', dataType: 'VARCHAR', maxLength: 20, isNullable: false, isPrimaryKey: true },
      { columnName: 'received_at', displayName: '접수시각', dataType: 'TIMESTAMP' },
      { columnName: 'customer_name', displayName: '거래처명', dataType: 'VARCHAR', maxLength: 40 },
      { columnName: 'channel', displayName: '접수경로', dataType: 'VARCHAR', maxLength: 10 },
      { columnName: 'inquiry_text', displayName: '문의내용', dataType: 'TEXT' },
    ],
  });
  await batch(
    token,
    dsInquiry.id,
    INQUIRIES.map(([hm, channel, text], i) => ({
      inquiry_no: `${TODAY.replace(/-/g, '').slice(2)}-Q${String(i + 1).padStart(3, '0')}`,
      received_at: `${TODAY}T${hm}:00`,
      customer_name: customers[(i * 7) % customers.length].name,
      channel,
      inquiry_text: text,
    })),
  );

  const ids = { sales: dsSales.id, purchase: dsPurchase.id, customer: dsCustomer.id, inquiry: dsInquiry.id, catFin: catFin.id, catCust: catCust.id };
  await seedAssets(token, ids);
  await seedKnowledge(token, ids);
  return { token, ids };
}

/** 오늘 들어온 문의 원문(시각, 경로, 내용). 긴급·보통·낮음이 고르게 섞이도록 고른다. 거래처는 목록에서 돌려 붙인다. */
const INQUIRIES = [
  ['08:12', '메일', '오늘 납품받은 제어 보드 200개 중 30개가 전원이 안 들어옵니다. 내일 아침 생산 라인이 멈출 수 있어요.'],
  ['08:40', '전화', '지난달 세금계산서 금액이 발주서와 12만 원 차이 납니다. 수정 발행 부탁드립니다.'],
  ['09:05', '메일', '새로 나온 제품 카탈로그 PDF를 받을 수 있을까요?'],
  ['09:20', '영업담당', '배터리 팩 한 개가 충전 중에 부풀어 올랐습니다. 같은 로트 제품은 전부 사용을 중지했습니다.'],
  ['09:48', '메일', '다음 달 물량을 20% 늘리려고 하는데 단가 조정이 가능한지 이번 주 안에 회신 바랍니다.'],
  ['10:15', '전화', '구매 담당자가 바뀌었습니다. 앞으로는 새 담당자에게 연락 부탁드립니다.'],
  ['10:32', '메일', '저희 담당자 메일로 다른 회사 견적서가 잘못 왔습니다. 어떻게 된 일인지 확인해 주세요.'],
  ['10:58', '영업담당', '센서 모듈 납기를 1주일 앞당길 수 있을지 가능 여부만 금요일까지 알려 주세요.'],
  ['11:20', '메일', '지난번 기술 지원 감사했습니다. 덕분에 문제가 잘 해결됐어요.'],
  ['11:45', '전화', '이번 주 금요일까지 센서 모듈이 안 들어오면 완성차 업체 납기를 못 맞춥니다. 위약금이 걸려 있어요.'],
  ['13:05', '메일', '유지보수 기사 방문 일정을 다음 주 화요일로 바꾸고 싶습니다.'],
  ['13:30', '메일', '내년 단가표는 언제쯤 받아 볼 수 있나요?'],
  ['13:52', '영업담당', '같은 커넥터 불량이 세 번째입니다. 이번에도 개선이 없으면 다음 분기 계약은 다른 업체로 넘기겠습니다.'],
  ['14:10', '전화', '거래명세서가 아직 안 왔습니다. 월말 정산 전에 보내 주세요.'],
  ['14:35', '메일', '세금계산서 받는 메일 주소를 바꾸고 싶습니다.'],
  ['14:58', '전화', '설비 제어기가 펌웨어 업데이트 뒤로 계속 재부팅됩니다. 공장 2라인이 지금 멈춰 있습니다.'],
  ['15:20', '메일', '제어 보드 최신 펌웨어 설치 방법을 안내받고 싶습니다.'],
  ['15:42', '메일', '전시회에서 본 신제품 샘플을 받아 볼 수 있을지 문의드립니다.'],
  ['16:05', '영업담당', '납품된 커넥터 상자에서 이물질이 나와 저희 고객사 출하가 보류됐습니다.'],
  ['16:30', '메일', '결제 조건을 60일에서 90일로 바꿀 수 있는지 다음 주까지 검토 부탁드립니다.'],
  ['16:55', '전화', '연말 감사 선물 잘 받았습니다. 감사합니다.'],
  ['17:20', '메일', '입금했는데 미수로 독촉 메일이 왔습니다. 입금 내역 확인 후 정정해 주세요.'],
  ['17:45', '영업담당', '회사가 이전해서 다음 달부터 납품지 주소가 바뀝니다.'],
  ['18:10', '메일', '배터리 팩 교체 주기에 관한 기술 자료를 받고 싶습니다.'],
];

/** 분석 자산(쿼리·차트·대시보드)·파이프라인·스마트 작업을 만든다. 실행(=AI 결과)은 seed-ai.mjs 가 맡는다. */
async function seedAssets(token, ids) {
  const q = (name, sqlText, description) =>
    call(token, 'POST', '/analytics/queries', { name, sqlText, description, datasetId: ids.sales, folder: '매출 분석', isShared: true });
  const chart = (name, savedQueryId, chartType, config, description) =>
    call(token, 'POST', '/analytics/charts', { name, savedQueryId, chartType, config, description, isShared: true });

  const qMonthly = await q(
    '월별 매출·매입 추이',
    // 선 차트는 계열을 열로 받는다 — 매출·매입·이익을 열로 둔다. 시작·이번 달은 한 달이 다 차지 않아 뺀다.
    `WITH s AS (
  SELECT date_trunc('month', invoice_date) AS m, sum(amount) AS sales, sum(amount - cost) AS profit
  FROM sales_ledger GROUP BY 1
), p AS (
  SELECT date_trunc('month', po_date) AS m, sum(amount) AS buy
  FROM purchase_ledger GROUP BY 1
)
SELECT to_char(s.m, 'YYYY-MM') AS 월,
       round(s.sales / 1e6) AS 매출,
       round(p.buy / 1e6) AS 매입,
       round(s.profit / 1e6) AS 매출총이익
FROM s JOIN p ON p.m = s.m
WHERE s.m >= date_trunc('month', now() - interval '11 months')
  AND s.m < date_trunc('month', now())
ORDER BY 1`,
    '최근 11개월 월별 매출·매입·매출총이익(백만 원)',
  );
  const qProduct = await q(
    '제품군별 매출 비중',
    `SELECT product_line AS 제품군, round(sum(amount) / 1e6) AS 매출
FROM sales_ledger
GROUP BY 1
ORDER BY 2 DESC`,
    '최근 12개월 제품군별 매출(백만 원)',
  );
  const qRegion = await q(
    '권역별 매출·이익률',
    `SELECT region AS 권역,
       round(sum(amount) / 1e6) AS 매출,
       round(100.0 * sum(amount - cost) / sum(amount), 1) AS 매출총이익률,
       count(DISTINCT customer_id) AS 거래처수
FROM sales_ledger
WHERE invoice_date >= current_date - 90
GROUP BY 1
ORDER BY 2 DESC`,
    '최근 90일 영업권역별 매출(백만 원)·매출총이익률(%)',
  );
  const qMap = await q(
    '거래처 분포(최근 90일 매출)',
    `SELECT c.customer_name AS 거래처, c.grade AS 등급, round(sum(s.amount) / 1e6) AS 매출, c.location
FROM customers c
JOIN sales_ledger s ON s.customer_id = c.customer_id AND s.invoice_date >= current_date - 90
GROUP BY c.customer_name, c.grade, c.location`,
  );
  const qHeat = await q(
    '권역·제품군별 매출',
    `SELECT region AS 권역, product_line AS 제품군, round(sum(amount) / 1e6) AS 매출
FROM sales_ledger
GROUP BY 1, 2
ORDER BY 1, 2`,
  );
  const qCollect = await q(
    '수금률',
    `SELECT round(100.0 * avg(CASE WHEN paid_date IS NOT NULL AND paid_date <= due_date THEN 1 ELSE 0 END), 1) AS 수금률
FROM sales_ledger
WHERE due_date BETWEEN current_date - 90 AND current_date`,
    '최근 90일 안에 만기가 돌아온 채권 중 만기 안에 입금된 비율',
  );
  const qScatter = await q(
    '거래처별 매출과 이익률',
    `SELECT customer_name AS 거래처, round(sum(amount) / 1e6) AS 매출, round(100.0 * sum(amount - cost) / sum(amount), 1) AS 이익률
FROM sales_ledger
GROUP BY 1
ORDER BY 2`,
    '거래처마다 최근 12개월 매출(백만 원)과 매출총이익률(%)',
  );
  await q(
    '연체 채권 현황',
    `SELECT customer_name AS 거래처, count(*) AS 연체건수, round(sum(amount) / 1e6, 1) AS 연체금액, max(current_date - due_date) AS 최장연체일
FROM sales_ledger
WHERE paid_date IS NULL AND due_date < current_date
GROUP BY 1
ORDER BY 3 DESC`,
    '만기가 지났는데 아직 입금되지 않은 채권(백만 원)',
  );

  const cMonthly = await chart('월별 매출·매입 추이', qMonthly.id, 'LINE', { xAxis: '월', yAxis: ['매출', '매입', '매출총이익'] });
  const cProduct = await chart('제품군별 매출 비중', qProduct.id, 'DONUT', { xAxis: '제품군', yAxis: ['매출'] });
  const cRegion = await chart('권역별 매출(최근 90일)', qRegion.id, 'BAR', { xAxis: '권역', yAxis: ['매출'] });
  const cMap = await chart('거래처 분포(최근 90일 매출)', qMap.id, 'MAP', { xAxis: '등급', yAxis: [], spatialColumn: 'location' });
  const cHeat = await chart('권역·제품군별 매출', qHeat.id, 'HEATMAP', { xAxis: '제품군', yAxis: ['권역'], valueColumn: '매출' });
  const cCollect = await chart('수금률(최근 90일 만기)', qCollect.id, 'GAUGE', { xAxis: '수금률', yAxis: ['수금률'], min: 0, max: 100, target: 90 });
  await chart('거래처별 매출과 이익률', qScatter.id, 'SCATTER', { xAxis: '매출', yAxis: ['이익률'] });
  // AI 분류 결과를 원문과 나란히 보는 쿼리 — 결과 테이블은 파이프라인 실행(seed-ai.mjs) 뒤에 채워진다
  await q(
    '오늘 고객 문의 긴급도(AI 분류)',
    `SELECT to_char(c.received_at, 'HH24:MI') AS 접수시각, c.customer_name AS 거래처, c.inquiry_text AS 문의내용,
       t.urgency AS 긴급도, t.category AS 유형, t.reason AS 판단근거
FROM customer_inquiries c
JOIN inquiry_triage t ON t.source_id = c.id
ORDER BY c.received_at`,
    '파이프라인의 AI 분류 단계가 판정한 결과를 문의 원문과 함께 본다',
  );

  const dash = await call(token, 'POST', '/analytics/dashboards', {
    name: '매출·수금 현황',
    description: '매출·매입 추이, 제품군 비중, 수금률, 거래처 분포를 한 화면에서 본다',
    isShared: true,
    autoRefreshSeconds: 300,
  });
  // 12열 그리드(행 높이 80px). 위: 게이지·도넛·막대 / 가운데: 추이 / 아래: 지도·히트맵
  const widgets = [
    [cCollect, 0, 0, 3, 4],
    [cProduct, 3, 0, 4, 4],
    [cRegion, 7, 0, 5, 4],
    [cMonthly, 0, 4, 12, 4],
    [cMap, 0, 8, 6, 5],
    [cHeat, 6, 8, 6, 5],
  ];
  for (const [c, x, y, w, h] of widgets) {
    await call(token, 'POST', `/analytics/dashboards/${dash.id}/widgets`, { chartId: c.id, positionX: x, positionY: y, width: w, height: h });
  }

  // 파이프라인 — 일별 매출 집계(SQL) + 고객 문의 긴급도 분류(AI). 출력 데이터셋은 미리 만들어 화면에서 이름이 보이게 한다.
  const dsDaily = await call(token, 'POST', '/datasets', {
    name: '일별 매출 집계',
    tableName: 'daily_sales_summary',
    description: '파이프라인이 매일 새벽 갱신하는 권역·제품군별 일 매출 집계',
    categoryId: ids.catFin,
    columns: [
      { columnName: 'stat_date', displayName: '집계일', dataType: 'DATE' },
      { columnName: 'region', displayName: '영업권역', dataType: 'VARCHAR', maxLength: 10 },
      { columnName: 'product_line', displayName: '제품군', dataType: 'VARCHAR', maxLength: 20 },
      { columnName: 'invoice_count', displayName: '전표수', dataType: 'INTEGER' },
      { columnName: 'sales_amount', displayName: '매출(원)', dataType: 'DECIMAL' },
      { columnName: 'gross_profit', displayName: '매출총이익(원)', dataType: 'DECIMAL' },
    ],
  });
  // AI 분류 출력도 이름 있는 데이터셋으로 둔다 — 비워 두면 "…(자동생성)" 임시 데이터셋이 목록·홈에 보인다.
  const dsTriage = await call(token, 'POST', '/datasets', {
    name: '고객 문의 분류 결과',
    tableName: 'inquiry_triage',
    description: '파이프라인의 AI 분류 단계가 문의 원문을 읽고 판정한 긴급도·유형·근거',
    categoryId: ids.catCust,
    columns: [
      { columnName: 'source_id', displayName: '원본 행', dataType: 'INTEGER' },
      { columnName: 'urgency', displayName: '긴급도', dataType: 'TEXT' },
      { columnName: 'category', displayName: '유형', dataType: 'TEXT' },
      { columnName: 'reason', displayName: '판단 근거', dataType: 'TEXT' },
    ],
  });
  const pipeline = await call(token, 'POST', '/pipelines', {
    name: '매출 데이터 일일 마감',
    description: '매일 새벽 매출 전표를 권역·제품군별로 집계하고, 당일 고객 문의의 긴급도를 AI 로 분류한다',
    steps: [
      {
        name: '권역·제품군별 일 집계',
        description: '매출 전표를 일·권역·제품군 단위로 집계',
        scriptType: 'SQL',
        scriptContent: `SELECT invoice_date AS stat_date, region, product_line,
       count(*) AS invoice_count, sum(amount) AS sales_amount, sum(amount - cost) AS gross_profit
FROM data."sales_ledger"
GROUP BY 1, 2, 3`,
        inputDatasetIds: [ids.sales],
        outputDatasetId: dsDaily.id,
        loadStrategy: 'REPLACE',
      },
      {
        name: '고객 문의 AI 분류',
        description: '문의 원문을 읽고 긴급도와 판단 근거를 분류',
        scriptType: 'AI_CLASSIFY',
        inputDatasetIds: [ids.inquiry],
        outputDatasetId: dsTriage.id,
        loadStrategy: 'REPLACE',
        dependsOnStepNames: ['권역·제품군별 일 집계'],
        aiConfig: {
          prompt:
            '거래처 문의 원문(inquiry_text)을 읽고 긴급도를 분류한다. urgency 는 다음 중 하나: "긴급" = 거래처 생산 라인 중단·대량 불량·안전 문제·계약 해지 언급·정보 유출 의심처럼 당장 대응하지 않으면 손실이 나는 건, "보통" = 정산 오류·납기 조정·단가 협의처럼 정해진 기한 안에 답해야 하는 건, "낮음" = 자료 요청·연락처 변경·감사 인사처럼 일정을 조율할 수 있는 건. category 는 "품질", "납기", "가격·계약", "정산", "기술 지원", "기타" 여섯 개 중 정확히 하나만 쓴다. reason 은 판단 근거를 20자 이내로.',
          outputColumns: [
            { name: 'urgency', type: 'TEXT' },
            { name: 'category', type: 'TEXT' },
            { name: 'reason', type: 'TEXT' },
          ],
          inputColumns: ['inquiry_text'],
          batchSize: 12,
          onError: 'CONTINUE',
        },
      },
    ],
  });
  await call(token, 'POST', `/pipelines/${pipeline.id}/triggers`, {
    name: '매일 새벽 3시',
    triggerType: 'SCHEDULE',
    description: '전날 마감 전표까지 반영',
    config: { cron: '0 0 3 * * *' },
  });
  await call(token, 'POST', `/pipelines/${pipeline.id}/triggers`, {
    name: '문의 접수 시 즉시',
    triggerType: 'DATASET_CHANGE',
    config: { datasetIds: [ids.inquiry] },
  }).catch((e) => console.warn(`  데이터 변경 트리거 생략: ${e.message.split('\n')[0]}`));

  // 스마트 작업 — 리포트 양식 + 주간 브리핑. 실행(AI 가 리포트 작성)은 seed-ai.mjs.
  const template = await call(token, 'POST', '/proactive/templates', {
    name: '주간 매출·수금 브리핑',
    description: '경영 회의용 주간 매출·수금 보고서',
    style: '보고서체(~함, ~임). 숫자는 근거 데이터의 값을 그대로 쓰고 금액은 백만 원 단위로, 표와 짧은 문장 위주로 쓴다.',
    sections: [
      { key: 'summary', label: '핵심 요약', required: true, type: 'text', instruction: '이번 주 가장 중요한 변화 3가지를 한 줄씩' },
      { key: 'sales', label: '매출·이익 추이', required: true, type: 'text', instruction: '제품군별 매출·매출총이익과 지난주 대비 증감을 표로' },
      { key: 'collection', label: '수금·연체 현황', required: true, type: 'text', instruction: '만기가 지났는데 입금되지 않은 거래처 상위 5곳과 금액' },
      { key: 'action', label: '권고 사항', required: false, type: 'text', instruction: '데이터로 뒷받침되는 영업·채권 관리 권고 2~3개' },
    ],
  });
  await call(token, 'POST', '/proactive/jobs', {
    name: '주간 매출·수금 브리핑',
    prompt:
      '매출 전표(sales_ledger)에서 최근 7일과 그 전 7일을 비교해 제품군별 매출·매출총이익 증감과 권역별 매출을 분석하고, 만기(due_date)가 지났는데 입금일(paid_date)이 비어 있는 연체 거래처 상위 5곳을 찾아 양식에 맞춰 보고서를 작성한다. 리포트 파일(report.html·report.md·summary.md)은 하위 에이전트에 맡기지 말고 직접 Bash 로 저장 경로를 만든(mkdir -p) 뒤 세 파일을 모두 저장하고 마친다.',
    cronExpression: '0 0 8 * * MON',
    timezone: 'Asia/Seoul',
    templateId: template.id,
    channels: [],
    config: {},
  });
  console.log('  분석 자산·파이프라인·스마트 작업 생성');
}

/** 사내 문서(가상) 원본 위치 — 계약서·품질 보고서·회의록 등. 문서 검색과 지식그래프 적재의 입력이다. */
const SEED_DOCS = path.join(path.dirname(fileURLToPath(import.meta.url)), '../seed-docs');
const OLLAMA_URL = process.env.INTRO_OLLAMA_URL ?? 'http://bluelion.iptime.org:11434';

/** 조건이 참이 될 때까지 폴링한다(문서 파싱·임베딩은 비동기 잡). */
async function poll(label, check, { intervalMs = 3000, timeoutMs = 10 * 60 * 1000 } = {}) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const v = await check();
    if (v) return v;
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(`시간 초과: ${label}`);
}

/**
 * 문서 검색·행 검색·지식 모델을 준비한다.
 * 그래프 적재(표 투영·문서 추출)는 API 엔드포인트가 없고 AI 에이전트 도구로만 돌기 때문에 seed-ai.mjs 가 채팅으로 시킨다.
 */
async function seedKnowledge(token, ids) {
  // 1) 임베딩 — 운영과 같은 Ollama(bge-m3, 1024차원). 저장할 때 서버가 실제로 한 번 임베딩해 차원을 잰다.
  await call(token, 'PUT', '/settings/embedding', { provider: 'OLLAMA', model: 'bge-m3', baseUrl: OLLAMA_URL });

  // 2) 행 검색(검색 탭) — 거래처 정보의 글자 컬럼을 의미 검색 대상으로 켠다. 색인은 1분 주기 스윕이 만든다.
  await call(token, 'PUT', `/datasets/${ids.customer}/search-index`, { fields: ['customer_name', 'industry', 'region', 'contact_name'] });

  // 3) 문서 데이터셋 — 파일은 한 건씩 올리고, 형식은 확장자가 아니라 Content-Type 으로 판정되므로 명시한다.
  const dsDocs = await call(token, 'POST', '/datasets', {
    name: '사내 문서',
    tableName: 'company_docs',
    storageType: 'DOCUMENT',
    description: '거래 계약서·품질 보고서·회의록·신용 평가·단가 협상 메모·제품 사양서',
    categoryId: ids.catCust,
  });
  ids.docs = dsDocs.id;
  const files = (await readdir(SEED_DOCS)).filter((f) => f.endsWith('.md')).sort();
  const uploaded = [];
  for (const f of files) {
    const form = new FormData();
    form.append('file', new Blob([await readFile(path.join(SEED_DOCS, f))], { type: 'text/markdown' }), f.replace(/^\d+-/, ''));
    const res = await fetch(`${API}/datasets/${dsDocs.id}/documents`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form });
    if (!res.ok) throw new Error(`문서 업로드 실패(${f}) → ${res.status} ${await res.text()}`);
    uploaded.push((await res.json()).id);
  }
  for (const docId of uploaded) {
    const doc = await poll(`문서 ${docId} 처리`, async () => {
      const d = await call(token, 'GET', `/datasets/${dsDocs.id}/documents/${docId}`);
      return ['COMPLETED', 'FAILED'].includes(d.status) ? d : null;
    });
    if (doc.status === 'FAILED') throw new Error(`문서 처리 실패: ${doc.fileName ?? docId} — ${doc.errorDetail}`);
  }
  console.log(`  사내 문서 ${uploaded.length}건 색인 완료`);

  // 4) 지식 모델 — 거래처·제품군·권역·담당자·이슈와 그 관계. 이름은 화면에 그대로 보이므로 한글로 짓는다.
  // 마이그레이션이 넣는 예시 지식 모델(화재조사 보고서)은 시연 주제와 무관해 목록에서 지운다.
  for (const o of await call(token, 'GET', '/ontologies')) await call(token, 'DELETE', `/ontology/${o.id}`);
  const prop = (name, description, dataType = 'text') => ({ name, description, dataType, unit: null });
  const ontology = await call(token, 'POST', '/ontologies', {
    domain: '영업·고객',
    status: 'active',
    entities: [
      { type: '거래처', description: '제품을 구매하는 고객사', naming: '법인 표기(㈜, 주식회사)와 띄어쓰기를 뺀 회사명', resolution: 'embedding', properties: [prop('등급', '거래 규모 등급(VIP·A·B·C)'), prop('업종', '거래처의 주요 업종')] },
      { type: '제품군', description: '판매 제품의 묶음', naming: '제품군 이름', resolution: 'exact', properties: [] },
      { type: '영업권역', description: '거래처를 담당하는 지역 단위', naming: '권역 이름', resolution: 'exact', properties: [] },
      { type: '영업담당', description: '거래처를 맡은 영업 사원', naming: '사람 이름', resolution: 'exact', properties: [] },
      { type: '이슈', description: '품질 불량·결제 지연·단가 협상처럼 거래에 영향을 준 사건', naming: '이슈를 한 구절로 요약', resolution: 'embedding', properties: [prop('심각도', '높음·보통·낮음')] },
    ],
    relations: [
      { subject: '거래처', relation: '구매함', object: '제품군', description: '거래처가 해당 제품군을 구매한 적이 있다' },
      { subject: '영업담당', relation: '담당함', object: '거래처', description: '영업 사원이 거래처를 맡고 있다' },
      { subject: '거래처', relation: '소속', object: '영업권역', description: '거래처가 속한 영업권역' },
      { subject: '거래처', relation: '관련 이슈', object: '이슈', description: '거래처와 관련된 품질·결제·계약 이슈' },
      { subject: '이슈', relation: '대상 제품', object: '제품군', description: '이슈가 발생한 제품군' },
    ],
  });
  const ontologyId = ontology?.id ?? ontology;
  ids.ontology = ontologyId;

  // 5) 매핑 — 거래처 정보(거래처·권역)와 매출 전표(거래처·제품군·담당자). 같은 이름의 거래처 노드는 그래프에서 하나로 합쳐진다.
  const bindAndMap = async (datasetId, spec) => {
    await call(token, 'PUT', `/datasets/${datasetId}/ontology`, { ontologyId });
    await call(token, 'PUT', `/datasets/${datasetId}/mapping`, spec);
    await call(token, 'POST', `/datasets/${datasetId}/mapping/activate`);
  };
  await bindAndMap(ids.customer, {
    entities: [
      { entityType: '거래처', nameColumn: 'customer_name', properties: [{ column: 'grade', propertyName: '등급' }, { column: 'industry', propertyName: '업종' }] },
      { entityType: '영업권역', nameColumn: 'region', properties: [] },
    ],
    relations: [{ subjectRef: 0, relation: '소속', objectRef: 1 }],
  });
  await bindAndMap(ids.sales, {
    entities: [
      { entityType: '거래처', nameColumn: 'customer_name', properties: [] },
      { entityType: '제품군', nameColumn: 'product_line', properties: [] },
      { entityType: '영업담당', nameColumn: 'sales_rep', properties: [] },
    ],
    relations: [
      { subjectRef: 0, relation: '구매함', objectRef: 1 },
      { subjectRef: 2, relation: '담당함', objectRef: 0 },
    ],
  });
  // 문서 데이터셋도 같은 지식 모델에 묶는다 — AI 가 문서에서 거래처·이슈를 뽑아 같은 그래프에 잇는다(seed-ai.mjs).
  await call(token, 'PUT', `/datasets/${dsDocs.id}/ontology`, { ontologyId });
  console.log('  임베딩·행 검색·지식 모델·매핑 준비');
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { ids } = await seed();
  console.log('seed-demo 완료', ids);
}

export { seed, seedKnowledge };
