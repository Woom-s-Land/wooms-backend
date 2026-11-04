# 권한 철회 이벤트 패키지 정리

사용자 승인: 패키지 정리좀해봐. 직전Redis구현의동작/설정/JSON계약/정책유지. 기존변경보존, frontend변경없음,commit/push/운영DB/부하/UI없음. 공유builder/reviewer idle확인.

## 목표 구조
backend: com.ee06.wooms.domain.realtime.access
- event.RoomAccessRevoked
- port.RoomAccessRevocationPublisher
- RoomAccessRevocationEventAdapter (Spring application event bridge)
- redis.RedisRoomAccessRevocationRelay, RealtimeAccessProperties, RealtimeAccessRevocationConfiguration
socket: com.ee06.mychat.realtime.access
- event.RoomAccessRevoked
- port.RoomAccessRevocationHandler
- redis.RoomAccessRevocationListener, RoomAccessRevocationRedisConfiguration

현재backend wo oms adapter/event/port 및globalconfig에산재한권한철회파일을위기능패키지로모은다. socket realtime루트의권한철회파일도동일하게묶는다. 기존NativeHub/RoomQueue/SketchService/PresenceService/서버/mafia구조는이번범위밖,이름/로직리팩터링없음. 새추상화/계층추가금지.

## 처리
Move-Item으로파일이동,package/import/테스트package/참조/스캔또는설정클래스명참조/문서경로만갱신. public/packageprivate가시성은이동에필요한최소만. 같은이벤트계약/환경변수/기본값그대로. application scan범위지속확인. 새파일은이미미추적이므로소실금지.
테스트도해당기능package에맞추되기존NativeHub통합테스트는그대로import만. 기존emptydir삭제는필수아님. tools벤치마크와PLAN-2보존.

## 검증
backend관련5개+socket전체테스트 실행(기존22개). gitdiffcheck및이전package참조잔존확인. 새테스트추가필요없음. builder구현후reviewer짧은독립정적리뷰. actualRedis없음은기존제약으로재시도안함. 구조와검증결과짧게보고.