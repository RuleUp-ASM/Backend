#!/usr/bin/env bash
# 시크릿·파라미터에서 컨테이너 env 파일을 만든다. 값은 화면에 찍지 않는다.
#   /opt/ruleup/app.env    앱 컨테이너 (Secrets Manager ruleup-prod/app + SSM /ruleup/prod/app-env)
#   /opt/ruleup/infra.env  cloudflared·alloy (Secrets Manager ruleup-prod/infra)
#   /opt/ruleup/.env       compose 변수 (이미지·DB 대상)
set -euo pipefail
export AWS_DEFAULT_REGION=ap-northeast-2
cd /opt/ruleup
umask 077
python3 - <<'PY'
import json, subprocess

def aws(*a):
    return subprocess.run(["aws", *a, "--output", "text"], check=True, capture_output=True, text=True).stdout.rstrip("\n")

def write(path, pairs):
    lines = []
    for k, v in pairs.items():
        v = str(v)
        if "\n" in v:
            raise SystemExit(f"{k}: 값에 줄바꿈이 있어 env 파일에 못 넣는다")
        lines.append(f"{k}={v}")
    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")

secret = json.loads(aws("secretsmanager", "get-secret-value", "--secret-id", "ruleup-prod/app", "--query", "SecretString"))
# 시크릿 키 → 앱이 읽는 이름. 기존 ECS 태스크 정의의 secrets 매핑과 같다.
rename = {"DB_PASSWORD": "SPRING_DATASOURCE_PASSWORD"}
app = {rename.get(k, k): v for k, v in secret.items()}
# 시크릿이 아닌 설정은 SSM 파라미터 한 개(JSON)에 둔다 — 강제 업데이트 버전 등을 배포 없이 바꾸려고.
app.update(json.loads(aws("ssm", "get-parameter", "--name", "/ruleup/prod/app-env", "--query", "Parameter.Value")))
write("app.env", app)

infra = json.loads(aws("secretsmanager", "get-secret-value", "--secret-id", "ruleup-prod/infra", "--query", "SecretString"))
write("infra.env", infra)

deploy = json.loads(aws("ssm", "get-parameter", "--name", "/ruleup/prod/deploy", "--query", "Parameter.Value"))
write(".env", {"IMAGE_BLUE": deploy["image"], "IMAGE_GREEN": deploy["image"], "DB_HOST": deploy["db_host"]})
print("rendered: app.env(%d) infra.env(%d) .env" % (len(app), len(infra)))
PY
