"""SNS ruleup-prod-alerts → Slack 수신 웹훅.

앱(OpsSlackChannel)은 Amazon Q 커스텀 알림 형식({"content": {"title", "description"}})으로 발행한다.
새 계정에는 Amazon Q 연결이 없어, 그 본문을 Slack 메시지로 바꿔 웹훅에 넣는다. 형식이 다른 메시지는 원문을 그대로 올린다.
웹훅 주소는 환경변수 SLACK_WEBHOOK_URL. 본문에 "urgent": true 가 있으면(보안 알림 — infra/security/alerts.sh)
SLACK_URGENT_WEBHOOK_URL 의 긴급 채널에도 함께 올린다.
"""
import json
import os
import urllib.request


def _text(sns: dict) -> str:
    raw = sns.get("Message", "")
    try:
        content = json.loads(raw).get("content") or {}
        title, desc = content.get("title"), content.get("description")
        if title or desc:
            return "\n".join(p for p in (f"*{title}*" if title else None, desc) if p)
    except (ValueError, AttributeError):
        pass
    subject = sns.get("Subject")
    return f"*{subject}*\n{raw}" if subject else raw


def _urgent(sns: dict) -> bool:
    try:
        return json.loads(sns.get("Message", "")).get("urgent") is True
    except (ValueError, AttributeError):
        return False


def _post(url: str, text: str) -> None:
    body = json.dumps({"text": text[:3500]}).encode()
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=8) as resp:
        resp.read()


def handler(event, _context):
    for record in event.get("Records", []):
        sns = record.get("Sns", {})
        text = _text(sns)
        _post(os.environ["SLACK_WEBHOOK_URL"], text)
        urgent_url = os.environ.get("SLACK_URGENT_WEBHOOK_URL")
        if urgent_url and _urgent(sns):
            _post(urgent_url, text)
    return {"forwarded": len(event.get("Records", []))}
