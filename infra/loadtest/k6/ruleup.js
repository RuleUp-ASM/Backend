// RuleUp 부하·성능 시나리오. SCENARIO 로 고른다.
//   seed      가짜 사용자 USERS 명 가입 + 각자 공개 그룹 방 1개 생성 + 남의 방 2곳 가입(이후 시나리오의 데이터)
//   login     아침 몰림 — 기존 사용자 로그인 + 리프레시를 초당 RATE 건
//   browse    앱 열기·탐색·상세·마이페이지 읽기 묶음, 동시 사용자 VUS 까지 램프
//   create    템플릿 초안 → 생성을 초당 RATE 건(실사용의 대부분)
//   aidraft   AI 초안(실제 LLM) → 생성, 동시 VUS 명
//   joinhot   인기 방 하나에 동시 가입(참여자 수·정원 경합)
//   sync      자동 인증 신호 업로드(아침 8시 몰림)
//   peak      실사용 비율로 섞은 피크(평상시 → 피크 → 2배 스트레스)
//   breakpoint 오류·지연이 무너질 때까지 단계적으로 올린다
// 공통: k6 run -e LT_SECRET=... -e RUN_ID=lt1 -e ALB_IP=... -e SCENARIO=browse infra/loadtest/k6/ruleup.js
import { sleep, check } from 'k6';
import http from 'k6/http';
import exec from 'k6/execution';
import * as L from './lib.js';

const SC = __ENV.SCENARIO || 'browse';
const USERS = Number(__ENV.USERS || 3000);
const RATE = Number(__ENV.RATE || 10);
const VUS = Number(__ENV.VUS || 100);
const DUR = __ENV.DURATION || '5m';
const ROOM_EVERY = Number(__ENV.ROOM_EVERY || 1);
const TEMPLATES = (__ENV.TEMPLATES || '1001,1002,1209,1501').split(',').map(Number);

function think(min, max) { sleep(min + Math.random() * (max - min)); }
function pick(a) { return a[Math.floor(Math.random() * a.length)]; }
function userNo() { return Math.floor(Math.random() * USERS); }

// VU 마다 로그인 상태를 들고 간다(실제 앱처럼 매 요청마다 로그인하지 않는다).
let me = null;
function mine() {
  if (!me) me = L.session(userNo());
  // 액세스 토큰은 30분짜리다 — 앱처럼 만료 전에 갱신한다(안 하면 30분 넘는 시험 끝물이 전부 401).
  else if (Date.now() - me.at > 25 * 60 * 1000) L.refresh(me);
  return me;
}

const scenarios = {
  seed: { executor: 'shared-iterations', vus: VUS, iterations: USERS, maxDuration: '60m', exec: 'seed' },
  seedjoin: { executor: 'shared-iterations', vus: VUS, iterations: USERS, maxDuration: '60m', exec: 'seedjoin' },
  login: { executor: 'ramping-arrival-rate', startRate: 1, timeUnit: '1s', preAllocatedVUs: 50, maxVUs: 400, exec: 'login',
    stages: [{ target: RATE, duration: '1m' }, { target: RATE, duration: DUR }, { target: 0, duration: '30s' }] },
  browse: { executor: 'ramping-vus', exec: 'browse', startVUs: 0,
    stages: [{ target: VUS, duration: '2m' }, { target: VUS, duration: DUR }, { target: 0, duration: '30s' }] },
  create: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: DUR, preAllocatedVUs: 30, maxVUs: 300, exec: 'create' },
  aidraft: { executor: 'constant-vus', vus: VUS, duration: DUR, exec: 'aidraft' },
  joinhot: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, exec: 'joinhot', maxDuration: '5m' },
  sync: { executor: 'ramping-arrival-rate', startRate: 1, timeUnit: '1s', preAllocatedVUs: 50, maxVUs: 400, exec: 'sync',
    stages: [{ target: RATE, duration: '1m' }, { target: RATE, duration: DUR }] },
};

function peakMix(scale) {
  // DAU 1만, 피크 동시 500 기준. scale 로 배수(2 = 스트레스).
  const s = (n) => Math.max(1, Math.round(n * scale));
  return {
    browse: { executor: 'ramping-vus', exec: 'browse', startVUs: 0,
      stages: [{ target: s(150), duration: '2m' }, { target: s(150), duration: '3m' },     // 평상시
               { target: s(400), duration: '2m' }, { target: s(400), duration: DUR },     // 피크
               { target: 0, duration: '1m' }] },
    login: { executor: 'ramping-arrival-rate', exec: 'login', timeUnit: '1s', preAllocatedVUs: 50, maxVUs: 500, startRate: 1,
      stages: [{ target: s(3), duration: '5m' }, { target: s(15), duration: '2m' }, { target: s(15), duration: DUR }, { target: 0, duration: '1m' }] },
    sync: { executor: 'ramping-arrival-rate', exec: 'sync', timeUnit: '1s', preAllocatedVUs: 50, maxVUs: 500, startRate: 1,
      stages: [{ target: s(4), duration: '5m' }, { target: s(20), duration: '2m' }, { target: s(20), duration: DUR }, { target: 0, duration: '1m' }] },
    create: { executor: 'ramping-arrival-rate', exec: 'create', timeUnit: '1s', preAllocatedVUs: 20, maxVUs: 200, startRate: 0,
      stages: [{ target: s(1), duration: '5m' }, { target: s(3), duration: '2m' }, { target: s(3), duration: DUR }, { target: 0, duration: '1m' }] },
    join: { executor: 'ramping-arrival-rate', exec: 'join', timeUnit: '1s', preAllocatedVUs: 20, maxVUs: 200, startRate: 0,
      stages: [{ target: s(1), duration: '5m' }, { target: s(4), duration: '2m' }, { target: s(4), duration: DUR }, { target: 0, duration: '1m' }] },
  };
}

// 배수(scale)를 시간에 따라 바꾸는 혼합 — [배수, 걸리는 시간] 목록. 1 = DAU 1만 피크(동시 ~500), 2 = DAU 2만 피크.
//   steps  500→1000→1500→500(회복): 오토스케일·RDS 가 단계마다 어떻게 버티는지
//   spike  500→1500 을 30초 안에, 5분 유지 뒤 500 으로: 급증과 복구 시간
function mixed(plan) {
  const st = (base) => plan.map(([scale, d]) => ({ target: Math.max(base === 400 ? 1 : 0, Math.round(base * scale)), duration: d }));
  const rate = (base, extra) => Object.assign({ executor: 'ramping-arrival-rate', timeUnit: '1s', startRate: 1, stages: st(base) }, extra);
  return {
    browse: { executor: 'ramping-vus', exec: 'browse', startVUs: 0, stages: st(400) },
    login: rate(15, { exec: 'login', preAllocatedVUs: 100, maxVUs: 1500 }),
    sync: rate(20, { exec: 'sync', preAllocatedVUs: 100, maxVUs: 1500 }),
    create: rate(3, { exec: 'create', preAllocatedVUs: 30, maxVUs: 600 }),
    join: rate(4, { exec: 'join', preAllocatedVUs: 30, maxVUs: 600 }),
  };
}
const PLANS = {
  steps: [[1, '2m'], [1, '6m'], [2, '2m'], [2, '8m'], [3, '2m'], [3, '8m'], [1, '1m'], [1, '8m']],
  spike: [[1, '2m'], [1, '5m'], [3, '30s'], [3, '5m'], [1, '30s'], [1, '8m']],
};

function breakpoint() {
  const stages = [];
  for (const v of [200, 400, 600, 800, 1000, 1300, 1600, 2000]) stages.push({ target: v, duration: '2m' }, { target: v, duration: '2m' });
  return { browse: { executor: 'ramping-vus', exec: 'browse', startVUs: 0, stages },
           login: { executor: 'constant-arrival-rate', exec: 'login', rate: 5, timeUnit: '1s', duration: '32m', preAllocatedVUs: 50, maxVUs: 300 } };
}

const chosen = SC === 'peak' ? peakMix(Number(__ENV.SCALE || 1)) : SC === 'breakpoint' ? breakpoint()
  : PLANS[SC] ? mixed(PLANS[SC]) : { [SC]: scenarios[SC] };

export const options = {
  hosts: L.hosts(), scenarios: chosen, discardResponseBodies: false,
  // 한계점은 오류 5% 뿐 아니라 핵심 화면이 SLO 의 3배(3초)를 넘으면 멈춘다 — 5xx 없이 느려지기만 하는 붕괴도 한계다.
  thresholds: SC === 'breakpoint' ? { http_req_failed: [{ threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '1m' }],
    'http_req_duration{api:me.home}': [{ threshold: 'p(95)<3000', abortOnFail: true, delayAbortEval: '2m' }],
    'http_req_duration{api:challenge.explore}': [{ threshold: 'p(95)<3000', abortOnFail: true, delayAbortEval: '2m' }] } : L.thresholds(),
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// ---------------- 시나리오 본문 ----------------

export function seed() {
  const n = exec.scenario.iterationInTest;
  const s = L.session(n);
  const tpl = TEMPLATES[n % TEMPLATES.length];
  // 방은 ROOM_EVERY 명 중 1명만 만든다(실사용은 참여자가 방장보다 훨씬 많다). 기본 1 = 모두 만든다.
  if (n % ROOM_EVERY === 0) L.createFromTemplate(s, tpl, L.GROUP_PUBLIC);
  const ex = L.get(s, `/api/v1/challenges/explore?sort=${pick(['RECENT', 'POPULAR', 'PARTICIPANTS'])}&size=20`, 'challenge.explore');
  const items = (ex.status === 200 && ex.json('data.items')) || [];
  for (const it of items.filter((x) => !x.joined && !x.isFull && x.eligible).slice(0, 2))
    L.post(s, `/api/v1/challenges/${it.challengeId}/members`, null, 'challenge.join', null, [200, 201, 409]);
}

/** 시드 2단계 — 각자 남의 방 2곳에 가입(시드 1단계가 방을 모두 만든 뒤라야 고르게 퍼진다). */
export function seedjoin() {
  const s = L.session(exec.scenario.iterationInTest);
  const ex = L.get(s, `/api/v1/challenges/explore?sort=${pick(['RECENT', 'POPULAR', 'PARTICIPANTS', 'DEADLINE'])}&size=20&eligibleOnly=true`, 'challenge.explore');
  const items = ((ex.status === 200 && ex.json('data.items')) || []).filter((x) => !x.joined && !x.isFull);
  for (let k = 0; k < 2 && items.length; k++) {
    const c = items.splice(Math.floor(Math.random() * items.length), 1)[0];
    L.post(s, `/api/v1/challenges/${c.challengeId}/members`, null, 'challenge.join', null, [200, 201, 409]);
  }
}

export function login() {
  const n = userNo();
  const r = L.oauthLogin(n);
  if (r.status === 200 && !r.json('data.isNewUser')) {
    const s = { access: r.json('data.accessToken'), refresh: r.json('data.refreshToken') };
    L.get(s, '/api/v1/me/home', 'me.home');
    L.refresh(s);
  }
}

export function browse() {
  const s = mine();
  // 앱 열기
  L.intro();
  L.get(s, '/api/v1/me/home', 'me.home');
  L.get(s, '/api/v1/challenges?filter=IN_PROGRESS', 'challenge.list');
  think(1, 3);
  // 탐색 — 정렬·카테고리를 바꿔 가며 2~3 페이지
  L.get(s, '/api/v1/challenge-categories', 'challenge.categories');
  L.get(s, '/api/v1/challenges/trending', 'challenge.trending');
  const sort = pick(['POPULAR', 'RECENT', 'DEADLINE', 'PARTICIPANTS']);
  let ex = L.get(s, `/api/v1/challenges/explore?sort=${sort}&size=20`, 'challenge.explore');
  think(1, 3);
  let items = (ex.status === 200 && ex.json('data.items')) || [];
  const cursor = ex.status === 200 ? ex.json('data.nextCursor') : null;
  // 다음 페이지는 같은 정렬로 — 정렬이 다르면 커서가 무효(400 CURSOR_INVALID)다.
  if (cursor) L.get(s, `/api/v1/challenges/explore?sort=${sort}&size=20&cursor=${encodeURIComponent(cursor)}`, 'challenge.explore');
  if (items.length) {
    const c = pick(items);
    L.get(s, `/api/v1/challenges/${c.challengeId}`, 'challenge.detail');
    think(2, 5);
  }
  // 내 방
  const mineList = L.get(s, '/api/v1/challenges?filter=IN_PROGRESS', 'challenge.list');
  const rooms = (mineList.status === 200 && (mineList.json('data.items') || mineList.json('data'))) || [];
  if (Array.isArray(rooms) && rooms.length) {
    const r = pick(rooms);
    const id = r.challengeId || r.id;
    if (id) {
      L.get(s, `/api/v1/challenges/${id}/room`, 'room.home');
      L.get(s, `/api/v1/challenges/${id}/ranking`, 'room.ranking', [200, 403, 404]);
      L.get(s, `/api/v1/challenges/${id}/verifications/today`, 'verification.today', [200, 404, 409]);
    }
  }
  L.get(s, '/api/v1/verifications/progress', 'verification.progress');
  L.get(s, '/api/v1/notifications', 'notification.list');
  think(2, 6);
  if (Math.random() < 0.3) {   // 마이페이지는 열 명 중 세 명
    L.get(s, '/api/v1/profile', 'profile');
    L.get(s, '/api/v1/me/stats', 'me.stats');
    L.get(s, `/api/v1/me/calendar?month=${new Date().toISOString().slice(0, 7)}`, 'me.calendar');
  }
  think(3, 8);
}

export function create() {
  const s = mine();
  L.createFromTemplate(s, pick(TEMPLATES), Math.random() < 0.6 ? L.GROUP_PUBLIC : undefined);
}

const AI_PROMPTS = ['매일 아침 30분 달리기', '하루 물 2리터 마시기', '주 3회 헬스장 가기', '매일 영어 단어 30개 외우기',
  '밤 12시 전에 자기', '하루 만보 걷기', '매일 독서 20쪽', '주 5회 스트레칭 10분'];

export function aidraft() {
  const s = mine();
  const d = L.post(s, '/api/v1/challenges/draft', { description: pick(AI_PROMPTS) }, 'challenge.draft_ai', null, [200, 429]);
  check(d, { 'ai draft not fallback': (r) => r.status === 200 && r.json('data.result') !== 'FALLBACK' });
  think(1, 3);
}

let hotId = null;
/** 모든 VU 가 같은 출발 시각을 보도록 한 번만 정한다(joinhot 전용, 다른 시나리오는 쓰지 않는다). */
export function setup() {
  return { startAt: __ENV.START_DELAY_MS ? Date.now() + Number(__ENV.START_DELAY_MS) : 0 };
}

export function joinhot(data) {
  // 모두 같은 방 하나에 동시에 들어간다 — 첫 VU 가 방을 만들고 나머지는 explore 최신에서 같은 방을 찾는다.
  // 이미 가입한 사용자로 로그인해 두고, 시작 뒤 START_DELAY_MS 시점에 <b>일제히</b> 들어간다 — 가입부터 하면 가입이 몰려
  // 정원 경합 전에 풀 대기로 503 이 난다(2차 J1: 300명 중 46명만 방까지 감). 가입 없는 사용자 번호는 HOT_USER_BASE 부터.
  const s = L.session(Number(__ENV.HOT_USER_BASE || USERS) + __VU);
  if (!hotId) hotId = __ENV.HOT_ID;
  if (data && data.startAt) sleep(Math.max(0, (data.startAt - Date.now()) / 1000));
  const r = L.post(s, `/api/v1/challenges/${hotId}/members`, null, 'challenge.join', null, [200, 201, 409]);
  check(r, { 'join ok or full': (x) => [200, 201, 409].includes(x.status) });
}

export function join() {
  const s = mine();
  const ex = L.get(s, `/api/v1/challenges/explore?sort=${pick(['POPULAR', 'RECENT'])}&size=20&eligibleOnly=true`, 'challenge.explore');
  const items = ((ex.status === 200 && ex.json('data.items')) || []).filter((x) => !x.joined && !x.isFull);
  if (!items.length) return;
  const c = pick(items);
  L.get(s, `/api/v1/challenges/${c.challengeId}`, 'challenge.detail');
  L.post(s, `/api/v1/challenges/${c.challengeId}/members`, null, 'challenge.join', null, [200, 201, 409]);
}

let healthVersion = null;
export function sync() {
  const s = mine();
  if (!s.consented) {   // 실사용자는 자동 인증 전에 건강정보 동의를 한다 — 없으면 신호가 저장·판정되지 않는다
    if (!healthVersion) healthVersion = L.intro().json('data.termsVersions.healthInfo');
    L.post(s, '/api/v1/users/me/agreements', { agreements: [{ type: 'HEALTH_INFO', agreed: true, version: healthVersion }] },
      'agreement.submit', null, [200, 201, 409]);
    s.consented = true;
  }
  if (!s.syncSession) {
    const i = L.post(s, '/api/v1/verifications/intro', { deviceId: `lt-dev-${L.RUN_ID}`, appVersion: '1.0.0',
      deviceProfile: { sdkInt: 34, model: 'SM-S921N', lowRam: false } }, 'verification.intro');
    s.syncSession = i.status === 200 ? (i.json('data.sessionId') || 'x') : 'x';
  }
  const now = Date.now();
  const KST = 9 * 3600 * 1000;   // k6 에는 Intl 시간대가 없다 — KST 자정을 직접 계산한다
  const startOfDay = Math.floor((now + KST) / 86400000) * 86400000 - KST;
  const from = new Date(now - 10 * 60 * 1000).toISOString();
  const rid = `lt-${L.RUN_ID}-${exec.vu.idInTest}-${now}`;
  const body = {
    deviceTimeMillis: now, elapsedRealtimeMillis: 1000000 + exec.vu.iterationInScenario, bootSessionId: 'boot-lt', timeZone: 'Asia/Seoul',
    coveredFrom: startOfDay, coveredUntil: now, backlog: true, sessionId: s.syncSession,
    signals: [{ type: 'HEALTH', recordId: rid, observedAt: new Date(now).toISOString(), readings: [{ recordId: rid, metric: 'STEPS', value: 120,
      unit: 'count', startTime: from, endTime: new Date(now).toISOString(),
      origin: { dataOrigin: 'com.sec.android.app.shealth', recordingMethod: 'AUTO', device: 'WATCH' } }] }],
  };
  // backlog=true 라도 사용자당 10초 간격 — 같은 VU 가 연달아 보내면 429 는 정상 거절이다.
  L.post(s, '/api/v1/verifications/sync', body, 'verification.sync', null, [200, 429]);
}
