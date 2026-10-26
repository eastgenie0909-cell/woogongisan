# 우공이산 GitHub 연결

- 저장소: https://github.com/eastgenie0909-cell/woogongisan
- 신호/화면 주소: https://eastgenie0909-cell.github.io/woogongisan/
- GitHub 설정 → Pages → Build and deployment → Source: GitHub Actions
- 첫 배포: `Publish Woogongisan` Actions. `main` 코드 변경 시 테스트 후 정적 화면을 게시한다.
- 종가 갱신: `Woogong close calculation` Actions. NYSE 일정·완료 종가를 확인해 새 결과를 계산하고 게시한다.

## 시세 API 키

저장소 Settings → Secrets and variables → Actions → New repository secret에서
이름 `TIINGO_TOKEN`, 값은 본인 Tiingo API 키로 등록한다. 키를 소스·채팅·앱 설정에 넣지 않는다.
등록 전에도 화면은 게시되며 과거 자료 또는 이전 상태 표시를 유지한다. 등록만으로 최신 계산 성공을 의미하지는 않는다.
등록 후 `Woogong close calculation`을 한 번 수동 실행해 게시된 `status.json`의 `state: ready`와 기준 거래일을 확인한다.
공급자 응답이 부족하거나 오류이면 `state: error`가 되어 이전 목표를 새 신호로 사용하지 않도록 표시한다.

## 앱 연결

0.5.2부터 위 주소를 기본 결과 서버로 사용한다. 기존 버전은 설정 → GitHub Pages 연결에
`https://eastgenie0909-cell.github.io/woogongisan`을 저장하고 최신 결과를 확인한다.
모델 결과 주소이므로 메리츠 인증키나 개인 자산을 전송하지 않는다.
메리츠 잔고·주문을 담당하는 개인 HTTPS 서버는 별도이며 GitHub Pages에서는 실행되지 않는다.

Firebase 푸시는 선택 사항이다. 사용할 때만 `GOOGLE_SERVICES_JSON`,
`FIREBASE_SERVICE_ACCOUNT_JSON`, `FCM_TOKENS`를 별도 설정한다.
개인 계좌 DB, 메리츠 인증키, 서버 토큰, 개발 도구·캐시는 이 저장소 업로드 대상에서 제외한다.
