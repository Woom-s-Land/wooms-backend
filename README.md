<div align="center">
<img src="./readme/layout/imgLogo.png" width=300px />
</div>

## 우리들의 추억 저장소, WOOMS

개방적인 SNS에 피로감을 느끼는 사람들을 위한 메타버스, WOOMS <br> 소수의 인원이
그룹을 구성해 방명록, 사연 남기기, 픽셀 변환 사진 저장, 편지 주고 받기 등 다양한
방식으로 추억을 저장하고 소통할 수 있는 공간입니다 😊 <br>

> 개발 기간: 2024.07.08 ~ 2024.08.16

## 목차

### - [주요 기능](#주요-기능)

### - [서비스 화면](#서비스-화면)

### - [기술 스택](#기술-스택)

### - [실시간 권한 철회](#실시간-권한-철회)

### - [팀원 구성](#팀원-구성)

### - [회고](#회고)

<br><br>

## 주요 기능

### 편지 작성

- 내가 가입한 그룹 내에 있는 사람들에게 편지를 작성할 수 있습니다.
- 편지 도착 예정일을 지정할 수 있습니다.(하루 뒤, 사흘 뒤, 일주일 뒤)

### 편지함

- 읽은 편지와 안 읽은 편지를 구분해, 안 읽은 편지를 먼저 띄웁니다.
- 편지가 오는 중인지 아닌지 알 수 있습니다.

### 사진관

- 그룹 내 사람들끼리 공유하는 사진관입니다.
- 사진을 픽셀 변환해 업로드 할 수 있습니다.
- 픽셀 변환의 정도와 색상 팔레트를 선택할 수 있습니다.
- 사진을 더이상 보여주기 싫다면 사진을 뒤집어놓을 수 있습니다.

### 사진 지도(채움)

- 업로드 한 사진의 메타데이터로 지도의 어느 위치에 해당하는지 확인해, 지도에
  있는 픽셀의 색을 채웁니다.
- 그룹 별로 방장이 색을 지정할 수 있으며, 같은 위치에 업로드 된 사진이 많을수록
  색이 진해집니다.

### 방명록

- 그룹 내 사람들끼리 공유하는 방명록입니다.
- 하루에 한 번 작성할 수 있습니다.

### 라디오

- 그룹 내 인원들끼리 공유합니다.
- 사연을 작성하면, AI가 실제 라디오 DJ처럼 스크립트를 작성해 읽어줍니다. (TTS)

<br><br>

## 서비스 화면

### 메인 페이지

![main](./readme/layout/image.png)

|                     로그인                     |                   회원가입                    |
| :--------------------------------------------: | :-------------------------------------------: |
| <img src="./readme/layout/login.png" width="300px" /> | <img src="./readme/layout/join.png" width="300px" /> |

### 개인 공간 페이지

![solo](./readme/map/solo.png)

|                       편지 작성                        |                        편지함                         |                       편지 읽기                        |
| :----------------------------------------------------: | :---------------------------------------------------: | :----------------------------------------------------: |
| <img src="./readme/feature/letter-write.png" width="200px" /> | <img src="./readme/feature/letter-read.png" width="200px" /> | <img src="./readme/feature/letter-read2.png" width="200px" /> |

### 그룹 공간 페이지

![group](./readme/map/introMap.png)

|                  사진 픽셀 변환                   |                      사진 지도                       |
| :-----------------------------------------------: | :--------------------------------------------------: |
| <img src="./readme/feature/photoImg.png" width="300px"/> | <img src="./readme/feature/photoMapImg.png" width="300px"/> |

|                       방명록                       |                      라디오                       |
| :------------------------------------------------: | :-----------------------------------------------: |
| <img src="./readme/feature/guestbook.png" width="300px"/> | <img src="./readme/feature/radioImg.png" width="300px"/> |

## 기술 스택

### FRONTEND

<img src="./readme/stack/front_stack.jpg" />

---

### BACKEND

<img src="./readme/stack/back_stack.jpg"/>

---

### INFRA

<img src="./readme/stack/infra_stack.jpg" />

---

### DATA

<img src="./readme/stack/data_stack.jpg" />

---

### TOOLS

<img src="./readme/stack/tool_stack.jpg" />

## 실시간 권한 철회

기존 `DELETE /api/wooms/{woomsId}/users` 경로에서 가입 상태가 `ACCEPT`에서 `REFUSE`로 바뀌고 DB transaction이 commit되면 `ROOM_ACCESS_REVOKED` Redis Pub/Sub 이벤트를 발행합니다. 이벤트에는 버전, event ID, 초대 코드 UUID, 사용자 UUID, 사유와 시각만 들어가며 쿠키·JWT는 포함하지 않습니다. 발행은 bounded 비동기 relay를 거치므로 Redis 장애가 이미 commit된 탈퇴 응답을 실패로 바꾸지 않습니다.

Pub/Sub은 영속 큐가 아니며 transactional outbox도 이번 구현에 포함하지 않았습니다. 이벤트가 유실되거나 Redis가 연결되지 않으면 Netty의 30–60초 분산 backend 재검증이 권한 철회를 보완합니다. 연결은 60초 cache 만료 전에도 재검증될 수 있으며, 일시적 backend 장애는 권한을 연장하지 않고 연결을 닫습니다. 실제 운영 동작은 backend와 Netty 양쪽 `WOOMS_REALTIME_ACCESS_EVENTS_ENABLED` 및 `WOOMS_REALTIME_ACCESS_CHANNEL` 값, Redis 접속 설정이 일치해야 합니다.

## 팀원 구성

| 송도언(팀장) | 김도예 | 윤대영 | 이현수 | 정 훈 |  홍성우   |
| :----------: | :----: | :----: | :----: | :---: | :-------: |
|     사진     |  사진  |  사진  |  사진  | 사진  |   사진    |
|      BE      |   FE   |   FE   |   BE   |  FE   | Infra, BE |

## 회고
