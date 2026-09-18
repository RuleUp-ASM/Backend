const PACKAGE = "com.ruleup.android_ruleup";
const APP_ORIGIN = "https://android.ruleup.co.kr";
const TYPES = { c: "challenge", w: "watcher", inv: "friend" };

function headers(extra = {}) {
  return {
    "Cache-Control": "no-store",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
    "X-Robots-Tag": "noindex, nofollow",
    ...extra,
  };
}

export default {
  async fetch(request, env) {
    if (!["GET", "HEAD"].includes(request.method)) {
      return new Response(null, { status: 405, headers: headers({ Allow: "GET, HEAD" }) });
    }
    const url = new URL(request.url);
    // Android verifies this exact HTTPS resource. Never redirect it to HTML or the store.
    if (url.pathname === "/.well-known/assetlinks.json") {
      return env.ASSETS.fetch(request);
    }
    const match = /^\/(c|w|inv)\/([A-Za-z0-9_-]{1,256})\/?$/.exec(url.pathname);
    if (match) {
      // A verified App Link opens the installed app before this HTTP fallback is requested.
      // Keep the type and token for the store referrer; never collapse /c or /w to /invite.
      const [, segment, token] = match;
      const referrer = new URLSearchParams({
        ruleup_invite_type: TYPES[segment],
        ruleup_invite_token: token,
        ruleup_invite_url: `${APP_ORIGIN}/${segment}/${token}`,
      });
      const store = new URL("https://play.google.com/store/apps/details");
      store.searchParams.set("id", PACKAGE);
      store.searchParams.set("referrer", referrer.toString());
      return new Response(null, { status: 302, headers: headers({ Location: store.href }) });
    }
    if (url.pathname === "/" || url.pathname === "/invite") {
      const html = `<!doctype html><html lang="ko"><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>RuleUp 초대</title><body><main><h1>RuleUp 초대</h1>
<p>초대받은 원래 링크를 열어 주세요. 챌린지 가입과 감시자 수락은 앱에서 진행해요.</p>
<a href="https://play.google.com/store/apps/details?id=${PACKAGE}">Google Play에서 RuleUp 보기</a>
</main></body></html>`;
      return new Response(request.method === "HEAD" ? null : html, {
        headers: headers({ "Content-Type": "text/html; charset=utf-8",
          "Content-Security-Policy": "default-src 'none'; base-uri 'none'; frame-ancestors 'none'" }),
      });
    }
    return new Response(request.method === "HEAD" ? null : "초대 링크를 다시 확인해 주세요.", {
      status: 404, headers: headers({ "Content-Type": "text/plain; charset=utf-8" }),
    });
  },
};
