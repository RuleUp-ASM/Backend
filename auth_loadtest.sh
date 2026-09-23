#!/usr/bin/env bash
#
# RuleUp 인증(Auth) 부하/동작 테스트 스크립트
# - 토큰 발급 -> 보호 엔드포인트 정상 인증 -> 음성(negative) 인증 케이스까지 한 번에 점검
# - 의존성: hey, jq, curl
#
# 사용법:
#   1) 아래 "설정" 부분을 본인 환경에 맞게 수정
#   2) chmod +x auth_loadtest.sh
#   3) ./auth_loadtest.sh
#
# 환경변수로도 덮어쓸 수 있음. 예:
#   BASE_URL="https://staging-api.ruleup.xyz" TOKEN="eyJ..." ./auth_loadtest.sh
#
set -uo pipefail

# ========================= 설정 =========================
# 끝에 슬래시(/) 빼고 입력
BASE_URL="${BASE_URL:-https://staging-api.ruleup.example}"

# 인증이 필요한 "보호 엔드포인트" (GET 기준)
PROTECTED_PATH="${PROTECTED_PATH:-/api/challenges}"

# ---- 토큰 얻는 방법 (둘 중 하나) ----
# 방법 A: 토큰을 직접 갖고 있으면 여기 넣기 (이게 채워져 있으면 로그인 단계 건너뜀)
TOKEN="${TOKEN:-}"

# 방법 B: dev/test용 로그인 엔드포인트로 발급받기 (TOKEN이 비어 있을 때만 동작)
LOGIN_PATH="${LOGIN_PATH:-/api/auth/test-login}"           # 발급 엔드포인트 경로
LOGIN_METHOD="${LOGIN_METHOD:-POST}"                       # 보통 POST
LOGIN_BODY="${LOGIN_BODY:-{\"userId\":1}}"                 # 발급에 필요한 바디 (엔드포인트 스펙에 맞게)
TOKEN_JSON_FIELD="${TOKEN_JSON_FIELD:-.accessToken}"       # 응답에서 토큰 꺼낼 jq 경로 (.accessToken, .data.token 등)

# 발급 엔드포인트 자체에도 부하를 줄지 (jti 충돌 회귀 테스트용). 1=실행, 0=건너뜀
TEST_ISSUANCE_LOAD="${TEST_ISSUANCE_LOAD:-1}"

# 부하 단계 (요청수 n / 동시성 c)
STAGES=(
  "500 10"
  "2000 50"
  "5000 100"
)
# =======================================================

OUTDIR="loadtest-$(date +%Y%m%d-%H%M%S)"
RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; BLU=$'\033[34m'; RST=$'\033[0m'

info()  { echo "${BLU}[INFO]${RST} $*"; }
ok()    { echo "${GRN}[ OK ]${RST} $*"; }
warn()  { echo "${YEL}[WARN]${RST} $*"; }
fail()  { echo "${RED}[FAIL]${RST} $*"; }

# ---------- 0. 의존성 체크 ----------
for bin in hey jq curl; do
  if ! command -v "$bin" >/dev/null 2>&1; then
    fail "'$bin' 가 설치돼 있지 않음."
    case "$bin" in
      hey)  echo "      설치: brew install hey   (또는 go install github.com/rakyll/hey@latest)";;
      jq)   echo "      설치: brew install jq";;
    esac
    exit 1
  fi
done

mkdir -p "$OUTDIR"
PROTECTED_URL="${BASE_URL}${PROTECTED_PATH}"
info "결과 저장 폴더: ${OUTDIR}"
info "대상(보호 엔드포인트): ${PROTECTED_URL}"
echo

# ---------- 1. 토큰 확보 ----------
if [[ -n "$TOKEN" ]]; then
  ok "직접 입력한 토큰 사용 (로그인 단계 건너뜀)"
else
  LOGIN_URL="${BASE_URL}${LOGIN_PATH}"
  info "토큰 발급 시도: ${LOGIN_METHOD} ${LOGIN_URL}"
  LOGIN_RESP="$(curl -s -X "$LOGIN_METHOD" "$LOGIN_URL" \
    -H "Content-Type: application/json" \
    -d "$LOGIN_BODY")"

  TOKEN="$(echo "$LOGIN_RESP" | jq -r "$TOKEN_JSON_FIELD" 2>/dev/null)"

  if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
    fail "토큰 발급 실패. 응답 본문:"
    echo "$LOGIN_RESP" | head -c 500
    echo
    warn "LOGIN_PATH / LOGIN_BODY / TOKEN_JSON_FIELD 가 실제 스펙과 맞는지 확인하세요."
    warn "OAuth 실로그인은 hey로 자동화 불가 — dev 로그인 엔드포인트가 없으면 TOKEN= 에 직접 넣어 실행하세요."
    exit 1
  fi
  ok "토큰 발급 성공 (앞 20자: ${TOKEN:0:20}...)"
fi
echo

# ---------- 2. 토큰 유효성 사전 확인 (부하 주기 전 1회) ----------
info "보호 엔드포인트 사전 확인 (단일 요청)..."
PRE_CODE="$(curl -s -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer ${TOKEN}" "$PROTECTED_URL")"
if [[ "$PRE_CODE" == "200" ]]; then
  ok "인증 정상 (200). 부하테스트 진행."
elif [[ "$PRE_CODE" == "401" || "$PRE_CODE" == "403" ]]; then
  fail "토큰으로 접근했는데 ${PRE_CODE} 반환. 토큰 만료(exp)거나 권한 문제일 수 있음. 중단."
  exit 1
else
  warn "예상 밖 응답코드: ${PRE_CODE}. 일단 진행하되 결과 해석에 주의."
fi
echo

# ---------- 3. 인증된 요청 부하 (단계별 램프업) ----------
echo "${BLU}===== [A] 인증 OK 상태로 보호 엔드포인트 부하 =====${RST}"
for stage in "${STAGES[@]}"; do
  read -r N C <<< "$stage"
  info "단계: n=${N}, c=${C}"
  OUT="${OUTDIR}/A_auth_n${N}_c${C}.txt"
  hey -n "$N" -c "$C" \
    -H "Authorization: Bearer ${TOKEN}" \
    "$PROTECTED_URL" | tee "$OUT"
  echo "----------------------------------------------"
done
echo

# ---------- 4. 음성(negative) 인증 케이스 ----------
echo "${BLU}===== [B] Negative: 토큰 없음 (전부 401 기대) =====${RST}"
OUT="${OUTDIR}/B_no_token.txt"
hey -n 500 -c 20 "$PROTECTED_URL" | tee "$OUT"
echo "기대: Status code distribution 이 [401] 위주여야 함 (인증 누락이 제대로 막히는지)"
echo

echo "${BLU}===== [C] Negative: 깨진 토큰 (전부 401 기대) =====${RST}"
OUT="${OUTDIR}/C_bad_token.txt"
hey -n 500 -c 20 \
  -H "Authorization: Bearer this.is.not.a.valid.jwt" \
  "$PROTECTED_URL" | tee "$OUT"
echo "기대: [401]. 만약 500이 섞이면 토큰 파싱부에서 예외가 안 잡히는 것 — 버그 신호."
echo

# ---------- 5. (옵션) 발급 엔드포인트 부하 = jti 충돌 회귀 테스트 ----------
if [[ "$TEST_ISSUANCE_LOAD" == "1" && -n "${LOGIN_PATH:-}" ]]; then
  echo "${BLU}===== [D] 토큰 발급 엔드포인트 부하 (jti 충돌/동시발급 점검) =====${RST}"
  LOGIN_URL="${BASE_URL}${LOGIN_PATH}"
  OUT="${OUTDIR}/D_issuance_load.txt"
  # hey 의 -m 메서드, -d 바디, -T 컨텐츠타입 사용
  hey -n 1000 -c 50 \
    -m "$LOGIN_METHOD" \
    -T "application/json" \
    -d "$LOGIN_BODY" \
    "$LOGIN_URL" | tee "$OUT"
  echo "기대: 동시 발급에도 [200] 위주. 5xx 가 튀면 발급 경로 동시성 문제(예: jti 유니크 충돌) 의심."
  echo
fi

# ---------- 6. 요약 ----------
echo "${GRN}===== 완료 =====${RST}"
echo "원본 결과: ${OUTDIR}/ 폴더"
echo
echo "해석 가이드:"
echo " - [A] 단계가 올라갈수록 p95/p99 가 얼마나 벌어지는지 = 서버 신호 (네트워크 변동 무관)"
echo " - [A] 의 에러율이 특정 동시성에서 갑자기 튀면 거기가 한계 구간"
echo " - [B][C] 는 status 분포가 401 위주여야 정상. 200 이 섞이면 인증 우회 가능성, 500 이면 예외처리 누락"
echo " - 토큰 exp 가 테스트 중간에 끝나면 [A] 후반부에 401 이 갑자기 늘어남 -> 서버문제로 오해 금지"
