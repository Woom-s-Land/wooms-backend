# Netty 독립 애플리케이션 마이그레이션

## 목표와 범위
- 사용자 요청: 조직의 기존 wooms-socket을 기반으로 별도 Netty 앱으로 실시간 기능을 이전하고, 기존 STOMP 앱을 보존해 나중에 비교한다.
- 작업 저장소: backend 현재 폴더, ../wooms-frontend, ../wooms-socket. socket은 origin/develop(227f924) 기반 feat/wooms-websocket-migration, backend/frontend는 feat/netty-transport-integration. commit/push/배포/운영 DB 변경 없음.
- 기존 STOMP 부하도구 및 결과 보존. 이전 계획은 .herdr/STOMP-BENCHMARK-PLAN.md에 보존했다. PLAN-2.md는 건드리지 않는다.

## 현재 동작
- socket 기존 구현은 Java22 Spring Boot 구성+Netty 줄단위 TCP. 브라우저 WebSocket, 수명주기, 인증 구현은 미완성. JWT claim은 backend와 불일치하고 하드코딩 토큰 코드가 있으므로 새 실행 경로에서 사용하지 않는다.
- backend는 채팅/이동, 공동그림/작품DB, 마피아 상태머신/개인role/타이머를 STOMP로 제공한다.
- frontend는 단일 client의 activate/deactivate/subscribe/publish/onConnect API를 사용. 지도 roomId는 초대 UUID, 그림/마피아 경로도 같은 UUID. 기존 그룹 상세 API는 숫자 ID이므로 구분해야 한다.

## 설계
- 기존 Netty bootstrap을 고쳐 독립 프로세스 ws://localhost:8090/ws 제공. STOMP wire가 아닌 JSON envelope: {op:SUBSCRIBE,id,destination}, {op:UNSUBSCRIBE,id}, {op:SEND,destination,body}. 서버 {op:CONNECTED}, {op:MESSAGE,subscription,destination,body}, {op:ERROR,message}. body는 기존 React가 기대하는 JSON문자열. 목적지 명명은 기존 경로와 같게 유지하여 adapter로 연결한다. wire에서 STOMP frame/decoder는 사용하지 않는다.
- 프론트 기본은 기존 stomp, VITE_SOCKET_TRANSPORT=netty 및 VITE_NETTY_WS_URL로 선택. netty adapter가 기존 client API를 구현한다. callbacks, unsubscribe, reconnect, heartbeat, deactivate후 재시작 race를 다룬다. 기본 선택으로 기존 화면 동작 보존.
- 네트워크 I/O eventloop에는 블로킹 DB/HTTP 작업 금지. backend HTTP는 HttpClient.sendAsync 또는 별도 bounded executor 사용. 같은 방 이벤트는 hash roomId로 고정된 직렬 실행기에서 처리, 적용/방송 순서 일치.
- 서버 수명주기는 비블로킹 bind 후 실제 채널 저장, 종료시 children/server/boss/worker/executors 정리. 기존 closeFuture().sync()로 ApplicationReady 막기 및 parent null 접근 제거.
- Origin allowlist(localhost5173,3000) 확인, 허용 frame 크기와 handshake/auth 시간/idle 제한 설정. 느린 소비자는 write buffer 임계에서 무한히 쌓지 말고 명확히 종료, 이후 스냅샷으로 복구. baseline에서는 임의 메시지drop 최적화 금지.

## 인증과 backend 경계
- 인증은 기존 backend의 Authorization 쿠키를 재사용. Netty는 handshake Cookie를 보관하되 비밀을 로그에 출력하지 않는다.
- 신규 backend GET /api/realtime/rooms/{roomId}/session: 기존 Spring Security 보호, CustomUserDetails 기반으로 WoomsRepository.findByInviteCode(UUID), EnrollmentRepository 조회하여 ACCEPT 여부 확인. 승인 사용자만 {roomId,userUuid,nickname,costume} 반환, 401/403/404/400을 명확히. 닉네임/의상은 DB의 현재 사용자 정보를 기준으로 확정. 요청body의 닉네임은 신뢰하지 않는다.
- Netty는 room subscribe/send 최초에 이 endpoint를 비동기로 호출해 세션별 room 권한/identity를 확정. 검증 완료 전 방 이벤트/개인정보 전송 금지. 권한 검사 대기 중 동일 세션 명령 순서 유지, 실패는 오류 후 차단. 매 연결 재검증; 타임아웃/fail closed.
- private queue subscription은 세션 자신에게만. 임의 목적지 방송 허용 금지, 알려진 room topic/command만 허용. 인증된 사용자/room 검증 없이 다른 방의 chat/move/sketch/mafia 접근 금지.
- 비교용 익명 모드는 명시적 local benchmark 옵션으로만 제공 가능; 기본 인증 필수. 익명 모드를 켜는 예시는 비교/테스트 한정이며 외부노출 금지.

## 이벤트 기능
1. 채팅: JSON DTO 처리, 서버 확정 nickname으로 broadcast. 내용 크기 제한.
2. 이동: 기존 x/y/direction/costume/stepId 형식, move 구독 시 room presence 추가 및 private /user/queue/init 스냅샷; 이동 방송/퇴장 이벤트. 늦게 연결한 client와 재연결/중복identity 처리 명시.
3. 그림: 기존 32x32/12색, 후입장 snapshot(/user/queue/sketch/init), draw 방송, archive/reset, gallery 호환.
4. 마피아: backend engine/entities/dto/topic 구현을 이식해 룰/점수/phase/시간 그대로 유지. SimpMessagingTemplate은 Netty outbound abstraction으로 바꾸고 개인 role/error 큐는 해당 session에만 전송. 방 실행기+타이머+disconnect 순서, game timer 정리/중복연결 주의.

## 그림 저장
- 기존 backend가 DB/갤러리를 소유. Netty가 DB를 직접 공유/수정하지 않는다.
- 추가 bridge endpoints(기존 security 보호+같은 membership 검증): GET wall -> {size,pixels}; PUT wall -> pixels 유효성 검증 후 기존 SketchWall upsert; POST archive -> 제출한 현재 pixels를 SketchMural에 저장하고 wall은 blank로 reset하는 트랜잭션. savedBy는 current DB nickname. 기존 GET /api/sketch/{roomId}/murals를 그대로 사용.
- Netty live canvas는 메모리, 최초 로드는 비동기, 변경 snapshot을 주기적으로 backend로 flush. 파일DB는 기존 것을 실행하지 않고 테스트는 H2메모리. archive 성공 전 reset 금지, 저장/후속draw/flush 순서 보장(방 작업을 직렬화하고 비동기작업 fence/version으로 정합성 유지), 실패시 dirty 유지+오류.
- STOMP와 Netty가 같은 room의 live wall을 동시에 운영하는 것은 지원하지 않음; 비교는 분리 실행/테스트room.

## 변경 파일
- backend: 신규 domain/realtime controller/service/DTO 및 의미 있는 membership/그림저장 테스트. 기존 STOMP 구현을 제거/교체하지 않는다.
- socket: 기존 Netty config/bootstrap/handler, 신규 native websocket transport/room registry/backend bridge/mafia 및 테스트/README/application 설정. gradle 필요한 JSON/JWT 의존성 등 최소 변경. JDK22 기존사용.
- frontend: libs/socket/client.js 선택로직 및 Netty adapter, .env.example/문서와 adapter lifecycle 테스트. 필요할 때만 구독/그림 gallery 오류처리 최소조정.
- 비교 실행은 후속 작업. 이번 목표는 기능 이전 및 검증이며 속도 우위 주장하지 않는다.

## 검증과 완료조건
- backend 신규 endpoint에 승인/대기/거절/타그룹/미인증 및 pixels경계/아카이브transaction 동작 테스트.
- Netty 실제 WebSocket smoke: 2방격리, chat/move init/disconnect, 후입장 그림 snapshot/저장실패시 보존, 잘못된 메시지/잘못된Origin/권한 없는 방 차단.
- 마피아 최소3클라이언트 전체라운드, private role 누출없음, disconnect/host succession, timer 종료.
- frontend 기본STOMP+Netty adapter lifecycle/subscribe복원 테스트 및 npm run build.
- 실제 backend+socket+headless WebSocket E2E는 로그인 쿠키를 받아 승인그룹에서 수행하고 테스트프로세스/DB만 사용한다.
- builder 단계별 완료/구체적 장애 후 다음 단계 진행. planner가 동일 구현/테스트 병행하지 않는다. 마지막 reviewer 독립 리뷰, 필요한 수정만 builder에게 재위임.

## 리뷰 후 인증/저장 보완
- 마피아 참가 소유권은 nickname이 아니라 DB user UUID로 확인한다. 같은 nickname의 타 UUID가 기존 역할을 재발급받지 못하게 한다.
- session 응답에 현재 access token expiresAt를 추가한다. Netty는 만료 시각에 연결을 종료하고 방 권한을 최대 5초 간격으로 재검증한다(변경 전파 지연 최대5초). 실패는 방 동작을 허용하지 않고 연결을 닫는다.
- 백그라운드 그림 flush는 사용자 cookie 만료와 분리한다. Netty/backend에만 공유하는 최소32자 WOOMS_REALTIME_INTERNAL_SECRET을 명시 설정하고 /api/auth/realtime/rooms/{roomId}/wall PUT 전용 endpoint에 X-Wooms-Realtime-Key 헤더로 인증한다. 사용자 JWT 공유 비밀과 다른 서비스 자격이며 frontend에 주입하지 않는다. 기본 비어있는 값은 거부한다.
- internal wall endpoint는 상수시간 key비교, UUID/실제room존재/pixels검증을 수행한다. 일반 사용자 GET/PUT/archive는 기존 membership 검증을 유지한다. archive는 사용자 인증으로 실행한다.
- 만료/권한취소 전 승인한 dirty 그림은 서비스 자격으로 저장할 수 있으나, 무효가 확인된 세션의 새 명령은 수락하지 않는다. 테스트는 무작위 임시 서비스 secret을 두 테스트 JVM에만 전달하고 출력하지 않는다.
- backend권한TTL은5초이므로 즉시 강퇴를 의미하지 않는다. 이 제한은 실행문서에 명시한다.
- 수신만 하는 연결도 최초 승인 이후 세션별5초주기권한재검증을 실행한다. 취소/만료 시 구독 정리와 연결 종료. 실제 전파 지연은 주기+backend 응답시간이다.
- 그림판/마피아 화면은 연결 종료 시 연결 상태를 갱신하고, 그림판은 offline 상태에서 로컬 그림 입력도 수락하지 않는다.
