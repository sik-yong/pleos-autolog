# AutoLog Business

법인·사업자 차량의 운행을 차량 데이터로 자동 기록하고, 국세청 업무용승용차 운행기록부를 만드는 Pleos Connect 앱.

## 동작

- 기어 P 해제 → 운행 시작 (계기판 누적거리 기록)
- P만으로는 끝내지 않음. P 상태에서 운전석 벨트 해제·문 열림·내비 목적지 도착 중 하나가 오면 종료
- 하차 신호 없이 P로 10분이 지나면 "운행을 마칠까요?" 한 번만 질문. 아니요면 같은 정차 중엔 다시 묻지 않음
- 종료 시 거리부터 저장하고 TTS로 용도 질문 → STT 답변으로 출퇴근/일반업무/개인 분류
- 답을 못 받은 운행은 다음 출발 때 다시 질문
- 종료 후 30분 안에 다시 출발하면 이전 운행에 이어 붙일지 질문 (목적지 도착으로 끝난 운행은 제외)
- 앱이 꺼져 있던 동안의 계기판 차이는 "미기록 구간" 운행으로 남기고 질문
- 주행(D) 중에는 Pleos가 앱 화면을 가리므로 출발 시 질문은 음성으로만 답함. 정차 중엔 화면 버튼으로도 선택
- 정차 중 화면에서 운행 목록 확인, `운행기록부.csv` 내보내기 (앱 외부 저장소 폴더)

## 사용 SDK

Vehicle(Gear, Odometer, Seat, Door) · NaviHelper(도착 이벤트) · TextToSpeech · SpeechToText (ON_DEVICE 모드)

## 실행 방법

1. Android Studio에 Pleos Connect Emulator(API 34, v2.1.0)를 설치하고 AVD 실행
   ([설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator))
2. 빌드·설치 (JDK 17 이상)

```bash
./gradlew :app:installDebug
```

3. 앱 실행 후 Virtual Console(`adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088)에서 기어 D → P, Door Position Front left Open으로 운행을 만들거나, 화면의 `운행 시작`/`운행 종료` 버튼 사용

## 테스트

```bash
./gradlew :app:testDebugUnitTest
```

## APK

`apk/` 폴더 (제출 시 `팀명_AutoLogBusiness_release.apk`)
