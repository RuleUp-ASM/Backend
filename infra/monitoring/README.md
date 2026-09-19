# sync 연속 400 경보

`VerificationSyncService`는 같은 계정에서 봉투 검증이 3회 이상 연속 실패하면 `sync_envelope_rejected`를 WARN으로 남긴다. 정상 봉투가 들어오면 연속 횟수가 초기화된다. 인스턴스별 근사치이며 재시작/다중 인스턴스 분산에 걸친 연속 횟수는 합산하지 않는다.

CloudWatch 로그 필터는 일반 Spring 로그의 **WARN + 이벤트 이름**을 함께 검사한다. JSON 필터를 사용하면 시각·레벨 접두어 때문에 매치되지 않는다. 5분 동안 매치 1건 이상이면 기존 `ruleup-stg-alarms` SNS 채널에 경보를 보내고, 정상 복귀도 같은 채널에 알린다. 로그가 없으면 정상으로 취급한다. 사용자 ID는 메트릭 차원에 넣지 않는다.

`deploy-monitoring.yml`은 이 설정이 dev에 반영될 때와 수동 실행 때 적용한다. 앱 배포와 분리해 경보 설정 실패가 실행 중인 API 배포를 방해하지 않게 한다. 동일 이름의 필터·알람을 갱신하므로 재실행해도 중복 생성하지 않는다.

## 최초 권한 설정

현재 `ruleup-stg-gha-deploy` 역할에는 ECR/ECS 권한만 있다. IAM 관리 권한이 있는 계정에서 아래 정책을 **별도 inline 정책**으로 한 번 추가한 후 워크플로를 실행한다. 기존 `deploy-perms` 정책을 덮어쓰지 않는다.

```sh
aws iam put-role-policy --role-name ruleup-stg-gha-deploy \
  --policy-name sync-monitoring \
  --policy-document file://infra/monitoring/deploy-policy.json
```

## 검증·적용

```sh
# 읽기 전용: INFO 1·2회와 무관한 WARN은 제외하고 3·4회 WARN만 매치하는지 검사
python3 infra/monitoring/verify-sync-filter.py
# AWS 설정 변경: 서울 리전 stg 로그 그룹에 필터 및 SNS 경보 적용
bash infra/monitoring/apply-sync-alarm.sh
```

설정 적용 후 세 번 연속 잘못된 봉투를 보내는 QA 계정으로 경보와 SNS 수신을 확인한다. 실제 경보 발송은 수신자의 구독 상태에도 의존한다. 이 저장소 변경 자체는 IAM·CloudWatch 리소스를 수정하지 않는다.

문법 근거: [CloudWatch 텍스트 필터](https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/FilterAndPatternSyntax.html), [PutMetricAlarm](https://docs.aws.amazon.com/cli/latest/reference/cloudwatch/put-metric-alarm.html).
