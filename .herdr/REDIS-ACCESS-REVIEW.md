# Redis 권한 철회 구현 검증

2026-11-03. backend/socket 미커밋 변경 기준.

- 최초 독립 리뷰에서 실제 outbound write·진행 중 SEND·비동기 그림 로드·재검증 실패의 차단 경합 지적.
- 보완: 세션 차단과 동기 상태 변경을 같은 monitor로 직렬화. 수신자 event loop에서 실제 쓰기 전 차단/구독 재확인. 그림 로드 후 변경·보관 요청 시작 전 재확인. 인증 실패는 future 실패 전 차단. 종료 정리는 closeStarted로 멱등 처리.
- 이미 시작한 보관 HTTP가 성공하면 캔버스 초기화를 완료해 저장 상태와 메모리를 일치시킴. 철회 전에 이미 enqueue된 프레임은 회수하지 못함.
- 최종 wooms-reviewer 정적 재리뷰 APPROVED. 재리뷰는 기존 세 P1 수정 범위이며 테스트 재실행은 하지 않음.
- 실행 검증: backend 관련 5 tests, socket 전체 22 tests, failures/errors 0. diff --check 통과.
- backend 테스트 명령: `./gradlew.bat test --tests '*RoomAccess*' --tests '*WoomsServiceLeaveWoomsTest' --console=plain`.
- socket 테스트 명령: `./gradlew.bat test --console=plain`. Java 22 사용.
- Redis 실제 전달은 미검증. Redis 실행 파일·리스너가 없고 Docker daemon을 사용할 수 없었음. 발행 측 Redis mock과 수신 측 실제 JSON 콜백 검증이며, 실제 Redis+backend+Netty 종단 간 검사로 표현하지 않음.
- commit 후 발행 실패/프로세스 종료/구독 단절은 Pub/Sub 유실 가능. Outbox 없음. 30~60초 분산 재검증으로 보완하며 주기+요청시간의 철회 지연 가능.
- 기존 탈퇴 ACCEPT→REFUSE에 연결. 기존 강퇴 API가 없어 새 강퇴 기능은 만들지 않았음. 이후 강퇴 경로도 동일 publisher port를 호출하도록 연결해야 함.
- 구현 중 builder quota 차단 후 planner가 잔여 수정·검증 수행. 부하/UI/운영DB 변경 및 commit/push 없음.
