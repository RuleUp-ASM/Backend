# Android 초대 링크 호스팅 (NAV-06)

Cloudflare Pages 프로젝트 `ruleup-android-link`의 소스다. 도메인은
`android.ruleup.co.kr`이다. 종전 배포는 Git 연결 없는 직접 업로드였으므로
백엔드의 초대 URL 계약과 함께 이 저장소에서 관리한다.

## 경로 계약

| 링크 | 앱 설치·도메인 검증 완료 | 앱이 링크를 처리하지 못해 HTTP 요청이 온 경우 |
|---|---|---|
| `/c/{token}` | 해당 챌린지 가입 화면, 로그인 필요 시 로그인 후 이어서 표시 | Google Play |
| `/w/{token}` | 감시자 초대 수락 화면 | Google Play |
| `/inv/{code}` | 친구 추천 코드용 기존 앱 진입 | Google Play |

`/inv`는 친구 추천 코드이며 특정 챌린지 가입 토큰이 아니다. 챌린지는 백엔드가
발급하는 `/c`, 감시자는 `/w`를 공유해야 한다. 웹에서 가입·감시자 수락을 처리하지 않는다.

Android의 기존 Manifest와 `NavRouteUriParser`는 `/c`와 `/w`를 각 화면으로 구분한다.
설치된 앱으로 연결하는 주체는 **검증된 Android App Links**다. 브라우저에 URL을 직접
입력하거나 앱 링크 열기를 사용자가 꺼 둔 경우에는 HTTP 폴백으로 갈 수 있다.
서버가 User-Agent로 설치 여부를 추측하지 않는다.

HTTP 폴백은 실제 applicationId `com.ruleup.android_ruleup`의 Play Store로 302 이동한다.
초대 종류·토큰·원래 URL을 `referrer`에 보존한다. 설치 이후 자동 복원은 Android의
Install Referrer 소비 구현이 추가되어야 한다. 현재 앱에는 그 구현이 없으므로 설치 후
원래 초대 링크를 다시 연다. 스토어 등록 전에는 해당 URL에 설치 페이지가 표시되지 않는다.

## 앱 연결 인증서

`public/.well-known/assetlinks.json`의 지문은 2026-09-18 확인한 개발 APK
`app/build/outputs/apk/debug/app-debug.apk`의 실제 서명 인증서 SHA-256이다.
패키지명과 함께 `apksigner verify --print-certs`로 확인했다. 개인 키는 포함하지 않는다.
이 설정은 **그 인증서로 서명한 QA 앱**에 적용된다. 다른 개발자의 debug 인증서와
Google Play 배포 서명은 자동으로 신뢰되지 않는다.

Play 출시 전 Play Console의 **앱 서명 키 인증서** SHA-256을 추가하고 배포한다.
업로드 키와 앱 서명 키를 혼동하지 않는다. 알 수 없는 release 지문을 임의로 넣지 않는다.
QA가 끝나면 운영 도메인에서 debug 지문을 제거한다.

## 검증·배포

```sh
cd infra/android-links
npm test
npx wrangler pages dev public
# 검토용 preview: production branch(main)가 아닌 브랜치를 명시한다.
npx wrangler pages deploy public --project-name ruleup-android-link --branch qa-server-infra-followup
# 실제 도메인 반영은 릴리스 때 실행한다.
npx wrangler pages deploy public --project-name ruleup-android-link --branch main
```

초대 페이지 요청은 캐시하지 않는다. assetlinks는 리다이렉트 없이 HTTPS 200,
`application/json`으로 제공한다. 기존 `/inv/* → /invite` 리다이렉트 파일은 배포에
포함하지 않는다. 원래 토큰 없이 `/invite`에 도달한 요청에서는 토큰을 복원할 수 없다.

배포 후 QA APK를 설치한 기기에서 `pm verify-app-links --re-verify`와
`pm get-app-links`로 해당 도메인이 `verified`인지 확인한다. 강제 `approved`는 통과
증거로 쓰지 않는다. 앱 종료·실행 중 각각 `/c`와 `/w`를 외부 앱에서 눌러 목적지를
확인하고, 앱 미설치 상태에서는 Google Play 이동을 확인한다.

참고: [Android 웹사이트 연결 설정](https://developer.android.com/training/app-links/configure-assetlinks),
[App Links 검증](https://developer.android.com/training/app-links/verify-applinks),
[Cloudflare Pages advanced mode](https://developers.cloudflare.com/pages/functions/advanced-mode/).
