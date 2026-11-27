"""SNS ruleup-prod-alerts → Slack 수신 웹훅.

앱(OpsSlackChannel)은 Amazon Q 커스텀 알림 형식({"content": {"title", "description"}})으로 발행한다.
새 계정에는 Amazon Q 연결이 없어, 그 본문을 Slack 메시지로 바꿔 웹훅에 넣는다. 형식이 다른 메시지는 원문을 그대로 올린다.
웹훅 주소는 환경변수 SLACK_WEBHOOK_URL.
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


def handler(event, _context):
    for record in event.get("Records", []):
        body = json.dumps({"text": _text(record.get("Sns", {}))[:3500]}).encode()
        req = urllib.request.Request(os.environ["SLACK_WEBHOOK_URL"], data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=8) as resp:
            resp.read()
    return {"forwarded": len(event.get("Records", []))}
