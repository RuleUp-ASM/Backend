"""Use CloudWatch's read-only parser to verify the production filter against log samples."""
import json
from pathlib import Path
import subprocess

config = json.loads(Path(__file__).with_name("sync-rejections-filter.json").read_text())
samples = [
    "2026-09-19T10:00:00.000Z INFO 1 --- [api] [nio] VerificationSyncService : sync_envelope_rejected userId=test reason=MISSING_DEVICE_TIME streak=1 present=[deviceTime=false]",
    "2026-09-19T10:00:01.000Z INFO 1 --- [api] [nio] VerificationSyncService : sync_envelope_rejected userId=test reason=MISSING_DEVICE_TIME streak=2 present=[deviceTime=false]",
    "2026-09-19T10:00:02.000Z WARN 1 --- [api] [nio] VerificationSyncService : sync_envelope_rejected userId=test reason=MISSING_DEVICE_TIME streak=3 present=[deviceTime=false]",
    "2026-09-19T10:00:03.000Z WARN 1 --- [api] [nio] VerificationSyncService : sync_envelope_rejected userId=test reason=MISSING_COVERED_FROM streak=4 present=[coveredFrom=false]",
    "2026-09-19T10:00:04.000Z WARN 1 --- [api] [nio] OtherService : unrelated failure",
]
result = subprocess.run([
    "aws", "logs", "test-metric-filter", "--region", "ap-northeast-2",
    "--filter-pattern", config["filterPattern"], "--log-event-messages", json.dumps(samples),
    "--output", "json", "--no-cli-pager",
], check=True, capture_output=True, text=True)
matches = {match["eventNumber"] for match in json.loads(result.stdout)["matches"]}
if matches != {3, 4}:
    raise SystemExit(f"Unexpected matching events: {matches}")
print("CloudWatch filter verified: only consecutive rejection WARN events match.")
