// 스모크 — 사용자 몇 명이 주요 API 를 한 번씩 부른다. 부하 전에 계약 오류(4xx/5xx)를 먼저 걸러 낸다.
//   k6 run -e LT_SECRET=... -e RUN_ID=smoke1 infra/loadtest/k6/smoke.js
import { sleep } from 'k6';
import * as L from './lib.js';

export const options = { hosts: L.hosts(), vus: Number(__ENV.VUS || 3), iterations: Number(__ENV.ITER || 3),
  thresholds: { ruleup_api_errors: ['count<1'] } };

const TEMPLATE = Number(__ENV.TEMPLATE_ID || 1001);

export default function () {
  const s = L.session(__VU * 1000 + __ITER);
  // 앱 첫 화면
  L.intro();
  L.get(s, '/api/v1/me/home', 'me.home');
  L.get(s, '/api/v1/users/me', 'users.me');
  L.get(s, '/api/v1/challenges?filter=IN_PROGRESS', 'challenge.list');
  L.get(s, '/api/v1/notifications', 'notification.list');
  L.get(s, '/api/v1/users/me/notification-settings', 'notification.settings');
  L.get(s, '/api/v1/verifications/progress', 'verification.progress');
  // 탐색
  L.get(s, '/api/v1/challenge-categories', 'challenge.categories');
  L.get(s, '/api/v1/challenges/trending', 'challenge.trending');
  const ex = L.get(s, '/api/v1/challenges/explore?sort=POPULAR&size=20', 'challenge.explore');
  L.get(s, '/api/v1/challenges/recommendations', 'challenge.recommendations');
  // 생성
  const id = L.createFromTemplate(s, TEMPLATE, L.GROUP_PUBLIC);
  if (id) {
    L.get(s, `/api/v1/challenges/${id}`, 'challenge.detail');
    L.get(s, `/api/v1/challenges/${id}/room`, 'room.home');
    L.get(s, `/api/v1/challenges/${id}/members`, 'challenge.members');
    L.get(s, `/api/v1/challenges/${id}/setup`, 'verification.setup');
    L.get(s, `/api/v1/challenges/${id}/verifications/today`, 'verification.today');
  }
  // 다른 사람 방 상세·가입
  let items = [];
  try { items = ex.json('data.items') || ex.json('data.content') || []; } catch (e) { items = []; }
  if (items.length) {
    const other = items[Math.floor(Math.random() * items.length)];
    const oid = other.challengeId || other.id;
    L.get(s, `/api/v1/challenges/${oid}`, 'challenge.detail');
  }
  // 마이페이지
  L.get(s, '/api/v1/profile', 'profile');
  L.get(s, '/api/v1/me/stats', 'me.stats');
  L.get(s, '/api/v1/me/tier', 'me.tier');
  L.get(s, `/api/v1/me/calendar?month=${new Date().toISOString().slice(0, 7)}`, 'me.calendar');
  L.refresh(s);
  L.get(s, '/api/v1/users/me', 'users.me');
  sleep(1);
}
