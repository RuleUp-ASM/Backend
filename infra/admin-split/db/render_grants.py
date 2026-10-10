#!/usr/bin/env python3
"""DB 계정 3종의 권한 SQL 을 만든다 — grants.sql.

    python3 infra/admin-split/db/render_grants.py            # grants.sql 갱신
    python3 infra/admin-split/db/render_grants.py --check    # 갱신이 필요하면 1 로 끝난다(CI·리뷰용)

계정
  ruleup_app      공개 API. 전 테이블 DML + 파티션 정비 테이블 6개에만 ALTER·CREATE·DROP
                  (SignalPartitionMaintainer 가 매일 REORGANIZE/DROP PARTITION 을 한다).
  ruleup_admin    관리자 API. admin-grants.txt 의 테이블·권한만. DDL 없음.
  ruleup_migrator 마이그레이션 태스크 전용. 스키마 단위 DDL+DML. GRANT OPTION 없음.

마스터 계정(ruleup)은 사람이 계정 관리에만 쓴다. 어느 서비스에도 주지 않는다.

비밀번호는 SQL 에 들어가지 않는다. 실행 컨테이너가 Secrets Manager 에서 주입받은 환경변수
(${APP_DB_PASSWORD} 등)를 셸이 펼친다 — RunTask 요청·CloudTrail 에 비밀번호가 남지 않게.
스키마 이름은 __SCHEMA__ 로 두고 실행 때 바꾼다.
"""
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
MIGRATIONS = HERE.parents[2] / "src/main/resources/db/migration"

# SignalDomain 의 table()·anomalyTable() — 일자 파티션을 매일 정비한다.
PARTITIONED = [
    "verification_location_signals", "anomaly_location_events",
    "verification_device_usage_signals", "anomaly_device_usage_events",
    "verification_health_connect_signals", "anomaly_health_connect_events",
]


def tables_in_migrations():
    names = set()
    for f in MIGRATIONS.glob("V*.sql"):
        names |= set(re.findall(r"CREATE TABLE (?:IF NOT EXISTS )?`?(\w+)`?", f.read_text()))
    return names


def admin_grants():
    out = {}
    for line in (HERE / "admin-grants.txt").read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        table, privs = line.split(":")
        out[table.strip()] = [p.strip().upper() for p in privs.split(",")]
    return out


def render():
    known = tables_in_migrations()
    grants = admin_grants()
    unknown = sorted(set(grants) - known)
    if unknown:
        sys.exit(f"admin-grants.txt 에 마이그레이션에 없는 테이블이 있다(대소문자 확인): {unknown}")
    for t in PARTITIONED:
        if t not in known:
            sys.exit(f"파티션 테이블 {t} 이 마이그레이션에 없다 — SignalDomain 과 맞춘다")

    s = ["-- 생성 파일 — 직접 고치지 말고 admin-grants.txt 를 고친 뒤 render_grants.py 를 돌린다.",
         "-- 여러 번 돌려도 같은 결과다(CREATE USER IF NOT EXISTS + 비밀번호 재설정 + 권한 재부여).",
         "",
         "-- 1) 마이그레이션 계정 — 마이그레이션 태스크에만 준다",
         "CREATE USER IF NOT EXISTS 'ruleup_migrator'@'%' IDENTIFIED BY '${MIGRATOR_DB_PASSWORD}';",
         "ALTER USER 'ruleup_migrator'@'%' IDENTIFIED BY '${MIGRATOR_DB_PASSWORD}' __REQUIRE_SSL__;",
         "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES, CREATE VIEW, SHOW VIEW, TRIGGER,"
         " LOCK TABLES, EXECUTE, CREATE ROUTINE, ALTER ROUTINE ON `__SCHEMA__`.* TO 'ruleup_migrator'@'%';",
         "",
         "-- 2) 공개 API 계정 — 전 테이블 DML, DDL 은 파티션 정비 테이블에만",
         "CREATE USER IF NOT EXISTS 'ruleup_app'@'%' IDENTIFIED BY '${APP_DB_PASSWORD}';",
         "ALTER USER 'ruleup_app'@'%' IDENTIFIED BY '${APP_DB_PASSWORD}' __REQUIRE_SSL__;",
         "GRANT SELECT, INSERT, UPDATE, DELETE ON `__SCHEMA__`.* TO 'ruleup_app'@'%';"]
    for t in PARTITIONED:
        s.append(f"GRANT ALTER, CREATE, DROP ON `__SCHEMA__`.`{t}` TO 'ruleup_app'@'%';")
    s += ["",
          "-- 3) 관리자 API 계정 — 실행 근거가 있는 테이블·작업만(admin-grants.txt)",
          "CREATE USER IF NOT EXISTS 'ruleup_admin'@'%' IDENTIFIED BY '${ADMIN_DB_PASSWORD}';",
          "ALTER USER 'ruleup_admin'@'%' IDENTIFIED BY '${ADMIN_DB_PASSWORD}' __REQUIRE_SSL__;",
          # 이전 실행에서 준 테이블 권한이 목록에서 빠졌을 수 있다 — 전부 걷고 다시 준다.
          "REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'ruleup_admin'@'%';"]
    for t in sorted(grants, key=str.lower):
        s.append(f"GRANT {', '.join(grants[t])} ON `__SCHEMA__`.`{t}` TO 'ruleup_admin'@'%';")
    s += ["",
          "-- 연결 상한 — 한 계정이 RDS 연결을 다 먹지 못하게(풀 크기 × 최대 태스크 수 × 롤링 2배 + 여유)",
          "ALTER USER 'ruleup_admin'@'%' WITH MAX_USER_CONNECTIONS 20;",
          "ALTER USER 'ruleup_migrator'@'%' WITH MAX_USER_CONNECTIONS 5;",
          "",
          "SHOW GRANTS FOR 'ruleup_admin'@'%';",
          ""]
    return "\n".join(s)


if __name__ == "__main__":
    target = HERE / "grants.sql"
    rendered = render()
    if "--check" in sys.argv:
        if not target.exists() or target.read_text() != rendered:
            sys.exit("grants.sql 이 admin-grants.txt 와 다르다 — render_grants.py 를 돌린다")
        print("grants.sql 최신")
    else:
        target.write_text(rendered)
        print(f"wrote {target}")
