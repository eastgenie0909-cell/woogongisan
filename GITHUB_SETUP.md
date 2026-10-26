# 우공이산 GitHub 연결

- 저장소: https://github.com/eastgenie0909-cell/woogongisan
- 신호/화면 주소: https://eastgenie0909-cell.github.io/woogongisan/
- GitHub 설정 → Pages → Build and deployment → Source: GitHub Actions
- 첫 배포: `Publish Woogongisan` Actions. `main` 코드 변경 시 테스트 후 정적 화면을 게시한다.
- 종가 갱신: `Woogong close calculation` Actions. NYSE 일정·완료 종가를 확인해 새 결과를 계산하고 게시한다.

## 메리츠 연결 확인

2026-10-26 개인 PC에서 숨김 입력으로 인증하고, QQQ·TQQQ·BRK.B·GLD·SOXL·BITO의
2026-10-23 일봉 조회를 확인했다. 계좌 조회·주문은 실행하지 않았다. 키는 파일과 GitHub에 저장하지 않았다.

메리츠 수정주가와 기존 모델 자료의 겹치는 123거래일을 비교했을 때 SOXL·BITO에서
일간 수익률 차이가 확인됐다. 원인과 분배금·분할 조정 기준을 검증하기 전에는 이 자료를
기존 모델의 총수익 가격으로 대체하지 않는다. 현재 Pages는 2026-10-20 과거 미리보기이며
메리츠 자동 갱신은 아직 활성화하지 않았다. 인증 성공은 모델 운용 검증과 별개다.

다시 확인하려면 로컬에서 `./check_meritz.ps1`을 실행하고 App Key와 App Secret을 숨김 입력한다.
결과는 업로드에서 제외되는 `runtime/meritz-market-check.json`에 저장된다.
조회 도구는 해외 일봉 경로만 사용하며 계좌·주문 API는 호출하지 않는다.
현재 도구는 키를 유지하지 않으므로 상시 실행 서버의 비밀정보 설정은 별도다.
메리츠 계좌 키를 공개 소스·앱·Pages·GitHub Secrets에 넣지 않는다.

## 선택 사항: 기존 Tiingo 파이프라인

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
