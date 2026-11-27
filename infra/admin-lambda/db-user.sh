#!/usr/bin/env bash
# 서버에서 실행된다(provision.sh db 가 SSM 으로 보낸다). 비밀번호는 시크릿에서 읽어 임시 파일(600)로만 다룬다.
set -euo pipefail
export AWS_DEFAULT_REGION=ap-northeast-2
umask 077
D=$(mktemp -d); trap 'rm -rf "$D"' EXIT
python3 - "$D" <<'PY'
import json, subprocess, sys
d = sys.argv[1]
def secret(name):
    return json.loads(subprocess.run(["aws", "secretsmanager", "get-secret-value", "--secret-id", name, "--query", "SecretString", "--output", "text"],
                                     check=True, capture_output=True, text=True).stdout)
master = secret("ruleup-prod/app")["DB_PASSWORD"]; admin = secret("ruleup-prod/admin")["DB_PASSWORD"]
open(d + "/my.cnf", "w").write("[client]\nuser=ruleup\npassword=\"%s\"\nhost=dbproxy\nssl-mode=REQUIRED\n" % master.replace('"', '\\"'))
open(d + "/grants.sql", "w").write("""
CREATE USER IF NOT EXISTS 'ruleup_admin'@'%%' IDENTIFIED BY '%(pw)s';
ALTER USER 'ruleup_admin'@'%%' IDENTIFIED BY '%(pw)s' REQUIRE SSL WITH MAX_USER_CONNECTIONS 20;
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'ruleup_admin'@'%%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `RuleUp7`.* TO 'ruleup_admin'@'%%';
SHOW GRANTS FOR 'ruleup_admin'@'%%';
""" % {"pw": admin})
PY
chmod 644 "$D"/*; chmod 755 "$D"
docker run --rm --network ruleup_default -v "$D:/w:ro" mysql:8.4 sh -c 'mysql --defaults-extra-file=/w/my.cnf < /w/grants.sql' < /dev/null
