# 약관 원문

`GET /terms/{slug}/{version}` 이 여기 파일을 그대로 보여 준다. 경로는 `{slug}/{version}.md`.

| slug | 문서 | 버전 설정 |
|---|---|---|
| `terms-of-service` | 서비스 이용약관 | `app.client.terms-versions.terms-of-service` |
| `privacy-policy` | 개인정보 처리방침 | `app.client.terms-versions.privacy-policy` |
| `location-service` | 위치기반 서비스 이용약관 | `app.client.terms-versions.location-service` |

원문은 Notion 「약관」 페이지 첨부 3종이다. 개정하면 새 버전 파일을 **추가**하고 설정 버전을 올린다.
옛 파일은 지우지 않는다 — 과거 동의 버전의 원문을 열 수 있어야 한다.
현행 버전 파일이 없으면 기동 로그에 `terms_document_missing` 경고가 남고 그 URL 은 404 다.
