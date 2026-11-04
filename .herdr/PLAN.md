# 레거시 제거 및 권한 철회 경합 수정

## 사용자 요청
레거시 domain/global/netty 제거. 제공리뷰의우선문제(인증중다른사용자의강퇴이벤트가해당방인증중모든세션을종료)를수정하고실제Redis+Backend+Netty통합검사추가. NativeHub책임분리/패키지추가분할/그림저장프로토콜전환은이번범위밖. backend/socket현재clean확인. 기존브랜치그대로,사용자변경보존. 새commit/push는요청없으므로하지않음.

## 레거시
socket src/main/java/com/ee06/mychat/{domain,global,netty}만확인후삭제. 삭제대상resolve경로가위세디렉터리인지검사,다른repo의동명패키지보존. 현재realtime와tests에서의존하지않음확인. 레거시netty.*설정/사용안하는JWT·Servlet·Security·Validation의존성은실제참조확인후필요없으면제거. spring-web은HTTP header/status사용하므로임의제거금지. Redis/Lombok/Jackson/Netty/Boot필요의존성유지. application-auth.properties 등ignored비밀파일읽기/변경금지. README'기존TCP보존'문구현행화,과거분석은역사기록으로유지.

## 인증중철회
NativeHub의identityFuture미완료/실패면affected=true현재정책제거. authenticateduserUUID가확정됐다면그사용자만철회,다른사용자보존. 최초인증중사용자미확정이면event의대상userUUID를해당검증시도에연결해기록하고응답의trustedidentity와대조한뒤일치할때만차단. 이벤트시점엔구독미설치상태유지. matchingevent후stale인증응답이구독을살리면안됨.
세션monitor아래서이벤트대조/최초신원확정/validationfuture완료순서를원자적으로맞춘다. 검사후lock해제→future.complete사이경합도주의. 동일연결다른방인증도같은사용자인지검증. 대기이벤트는진행중검증에만묶어종료/완료시정리,무제한이력캐시새로만들지않음. 수신전용기존인증세션즉시차단,중복멱등/30~60재검증/expiresAt/오류코드유지.

## 필요한 검증
- 같은방에서A가최초인증대기중B강퇴→A정상응답시구독/연결유지,B만종료.
- A인증중A철회→늦은정상응답와도구독설치0/차단.
- 인증완료/future완료경계와재검증중타인철회도안전,기존수신전용과중복검증유지.
- socket전체기존tests+필요회귀테스트,clean컴파일/bootJar로삭제의존성검증. backend는실제변경/통합에필요한범위만.
- 실제Redis+backend memoryDB+Netty작은통합test추가. 현재Redis/Docker가용성확인. Docker있고daemon안켜졌으면설치된DockerDesktop을hidden으로시작해시험가능(다른프로젝트중지/설정변경금지). 가용없으면무작정설치나장시간환경조사하지말고실행요건있는재현가능통합test와미실행사유구체적보고. 운영DB/flushall/임의사용중서비스조작금지. 시험리소스/PID/컨테이너만정리.
- 부하/UI시험금지. 토큰/암호/서비스키로그금지,필요한소스범위만읽고출력요약.
builder구현/검증완료후reviewer독립리뷰,planner동일시험병행금지. 문서/이력서근거도완료/미실행구분하여갱신.
## 완료 — 2026-11-04
- legacy domain/global/netty 소스와 불필요 의존성·설정 제거 완료.
- 최초 인증/재인증과 권한 철회의 경합 수정. 인증된 사용자 UUID를 세션에 유지하고 방별 진행 중 인증에 철회 대상을 기록한다. 다른 방의 이벤트가 동일 사용자 연결이나 미확정 인증을 종료하지 않도록 방 범위를 확인한다.
- Builder: 최종 clean test bootJar 성공, 테스트 27개/실패 0, diff --check 통과. 빈 legacy 디렉터리 정리.
- 실제 Redis+Backend+Netty 통합은 이전 실행에서 성공. 최종 방 범위 수정 후에는 재실행하지 않았으며 회귀 테스트로 검증했다.
- Reviewer 최종 APPROVED. 테스트 재실행 없이 변경 구간과 기존 검증 결과를 독립 검토했다.
- commit/push 미실행. Pub/Sub 유실 가능성 및 저빈도 재검증 보완 정책은 유지한다.
