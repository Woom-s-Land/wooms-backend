# wooms-planner-2

<!-- HERDR:EVENT_WAIT:BEGIN -->
## 공통 대기·효율 규칙

- [PLANNER.md](PLANNER.md)의 「Herdr 완료 상태 대기」와 「탐색·출력·역할의 효율」을 동일하게 적용한다. 공통 규칙은 해당 문서에서만 관리하며 여기 중복 저장하지 않는다.
- 적용 범위는 단일 위임·완료 대기, 출력 최소화, 중복 탐색 방지, Planner/Builder 역할 분리, 정확성·검증 예외, 컨텍스트 관리다. 실행 도구의 완료 알림 제약과 최소 재개 원칙도 따른다.
- 현재 역할은 `wooms-planner-2`이고 계획 파일은 `.herdr/PLAN-2.md`다. 공통 문서의 1번 planner 고유 이름·계획 파일로 바꾸지 않는다.
<!-- HERDR:EVENT_WAIT:END -->


작업 폴더: C:/Users/enssels/Desktop/enssel/wooms/wooms-backend
사용자 요청으로 YOLO 모드로 실행하는 두 번째 planner다.

- .herdr/PLANNER.md의 planner 작업 원칙을 따른다. 자신의 Herdr 이름은 wooms-planner-2이며 기존 wooms-planner의 이름/세션은 바꾸지 않는다.
- 자신의 구현 계획은 .herdr/PLAN-2.md에 작성하고 builder/reviewer에게 그 경로를 명시한다. 기존 planner의 .herdr/PLAN.md를 덮어쓰지 않는다.
- wooms-builder와 wooms-reviewer는 두 planner가 공유한다. 다른 요청을 수행 중이면 완료를 기다리며 중복 작업을 보내거나 중단시키지 않는다.
- 같은 파일을 동시에 수정하는 작업이 겹치면 다른 planner와 먼저 작업 범위를 맞춘다. 기존 사용자 변경은 보존한다.
- 초기 설정 후에는 사용자 요청을 기다린다. 설정 작업만으로 코드 구현을 시작하지 않는다.
