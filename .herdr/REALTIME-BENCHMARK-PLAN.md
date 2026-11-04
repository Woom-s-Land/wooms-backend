# STOMP / Netty 기능 확인 및 처리율 비교

## 사용자 요청과 범위
- 승인한 다음 작업을 실제 수행한다: 브라우저 기능 점검, 인증/로그/메시지 조건 정리, 1000 연결·방당10명에서 입력1000/5000/10000 msg/s 비교. 채팅·이동·그림을 구분한다.
- 기존 제품코드와 설정은 측정 때문에 튜닝/변경하지 않는다. 테스트 전용 fixture/launcher/생성기/결과 문서만 작성한다. 버그가 발견되면 근거를 planner에게 보고하고 범위를 판단한다.
- 기존3저장소는 clean, 이전작업 push 완료. 자동 commit/push/배포/운영DB변경 없음. 기존 결과 보존. .herdr/PLAN-2.md 보존. 기존 migration 계획은 NETTY-MIGRATION-PLAN.md에 보존.

## 도구 및 실행 환경
- Java22 C:/Users/enssels/.jdks/temurin-22.0.2, Node22, Windows11/18논리CPU. backend/socket 별도JVM, 생성기 별도Node프로세스.
- 기본8080/8090/5173은 현재리스너없음 확인. UI용은이포트, 성능도같은포트를사용하되모드별재시작/메모리DB재생성가능. 기존사용중포트/다른프로젝트프로세스건드리지않는다.
- 모든서버는 jdbc:h2:mem:realtimecompare;DB_CLOSE_DELAY=-1, ddl-auto=create-drop. 파일DB사용금지. test process는Hidden,로그TEMP,명령행/PID기록,종료는본인것만확인후.
- 서비스key는매run랜덤생성해양쪽JVM환경에만전달. key/JWT/cookie/password는출력/저장소추가금지. 인증fixture JSON은TEMP,결과JSON엔비밀제외.
- 브라우저접근host unavailable이므로 mcp__cua_repl의Windows Chrome UI fallback가능. computer-use skill/guidance/confirmations 읽고관측기반UI만. 외부사이트/사용자기존탭내용을검증대상으로삼지않는다.

## 테스트 데이터/인증
- 제품DevDataInitializer는수정하지말고 test-scope main launcher로추가fixture생성. 100개실제Wooms UUID방,방당10명으로1000개고유User+ACCEPT Enrollment를메모리DB에만생성.
- 기존seed admin5명/기본방도보존해브라우저로그인에사용. fixtureuser는고유nickname,costume,실제userUUID,서버JWTUtil.generateAccessToken의signed access cookie,channel-uuid는자기방으로.
- 로그인처리량이아닌연결이후steady-state측정. 직접발급한JWT는실제backend검증과Netty그룹검증을통과해야함. cookie를넣을수있는ws 라이브러리를전용tools package에서사용(설치된primary dependency버전확인,필요시공식패키지만).
- 두프로토콜모두같은실제JWT인증을사용. 기존STOMP는연결JWT검증만하고Netty는추가DB그룹권한/5초재검증을수행하므로순수I/O엔진만의동일기능비교라고주장하지않는다. 이추가비용도Netty쪽전체backendCPU/메모리에포함한다.
- testmain/Gradle init script로실행하며제품build.gradle을바꿀필요없음. JavaArgfile로긴classpath전달가능. 대량계정/토큰파일은test fixture만,외부/기존DB사용금지.

## 생성기
- tools/realtime-benchmark/ 아래재현도구. STOMP/Native 두adapter가같은pacing/수신집계/monotonic clock을공유.
- 인증된1000연결,room별정확10구독,100rooms. 연결별준비probe(자기메시지왕복)를확인후warmup5초+measure20초+drain10초. heartbeat10초.
- workload별chat(JSON content에runId/publisher/seq/sentAt),move(동일coords/direction/stepId,고유ID는stepId인코딩으로추적),sketch(PEN x/y/color/nickname;프로토콜확장필드무시를이용하지말고실제응답에서매칭가능한sequence를설계,cell/color표현의한계시publisher별한번에하나의inflight등측정제약명시). 수신에서실제payload형식차이확인.
- chat/move/sketch각각target total ingress1000/5000/10000 msg/s. 초기chat smoke10연결부터. 모드별/부하별한번씩최소18run,안정상한근처조건은가능하면재현1회. 순서는교대/역순으로warmup편향줄이기.
- 입력은successfulsocket.send,출력은수신delivery로구분. cohort송신수,예상fanout,측정구간고유수신,drain후고유/미수신/중복/순서역전/오류/연결종료집계. 메시지0..measurement-end경계정의정확히,준비/워밍업제외.
- 고유ID를반환하지않는그림이벤트는p99정확측정이불가능하면추정값으로위장하지않고전달계수/RTT probe/구체적인상관방법의한계를명시한다. 필요하면그림은별도batch/Little-law가아닌정확한1outstanding대조실험으로명확히구분한다.
- 실제달성송신률/pacing lag p95p99max,event-loop lag,bufferedAmount,송수신앱bytes,sample payload bytes를측정해생성기포화와프로토콜오버헤드를구분. 최대부하에서node프로세스한계면서버한계단정금지.

## 리소스/공정성
- 로그는양쪽root/Wooms채팅/웹DEBUG를WARN으로CLI override,지속실행때같은로그설정기록. 로그줄이기코드변경금지.
- CPU/메모리:1초간격으로서버각PID및생성기CPU초delta/working set/private bytes,가능하면GC/heap 지표수집. host전체CPU도수집해다른프로젝트작업경합기록.
- Netty는backend+socket 합계비용을보고. Java총최대heap1536MiB 맞추기:STOMP backend-Xmx1536m,Netty backend-Xmx1024m +socket-Xmx512m. Xms/worker/코어수도보고하며총heap과프로세스수가같은의미는아님명시.
- rate상승때비정상CPU/메모리/연결급증/생성기버퍼과다(예64MiB)면안전상한으로종료하고그결과도실패/포화로남긴다.
- 그림주기DB저장은STOMP10초,Netty5초+HTTP등현재차이유지/명시. 따라서앱전체비교이고순수Netty속도우위로과장하지않는다.

## 브라우저 QA
- builder가컴퓨터사용skill로테스트용Chrome새탭에서실행가능한Netty frontend로그인,그룹이동,채팅,그림그리기/저장/갤러리,마피아대기방/가능한3인라운드/재접속을확인.
- UI자동화는등록된CUA만사용,브라우저host불가면WindowsChrome관측기반사용. 다른프로젝트Chrome창을조작하지않는다. 바인딩하려는window정확선택,테스트탭만정리.
- 브라우저접근/다중세션제약으로일부UI단계를완료못하면누락을정확히보고하고이미검증된headless E2E와구분한다. 성능측정중UI/추가부하종료.

## 산출물/검증
- test launcher / 생성기 / 런명령 / secret제외fixture메타데이터 / run JSON / CPU메모리요약 / 비교표와해석 / 브라우저QA기록.
- 실제실행값만보고하고Netty이득없거나나빠도그대로결론. 미수신은drain초과를포함해정확히용어구분.
- builder단계별완료후reviewer가생성기집계와결과해석독립검토. planner는동일구현/테스트병행하지않음.

## 생성기 상관키 구체화
- chat: content 안 runId/publisher/seq와 실제송신시각을넣고두서버가돌려준같은content로상관.
- move: nickname+좌표문자열+direction+stepId 조합을key로. sequence는publisher별x=100+seq(예정최대250정도),y=200,dir=seq%4,stepId=seq%4로표현해animation step범위도유지. 같은run/닉네임에서좌표반복없음확인.
- sketch: nickname+x+y+colorIndex+tool이key. publisher별seq를 x=seq%32,y=floor(seq/32)%32,colorIndex=floor(seq/1024)%12에인코딩하면12288개서로다른응답을표현가능. 본시나리오최대publisher당250개수준이라wrap없음. 준비probe는예약tuple(31,31,11)을사용하고본측정에서예약tuple/wrap을사용하면중단. 매메시지의고유key와송신시각은생성기map에등록해정확히상관하며추가DTO필드/서버변경불필요.
- payload는양쪽에서nickname서버확정값과같게보내고수신nickname검증. 본문bytes가동일한지기록. 예상fanout은구독정원10을고정값으로믿지말고실제연결/구독준비확인수로계산.
- 생성기 한 프로세스 포화를 줄이기 위해 기본4개Node worker를권장한다. 각worker에완전한방25개/연결250개씩배정하면같은방publisher/subscriber의clock은같은프로세스라정확상관가능하다. coordinator가모든worker준비후동일wall-clock시작시각을전달해측정구간정렬. 프로토콜별같은worker수사용. workerCPU/메모리합계/각pacinglag기록. 분위수는worker분위수평균금지;전체latency샘플합치기또는정확/명시적히스토그램집계. 큰raw샘플은TEMP에만보관하고추적결과는요약만.
