// RuleUp 부하테스트 공통 — 계정·요청 헬퍼. 비밀값은 환경변수로만 받는다(저장소에 두지 않는다).
//   BASE_URL     기본 https://staging-api.ruleup.co.kr
//   ALB_IP       있으면 BASE_URL 호스트를 이 IP 로 직접 보낸다(Cloudflare 를 건너뛰고 서버만 잰다. TLS SNI 는 호스트 그대로)
//   LT_SECRET    stg DEV_TOKEN_SECRET — 로그인 우회(OAUTH_LOAD_TEST_ENABLED)·dev 토큰에 쓴다
//   RUN_ID       계정 식별자 접두어(같은 RUN_ID 면 같은 가짜 사용자를 다시 쓴다)
import http from 'k6/http';
import { check, fail } from 'k6';
import { Trend, Counter } from 'k6/metrics';

export const BASE = __ENV.BASE_URL || 'https://staging-api.ruleup.co.kr';
export const SECRET = __ENV.LT_SECRET || '';
export const RUN_ID = __ENV.RUN_ID || 'lt1';
const HOST = BASE.replace(/^https?:\/\//, '').replace(/\/.*$/, '');

export function hosts() {
  return __ENV.ALB_IP ? { [HOST]: __ENV.ALB_IP } : {};
}

export const apiErrors = new Counter('ruleup_api_errors');
export const businessRejects = new Counter('ruleup_business_rejects');

const JSON_HEADERS = { 'Content-Type': 'application/json', 'User-Agent': 'ruleup-loadtest/1.0 (k6)' };

export function auth(token, extra) {
  return { headers: Object.assign({}, JSON_HEADERS, { Authorization: `Bearer ${token}` }, extra || {}) };
}

/** 응답 확인 — 2xx 가 아니면 오류로 세고, 코드·상태를 태그로 남긴다(로그 대조용). */
export function ok(res, name, allowed) {
  const okStatus = (allowed || [200, 201]).includes(res.status);
  let code = '';
  if (!okStatus) {
    try { code = (res.json('error.code') || res.json('code') || '') + ''; } catch (e) { code = 'non_json'; }
    if (res.status >= 500 || res.status === 0) apiErrors.add(1, { api: name, status: `${res.status}`, code });
    else businessRejects.add(1, { api: name, status: `${res.status}`, code });
  }
  check(res, { [`${name} ${(allowed || [200, 201]).join('/')}`]: () => okStatus });
  return okStatus;
}

export function deviceInfo() {
  return { platform: 'ANDROID', osVersion: '14', sdkInt: 34, deviceModel: 'SM-S921N', manufacturer: 'samsung',
           lowRam: false, versionName: '1.0.0', versionCode: 100, country: 'KR', timeZone: 'Asia/Seoul', ramMb: 8192 };
}

function ids(n) {
  return { code: `loadtest-${RUN_ID}-${n}`, installationId: `lt-inst-${RUN_ID}-${n}`, deviceId: `lt-dev-${RUN_ID}-${n}` };
}

/** 실제 로그인 경로(카카오) — 우회 스위치가 IdP 호출만 뺀다. */
export function oauthLogin(n) {
  const i = ids(n);
  const res = http.post(`${BASE}/api/v1/auth/oauth/kakao`, JSON.stringify({
    code: i.code, codeVerifier: SECRET, redirectUri: null, installationId: i.installationId, deviceId: i.deviceId,
    deviceInfo: deviceInfo(), permissions: { postNotifications: 'GRANTED', location: 'GRANTED', camera: 'GRANTED', screenTime: 'GRANTED' },
  }), { headers: JSON_HEADERS, tags: { api: 'auth.oauth_kakao' } });
  ok(res, 'auth.oauth_kakao');
  return res;
}

export function signup(n, signupToken) {
  const i = ids(n);
  const agreed = { agreed: true };
  const res = http.post(`${BASE}/api/v1/auth/signup`, JSON.stringify({
    signupToken, installationId: i.installationId, deviceId: i.deviceId, deviceInfo: deviceInfo(),
    nickname: `lt${RUN_ID.slice(-3)}${n}`.slice(0, 12), interestCategories: ['EXERCISE', 'STUDY'], birthDate: '1998-03-21', gender: 'MALE',
    agreements: { termsOfService: agreed, privacyPolicy: agreed, locationService: agreed, marketing: agreed, event: agreed },
  }), { headers: JSON_HEADERS, tags: { api: 'auth.signup' } });
  ok(res, 'auth.signup');
  return res;
}

/** 로그인(없으면 가입 후 다시 로그인)해서 {access, refresh} 를 돌려준다. */
export function session(n) {
  let res = oauthLogin(n);
  if (res.status !== 200) fail(`login ${n} ${res.status} ${res.body && res.body.slice(0, 200)}`);
  if (res.json('data.isNewUser')) {
    const s = signup(n, res.json('data.signupToken'));
    if (s.status !== 200) fail(`signup ${n} ${s.status} ${s.body && s.body.slice(0, 200)}`);
    res = oauthLogin(n);
  }
  return { access: res.json('data.accessToken'), refresh: res.json('data.refreshToken'), userId: res.json('data.user.id'), at: Date.now() };
}

export function refresh(s) {
  const res = http.post(`${BASE}/api/v1/auth/refresh`, JSON.stringify({ refreshToken: s.refresh }),
    { headers: JSON_HEADERS, tags: { api: 'auth.refresh' } });
  if (ok(res, 'auth.refresh')) {
    s.access = res.json('data.accessToken');
    s.refresh = res.json('data.refreshToken');
    s.at = Date.now();
  }
  return res;
}

/** 앱 첫 화면 — 플랫폼·버전은 헤더로 보낸다(쿼리로 보내면 400). 로그인 전에도 부른다. */
export function intro() {
  const res = http.get(`${BASE}/api/v1/intro`, { headers: Object.assign({}, JSON_HEADERS, { platform: 'ANDROID', appVersionCode: '100' }),
    tags: { api: 'intro' } });
  ok(res, 'intro');
  return res;
}

// 허용한 4xx(예: 409 이미 가입)는 k6 실패율에 넣지 않는다 — 진짜 실패는 ruleup_api_errors(5xx·연결 실패)로 본다.
function expect(allowed) {
  return { responseCallback: http.expectedStatuses(...(allowed || [200, 201])) };
}

export function get(s, path, name, allowed) {
  const res = http.get(`${BASE}${path}`, Object.assign(auth(s.access), { tags: { api: name } }, expect(allowed)));
  ok(res, name, allowed);
  return res;
}

export function post(s, path, body, name, extraHeaders, allowed) {
  const res = http.post(`${BASE}${path}`, body == null ? null : JSON.stringify(body),
    Object.assign(auth(s.access, extraHeaders), { tags: { api: name } }, expect(allowed)));
  ok(res, name, allowed);
  return res;
}

export function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

/** 템플릿 초안 → 생성. 초안을 고치지 않고 그대로 보내면 모더레이션 대상이 아니다(실사용의 대부분). */
export function createFromTemplate(s, templateId, overrides) {
  const d = post(s, '/api/v1/challenges/recommendation/by-template', { templateId }, 'challenge.draft_template');
  if (d.status !== 200) return null;
  const draft = d.json('data.draft');
  const body = Object.assign({
    draftId: d.json('data.draftId'), title: draft.title, description: draft.description, category: draft.category,
    mode: draft.mode, visibility: draft.visibility, rankingVisible: draft.rankingVisible, capacity: draft.capacity,
    minTier: draft.minTier, period: { start: draft.period.start, end: draft.period.end }, weeklyCount: draft.weeklyCount,
    params: (draft.params || []).map((p) => ({ key: p.key, value: p.value != null ? `${p.value}` : p.defaultValue != null ? `${p.defaultValue}` : null })),
    verification: { type: draft.verification.type, method: draft.verification.method },
    penalties: { watcher: false }, imageUrl: null,
  }, overrides || {});
  const c = post(s, '/api/v1/challenges', body, 'challenge.create', { 'Idempotency-Key': uuid() });
  return c.status === 200 || c.status === 201 ? c.json('data.challengeId') || c.json('data.id') : null;
}

/** 탐색·가입 부하에 쓰는 공개 그룹 방 — 제목·설명은 초안 그대로라 모더레이션 대상이 아니다. */
export const GROUP_PUBLIC = { mode: 'GROUP', visibility: 'PUBLIC', capacity: 100 };   // 정원은 5·30·100·300·무제한만

export const SLO_MS = {   // 노션 SLO 지연 목표(p95) — alarm-catalog.yaml operations 와 같다
  'auth.oauth_kakao': 3000, 'auth.refresh': 1000, 'challenge.explore': 1000, 'challenge.detail': 1000,
  'challenge.join': 1500, 'verification.sync': 3000, 'verification.today': 1000, 'verification.progress': 1000,
};

// 요약에 API 별 지연이 나오려면 태그별 임계값이 있어야 한다(k6 는 임계값이 걸린 태그만 따로 집계한다).
const ALL_APIS = ['intro', 'me.home', 'users.me', 'challenge.list', 'notification.list', 'notification.settings', 'challenge.categories',
  'challenge.trending', 'challenge.recommendations', 'challenge.draft_template', 'challenge.create', 'challenge.draft_ai', 'room.home',
  'room.ranking', 'challenge.members', 'verification.setup', 'verification.intro', 'profile', 'me.stats', 'me.tier', 'me.calendar', 'auth.signup'];

export function thresholds(extra) {
  const t = { http_req_failed: ['rate<0.005'], ruleup_api_errors: ['count<1'] };
  for (const [api, ms] of Object.entries(SLO_MS)) t[`http_req_duration{api:${api}}`] = [`p(95)<${ms}`];
  for (const api of ALL_APIS) if (!t[`http_req_duration{api:${api}}`]) t[`http_req_duration{api:${api}}`] = ['p(95)<60000'];
  return Object.assign(t, extra || {});
}
