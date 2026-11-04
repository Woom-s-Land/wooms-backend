# Redis 권한 철회 이벤트 구현

## 승인·범위
사용자: 추상화 잘 해서 redis로 이벤트 전파방식으로 바꾸자. backend/socket 제품 변경 및 필요한 소규모 기능검증 승인. frontend는필요할때만. 부하/UI시험·운영DB·배포·commit/push 없음. 이전PLAN은 REALTIME-BENCHMARK-PLAN.md 보존. PLAN-2/기존변경/미추적자료 보존. Herdr builder 구현후 reviewer독립리뷰, 같은시험병행금지.

## 현재와 목표
NativeHub는최초접근검증+5초캐시/주기재검증+expiresAt종료. backend WoomsService.leaveWooms가ACCEPT->REFUSE경로. 가입승인변경은WAITING만가능하므로기존강퇴API있는것처럼새endpoint만들지않음. 전체실제철회경로확인후연결. 기존Redis starter양쪽존재, refresh token등무관기능변경금지.

## 구조
- 도메인 이벤트 ROOM_ACCESS_REVOKED와 전송 구현 분리. backend 이벤트 DTO/publisher port/AFTER_COMMIT relay/Redis adapter, socket handler port/Redis adapter. 도메인이RedisTemplate/채널에직접의존하지않음. JSON계약각repo소규모정의및공통fixture로호환성검증. 공통새repo/범용프레임워크없음.
- JSON schemaVersion=1,eventId UUID,type,roomId 초대코드UUID,userUuid UUID,reason,occurredAt. 비밀없음. configurable channel 기본 wooms.realtime.access.v1. 모든Netty인스턴스가방송수신. Redis설정env,비밀출력금지.
- 트랜잭션내도메인이벤트,성공커밋후발행. rollback/실제변경없음/권한거부시0. Redis발행실패가이미성공한DB작업을실패처럼응답하지않게로그/폴백. bounded전송/필요시비동기발행으로HTTP응답장기정체방지. PubSub유실가능,Outbox이번미구현명시.
- 수신시일치하는방+사용자세션송수신차단. 우선안전하고단순한전체WebSocket종료정책,기존close로presence/mafia/구독정리. 다른방재접속영향명시. 캐시삭제만으로끝내지않음.
- Redis callback→NettyeventLoop안전전달,도메인기존방큐규칙유지. 중복멱등,잘못된이벤트거부. 최초/재인증inflight및구독설치와철회경합후권한부활금지. 사용자미확정해당방inflight세션보수적종료가능. 새접속은backend재검사.
- 최초검증/expiresAt유지. 캐시/주기검증configurable60초기본. 세션별30~60초간격분산,최대정책주기초과금지. 수신전용도대상. 캐시만료시SEND재검증. 이벤트유실시주기+요청timeout만큼지연명시.
- backend401/403확정거부vs5xx/timeout일시장애구분. 두경우모두송수신차단후연결종료하되사유/로그/closecode구분. 복잡한재시도상태/큐이번제외. 검증실패권한무기한연장금지.
- Redis장애때저빈도backend재검증보완,backend장애는위정책. 시작/재구독실패동작명시,무한대기금지. 이벤트enabled설정가능하나기본활성,비활성시도재검증유지. Redis무관테스트context는명시적으로비활성.

## 변경파일
backend domain/realtime 이벤트/port/adapter, WoomsService 실제철회경로,설정/테스트. socket realtime handler/adapter/NativeHub/SocketProperties/session조회필요부분/설정/테스트. 양쪽README정책·실행·전달한계. NETTY-RESUME-NOTES완료사실구분갱신.

## 필요한 검증만
1 commit후발행/rollback0/실제철회만.
2 수신전용철회종료/cleanup/다른유저·방보존/중복·잘못된이벤트안전.
3 인증inflight·구독과철회경합.
4 설정캐시·분산상한·expiresAt·401403vs5xx/timeout안전종료.
5 가능하면격리Redis+memoryDB실제탈퇴→commit→Redis→Netty종료통합1회. 다른서비스조작/flushall금지. Redis없으면구체적보고. 부하/UI없음.
구현후reviewer독립리뷰. 불필요한리팩터링/새강퇴기능/미사용상태정리/그림저장큐분리는별도작업으로남김.