# 약관 원문

`GET /terms/{slug}/{version}` 이 여기 파일을 그대로 보여 준다. 경로는 `{slug}/{version}.md`.

| slug | 문서 | 버전 설정 |
|---|---|---|
| `terms-of-service` | 서비스 이용약관 | `app.client.terms-versions.terms-of-service` |
| `privacy-policy` | 개인정보 처리방침 | `app.client.terms-versions.privacy-policy` |
| `location-service` | 위치기반 서비스 이용약관 | `app.client.terms-versions.location-service` |

원문은 Notion 「약관」 페이지 첨부 3종이다. 개정하면 새 버전 파일을 **추가**하고 설정 버전을 올린다.
옛 파일은 지우지 않는다 — 과거 동의 버전의 원문을 열 수 있어야 한다.
현행 버전 파일이 없으면 `terms_document_missing` 으로 기동을 중단한다. URL 만 광고하고 404 를 내는 배포를 막는다.

`1.0.md` 3종은 2026-09-19에 [Notion 약관 페이지](https://app.notion.com/p/3d4ad4145fb6805cb68dd0016ff1652b)의 첨부를 바이트 변경 없이 옮겼다.
원문의 초안 안내, 사업자 정보 및 시행일 자리표시자도 그대로다. 문서 내용 확정은 별도이며, 임의의 값으로 채우지 않는다.

| 원문 | SHA-256 |
|---|---|
| 서비스 이용약관 | `99b05b73f372be3fb3a1a34ca66661ce08974d57fdff36565238d8e2db1bcd26` |
| 개인정보 처리방침 | `b4964054ac183a346eefc7530a69e5fef4c4bbb662f982ef884e862463905580` |
| 위치기반서비스 이용약관 | `24e7a05929c4c0b490c942775f18a178d607d4f858a7f262107f8cbb5036caae` |
