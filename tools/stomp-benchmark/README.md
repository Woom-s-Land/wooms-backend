# WOOMS STOMP 로컬 부하 측정

## 2026-11-02: 1,000연결 / 방당 10명

100개 방, 전체 목표 입력 1,000 msg/s(연결당 평균 1 msg/s), 구독자 자신을 포함한 방당 10회 전달.
실제 `/ws/send/chat/{UUID}` → controller의 JSON 파싱/직렬화 → simple broker → `/ws/wooms/chat/{UUID}` 경로를 측정했다.
Node 22 내장 WebSocket을 이용한다. 현재 endpoint가 허용하는 익명 연결이며 Origin 헤더를 보내지 않는다.

| 항목 | 1회 | 2회 |
|---|---:|---:|
| 구독 준비 완료 / 종료 전 연결 | 1,000 / 1,000 | 1,000 / 1,000 |
| 실제 입력 msg/s | 1,000.333 | 1,000.533 |
| 측정 구간 전달 deliveries/s | 10,002.667 | 10,005.333 |
| 측정 메시지 수 | 15,005 | 15,008 |
| 예상 전달 수 | 150,050 | 150,080 |
| 측정 구간 내 고유 전달 수 | 150,040 | 150,080 |
| 추가 수신 대기 종료 후 고유 전달 수 | 150,050 | 150,080 |
| p50 지연 ms | 2.241 | 2.218 |
| p95 지연 ms | 4.289 | 4.279 |
| p99 지연 ms | 5.797 | 5.780 |
| 최종 미수신 / 중복 / 오류 / 연결 종료 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| 생성기 예약 송신 지연 p99 ms | 13.448 | 13.411 |
| 생성기 송신 버퍼 최대 bytes/연결 | 258 | 258 |

각 실행은 모든 연결의 고유 probe 왕복 확인 후 워밍업 5초, 측정 15초, 추가 수신 대기 10초를 사용했다.
입력률은 측정 구간에 `WebSocket.send`가 성공한 수/15초이다. 서버 내부 처리 카운터는 아니다.
전달률은 측정 구간에 송신한 메시지 중 같은 구간 내 도착한 고유 전달 수/15초이다.
지연 분위수는 측정 메시지의 실제 송신 직전부터 클라이언트 수신 처리까지이며 추가 대기 중 수신도 포함한다.
예약 송신 지연은 별도 지표로, 위 지연에 포함되지 않는다. 입력 메시지 수가 목표보다 소폭 많은 것은 워밍업 경계의 지연된 송신이 측정 구간에 들어오기 때문이다.

## 환경 및 제한

- Windows 11 Pro 10.0.26200, Intel Core Ultra 5 125H(14코어/18 논리 프로세서), 가용 시스템 메모리 약 15.5 GiB.
- Java Temurin 22.0.2 / Spring Boot 3.3.2, Node 22.20.0. 서버와 생성기는 같은 PC의 별도 프로세스.
- 제품 코드와 기존 설정 파일을 변경하지 않았다. 기존 dev 로그 수준을 유지하므로 메시지별 INFO 및 Spring Web DEBUG 로그가 포함된다.
- 별도 포트 18080, H2 메모리 DB. 기존 `data/devdb`는 사용하지 않았다.
- 로그인/JWT 부하, 이동/그림/마피아의 로직, 브라우저 렌더링, 인터넷 지연, 장시간 안정성, 다중 서버는 측정하지 않았다.
- CPU/힙 사용량은 수집하지 않았다. 이 수치는 측정 조건에서 확인한 기준선이며 STOMP 최대 처리량이 아니다.
- 이전 Python 결과는 `results/multi-*.json` 및 `fanout-50-500.json`에 보존했다. Python 단일 방 50명 결과는 생성기 목표율 미달/송신 지연이 있어 서버 한계로 해석할 수 없다. 이번 결과와 도구가 다르므로 직접 성능 개선으로 비교하지 않는다.

## 재현

저장소 루트에서 Java 22로 빌드한 뒤 독립 서버를 실행한다. PowerShell 예:

```powershell
$env:JAVA_HOME = 'C:\Users\enssels\.jdks\temurin-22.0.2'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat bootJar --console=plain
& "$env:JAVA_HOME\bin\java.exe" -jar build/libs/wooms-0.0.1-SNAPSHOT.jar '--server.port=18080' '--spring.datasource.url=jdbc:h2:mem:stompbench;DB_CLOSE_DELAY=-1' '--spring.jpa.hibernate.ddl-auto=create-drop'
```

서버 시작 후 다른 터미널에서:

```powershell
node tools/stomp-benchmark/bench.mjs --url ws://127.0.0.1:18080/ws --connections 1000 --room-size 10 --rate 1000 --warmup 5 --duration 15 --drain 10 --connect-timeout 30 --output tools/stomp-benchmark/results/node-1000-room10-rate1000-run1.json
```

`results/node-1000-room10-rate1000-run1.json`, `run2.json`에 각각 원본 요약 결과가 있다. Node 생성기는 추가 패키지 없이 실행할 수 있다.
Python 도구를 별도로 재실행하려면 `websocket-client`가 필요하다. 현재 권장 측정 도구는 검증한 Node 생성기다.
