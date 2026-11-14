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
- 정차·대기 중 화면에서 운행 목록 확인, 운행을 눌러 용도 수정 (출발하면 수정 창을 닫음)
- 차량·사용자 정보(상호, 사업자등록번호, 차종, 자동차등록번호, 부서, 성명) 입력. 부서·성명은 출발 시점 값으로 운행마다 기록
- 운행기록부 CSV 내보내기: 공용 `다운로드/AutoLog/운행기록부_날짜시각.csv`에 저장 (Android 10+, 저장소 권한 불필요). 공유받을 앱이 있으면 공유 버튼 표시
  - 머리말: 상호·사업자등록번호·차종·자동차등록번호
  - 표: 사용일자, 부서, 성명, 주행 전·후 계기판 거리, 주행거리, 출퇴근용·일반업무용 거리, 비고
  - 월별 소계와 기간 합계(총주행거리, 업무용 사용거리, 업무사용비율). 미분류 운행은 총주행거리에만 포함

## 사용 SDK

Vehicle(Gear, Odometer, Seat, Door) · NaviHelper(도착 이벤트) · TextToSpeech · SpeechToText (ON_DEVICE 모드)

## 실행 방법

1. Android Studio에 Pleos Connect Emulator(API 34, v2.1.0)를 설치하고 AVD 실행
   ([설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator))
2. 빌드·설치 (JDK 17~21. JDK 25에서는 빌드가 실패한다)

```bash
./gradlew :app:installDebug
```

3. 앱 실행 후 Virtual Console(`adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088)에서 기어 D → P, Door Position Front left Open으로 운행을 만든다. debug 빌드에서는 화면의 `시연: 운행 시작`/`시연: 운행 종료` 버튼도 쓸 수 있다(제출한 release APK에는 이 버튼이 없다)

4. 내보낸 CSV 꺼내기 (에뮬레이터 앱은 사용자 10에서 실행된다)

```bash
adb shell content query --user 10 --uri content://media/external/downloads --projection _id:_display_name
adb shell content read --user 10 --uri content://media/external/downloads/<_id> > 운행기록부.csv
```

## 테스트 환경

- Pleos Connect Emulator v2.1.0 (AVD `Pleos_Connect_v2.1.0`, API 34)
- 화면 규격: 2560×1440 가로. 앱은 오른쪽 앱 영역(약 1680×1210)에 표시된다
- 필요한 설정: 없음. 차량 신호는 Virtual Console로 넣는다
- 주행(D) 중에는 Pleos가 앱 화면을 가리므로 화면 확인은 P 상태에서 한다

## 단위 테스트

```bash
./gradlew :app:testDebugUnitTest
```

## APK

`apk/autolog_AutoLogBusiness_release.apk` (release v0.2.1, versionCode 3)

release 빌드:

1. 키스토어를 만든다(한 번만). 키스토어와 비밀번호는 잃어버리면 같은 서명으로 업데이트할 수 없으니 따로 보관한다.
   ```bash
   mkdir -p ~/autolog-keys
   keytool -genkeypair -v -keystore ~/autolog-keys/autolog-release.jks -alias autolog -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=autolog"
   ```
   `-dname`을 지정해 인증서에 실명 등 개인정보가 들어가지 않게 한다.
2. `keystore.properties.example`을 `keystore.properties`로 복사해 경로와 비밀번호를 채운다.
3. 빌드하고 제출 파일명으로 복사한다.
   ```bash
   ./gradlew :app:assembleRelease
   mkdir -p apk && cp app/build/outputs/apk/release/app-release.apk apk/autolog_AutoLogBusiness_release.apk
   ```

`keystore.properties`가 없으면 서명 없는 `app-release-unsigned.apk`가 만들어지며, 설치할 수 없다.
