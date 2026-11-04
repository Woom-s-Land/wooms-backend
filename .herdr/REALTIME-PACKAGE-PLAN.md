# Netty realtime 전체 패키지 정리

사용자: REALtime에 정리안된친구들이 너무 많다. 직전access만정리한범위를확장한다. socket realtime루트의모든남은실행코드와mafia파일경로를역할에맞게배치한다. 기능추출/정책/이름/프로토콜/config값은바꾸지않는다. 기존수정/미추적보존. backend/frontend제품변경없음. commit/push/부하/UI/Redis실행없음. 공유담당idle직전완료확인.

## 구조
com.ee06.mychat.realtime 아래:
- config.SocketProperties
- websocket.RealtimeServer, NativeHub (WebSocket수신과명령라우팅)
- session.SocketSession, OutboundBus (연결/구독/출력)
- room.RoomQueue (방별실행순서)
- presence.PresenceService (채팅/캐릭터상태)
- sketch.SketchService
- backend.BackendBridge (backend HTTP연동)
- access 기존event/port/redis 유지
- mafia 기존하위구조유지. 현재mafia루트5개model의package선언은이미mafia.entity이므로실제파일도entity로옮겨선언/경로일치시킨다.
realtime루트에일반클래스를남기지않는다. 빈디렉터리제거필수아님. 레거시com.ee06.mychat.netty/global/domain은스캔제외코드이므로이번에건드리지않는다.

## 실행
Move-Item으로옮기고package/import/FQCN/테스트package/문서코드경로/설정참조를일관되게갱신. 같은package암묵접근의존관계주의. 테스트도대상역할패키지로정리하고하나의테스트를위해불필요한public확대금지. SpringscanBasePackages=com.ee06.mychat.realtime유지.
루트tools의참조도검색하되실제경로참조만수정,과거기록은당시경로라고명시가능. products로직변경없음. 새테스트불필요.

## 검증
socket전체22tests,context스캔및app실행설정기존테스트로확인. package선언과파일경로일치자동점검,이전FQCN잔존검색,diffcheck. builder완료후reviewer이번파일이동범위만정적리뷰. 요약은새tree/테스트결과짧게.