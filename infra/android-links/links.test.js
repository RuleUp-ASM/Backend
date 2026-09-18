import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import worker from "./public/_worker.js";

const origin = "https://android.ruleup.co.kr";
const request = (path, method = "GET") => worker.fetch(new Request(origin + path, { method }), {});

for (const [path, type, token] of [
  ["/c/Abc_123-def", "challenge", "Abc_123-def"],
  ["/w/Watcher_123", "watcher", "Watcher_123"],
  ["/inv/H62IQM", "friend", "H62IQM"],
]) {
  test(`${type}: HTTP fallback preserves the invitation and targets the real package`, async () => {
    for (const method of ["GET", "HEAD"]) {
      const result = await request(path, method);
      assert.equal(result.status, 302);
      assert.equal(result.headers.get("cache-control"), "no-store");
      const target = new URL(result.headers.get("location"));
      assert.equal(target.origin + target.pathname, "https://play.google.com/store/apps/details");
      assert.equal(target.searchParams.get("id"), "com.ruleup.android_ruleup");
      const referrer = new URLSearchParams(target.searchParams.get("referrer"));
      assert.equal(referrer.get("ruleup_invite_type"), type);
      assert.equal(referrer.get("ruleup_invite_token"), token);
      assert.equal(referrer.get("ruleup_invite_url"), origin + path);
      assert.equal(await result.text(), "");
    }
  });
}

test("link association is served directly with the verified QA APK certificate", async () => {
  const text = await readFile(new URL("./public/.well-known/assetlinks.json", import.meta.url), "utf8");
  const result = await worker.fetch(new Request(origin + "/.well-known/assetlinks.json"), {
    ASSETS: { fetch: async () => new Response(text, { headers: { "Content-Type": "application/json" } }) },
  });
  assert.equal(result.status, 200);
  assert.equal(result.headers.get("location"), null);
  assert.match(result.headers.get("content-type"), /application\/json/);
  const [association] = await result.json();
  assert.deepEqual(association.relation, ["delegate_permission/common.handle_all_urls"]);
  assert.equal(association.target.package_name, "com.ruleup.android_ruleup");
  assert.ok(association.target.sha256_cert_fingerprints.length > 0);
  for (const fingerprint of association.target.sha256_cert_fingerprints) {
    assert.match(fingerprint, /^(?:[A-F0-9]{2}:){31}[A-F0-9]{2}$/);
  }
  assert.ok(!text.includes("REPLACE_ME"));
});

test("malformed links do not reach the store or another website", async () => {
  for (const path of ["/w", "/c/", "/w/a/b", "/x/token", "/c/%2Fbad", "/w/" + "a".repeat(257)]) {
    const result = await request(path);
    assert.equal(result.status, 404, path);
    assert.equal(result.headers.get("location"), null);
  }
  const result = await request("/w/valid?redirect=https://example.com&token=changed");
  assert.match(result.headers.get("location"), /^https:\/\/play\.google\.com\//);
  assert.ok(!result.headers.get("location").includes("example.com"));
  assert.ok(!result.headers.get("location").includes("changed"));
  assert.equal((await request("/w/valid", "POST")).status, 405);
});

test("legacy landing does not claim that an invitation has been accepted", async () => {
  const result = await request("/invite");
  assert.equal(result.status, 200);
  assert.ok((await result.text()).includes("초대받은 원래 링크"));
});
