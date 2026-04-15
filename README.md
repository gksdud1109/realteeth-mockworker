# Mock Worker 연동 서비스

이미지 처리 요청을 받아 외부 Mock Worker에 위임하고, 비동기 작업의 상태를 추적·관리하는 서비스입니다.

---

## 실행 방법

### 요구사항

- Docker, Docker Compose

### 빠른 시작

```bash
docker-compose up --build
```

서비스가 정상 기동되면:

| 항목 | 주소 |
|------|------|
| API 서버 | http://localhost:8080 |
| Swagger UI | http://localhost:8080/docs |
| OpenAPI JSON | http://localhost:8080/api-docs |

### 환경 변수 (선택)

| 변수명 | 기본값 | 설명 |
|--------|--------|------|
| `MOCK_WORKER_CANDIDATE_NAME` | `candidate` | API 키 발급 시 사용할 이름 |
| `MOCK_WORKER_EMAIL` | `candidate@example.com` | API 키 발급 시 사용할 이메일 |
| `MOCK_WORKER_API_KEY` | *(자동 발급)* | 미설정 시 애플리케이션 시작 시 자동 발급 |
| `SCHEDULING_ENABLED` | `true` | `false`로 설정하면 이 인스턴스의 스케줄러 비활성화 |

### 로컬 개발 (IDE)

MySQL을 먼저 실행한 후 애플리케이션을 시작합니다.

```bash
docker-compose up mysql
./gradlew bootRun
```

---

## 주요 API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/v1/jobs` | 이미지 처리 작업 접수 |
| `GET` | `/api/v1/jobs/{id}` | 작업 단건 조회 |
| `GET` | `/api/v1/jobs` | 작업 목록 조회 (페이지네이션) |

### 작업 접수 예시

```bash
curl -X POST http://localhost:8080/api/v1/jobs \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: my-unique-key-001" \
  -d '{"imageUrl": "https://example.com/tooth.png"}'
```

응답 (202 Accepted):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PENDING",
  "imageUrl": "https://example.com/tooth.png",
  "result": null,
  "failureReason": null,
  "attemptCount": 0,
  "createdAt": "2025-01-01T00:00:00Z",
  "updatedAt": "2025-01-01T00:00:00Z"
}
```

---

## 설계 설명

### 상태 모델

작업은 4가지 상태를 가집니다.

```
PENDING → IN_PROGRESS → COMPLETED
                      ↘ FAILED
PENDING → FAILED
```

| 상태 | 의미 |
|------|------|
| `PENDING` | 작업이 접수되어 Mock Worker 제출을 기다리는 중 |
| `IN_PROGRESS` | Mock Worker에 제출 완료, 결과 폴링 중 |
| `COMPLETED` | 처리 완료, `result` 필드에 결과 포함 |
| `FAILED` | 영구 실패 또는 최대 재시도 초과, `failureReason` 필드에 원인 포함 |

`COMPLETED`와 `FAILED`는 터미널 상태로, 이후 추가 전이가 허용되지 않습니다.  
상태 전이 규칙은 `JobStatus.canTransitionTo()`에 캡슐화되어 서비스 전반에 분산되지 않습니다.

### 실패 처리 전략

외부 시스템(Mock Worker)의 장애를 크게 두 종류로 분류합니다.

| 종류 | 예시 | 처리 방식 |
|------|------|-----------|
| 일시적(transient) | 5xx, 429, 네트워크 오류, 타임아웃 | 지수 백오프 후 재시도 |
| 영구적(permanent) | 4xx(429 제외), 응답 파싱 실패 | 즉시 FAILED 처리 |

**제출(submit) 재시도**: 최대 `mock-worker.submit.max-attempts`(기본 5회)까지 재시도.  
**폴링(poll) 재시도**: 횟수 제한 없이, `mock-worker.poll.deadline`(기본 10분) 초과 시 FAILED.  
**지수 백오프**: 초기 대기 시간 × 2^n, 상한값(ceiling)으로 제한.

트랜잭션 경계와 외부 HTTP 호출은 분리됩니다.  
DB 커넥션을 점유한 채로 외부 API 응답을 기다리지 않습니다.

### 동시 요청 처리

**멱등성 키(Idempotency-Key) 헤더**로 중복 요청을 처리합니다.

- 동일 키 + 동일 페이로드 → 기존 작업을 그대로 반환 (멱등)
- 동일 키 + 다른 페이로드 → 409 Conflict
- 키 미전달 → 페이로드 해시로 자동 생성 (`auto-{sha256(imageUrl)}`)
  - 같은 URL을 두 번 요청하면 동일 작업이 반환됨
  - 다른 작업으로 처리하려면 클라이언트가 명시적 키를 전달해야 함

중복 방지는 두 계층으로 보장됩니다.

1. **애플리케이션 계층**: `findByClientRequestKey` 선조회 후 기존 작업 반환
2. **DB 계층**: `client_request_key` 유니크 제약으로 동시 삽입 시에도 단 하나만 성공

Race condition 대응: 동시 삽입 시 발생하는 `DataIntegrityViolationException`을 catch하여 승자 행을 읽어 반환합니다.

**스케줄러 동시성**은 `@Version` 낙관적 락으로 보호합니다.  
동일 작업을 두 인스턴스가 동시에 처리하려 할 때 하나는 `ObjectOptimisticLockingFailureException`으로 튕기고 다음 틱에서 재처리됩니다.

멀티 인스턴스 환경에서의 중복 스케줄러 실행은 **ShedLock**으로 방지합니다.  
각 스케줄러 작업(`job-submitter`, `job-poller`)에 분산 락을 적용해 동시에 단 하나의 인스턴스에서만 실행됩니다.

### 트래픽 증가 시 병목 지점

| 지점 | 원인 | 대응 방향 |
|------|------|-----------|
| 스케줄러 처리량 | 단일 실행 인스턴스에서 배치(10~20건)씩 처리 | 배치 크기 확대 또는 병렬 처리 |
| Mock Worker API 속도 | 외부 시스템 응답 속도에 종속 | 폴링 간격 및 재시도 파라미터 조정 |
| DB 커넥션 | 동시 처리 작업 수에 비례 | 커넥션 풀 크기 조정 |
| `PENDING` 작업 누적 | Mock Worker 장애 지속 시 | 서킷 브레이커 도입 검토 |

현재 스케줄러는 `fixedDelay` 기반으로, 이전 실행이 끝난 후 딜레이를 두고 재실행합니다.  
처리량이 부족하면 배치 크기(`BATCH_SIZE`) 상향 또는 스케줄러 주기 단축으로 조정할 수 있습니다.

### 외부 시스템 연동 방식

**폴링(Polling) 방식**을 선택했습니다.

Mock Worker는 처리 완료 시 콜백(Webhook)을 제공하지 않습니다.  
따라서 주기적으로 상태를 조회하는 폴링 방식으로 결과를 수집합니다.

| 항목 | 구현 |
|------|------|
| HTTP 클라이언트 | Spring `RestClient` (동기) |
| 타임아웃 | `connect-timeout: 2s`, `read-timeout: 5s` |
| API 키 관리 | 최초 호출 시 자동 발급 후 캐시, 401 응답 시 자동 갱신 |
| 이미지 전달 방식 | URL 참조 방식 (`imageUrl` 문자열) |

이미지 데이터를 직접 전송하는 대신 URL을 전달하는 방식을 선택한 이유:
- 대용량 바이너리 전송에 따른 서버 메모리·네트워크 부담을 제거
- Mock Worker API 스펙이 URL 방식을 전제하고 있음

---

## 추가 요구사항 설명

### 4.1 중복 요청 처리

위 "동시 요청 처리" 섹션 참조.  
`Idempotency-Key` 헤더 + DB 유니크 제약의 이중 방어를 사용합니다.

### 4.2 상태 전이

```
PENDING  → IN_PROGRESS  : Mock Worker 제출 성공
PENDING  → FAILED       : 영구 오류 또는 최대 재시도 초과
IN_PROGRESS → COMPLETED : Mock Worker가 COMPLETED 응답
IN_PROGRESS → FAILED    : 영구 오류 또는 poll 데드라인 초과
COMPLETED → (없음)      : 터미널 상태
FAILED    → (없음)      : 터미널 상태
```

허용되지 않는 전이(예: `PENDING → COMPLETED`, `COMPLETED → FAILED`)는 `BusinessException(INVALID_STATE_TRANSITION)`을 발생시킵니다.

### 4.3 처리 보장 모델

**At-Least-Once** 전달 보장입니다.

Mock Worker에는 멱등성 키가 없습니다.  
따라서 제출 성공 후 `workerJobId` 저장 전에 서버가 크래시되면, 재시작 후 해당 작업이 PENDING으로 남아 재제출됩니다.  
이 경우 Mock Worker에 동일 이미지 작업이 중복 생성될 수 있습니다(at-least-once).

Exactly-Once를 보장하려면 Mock Worker의 멱등성 키 지원이 필요합니다.

### 4.4 서버 재시작 시 동작

**자동 복구**: 재시작 후 스케줄러가 기동되면 `nextAttemptAt <= now`인 PENDING/IN_PROGRESS 작업부터 자동으로 재처리됩니다.  
별도의 복구 절차가 필요하지 않습니다.

**정합성이 깨질 수 있는 지점**:

| 시나리오 | 결과 |
|----------|------|
| Mock Worker 제출 성공 → `workerJobId` DB 저장 전 크래시 | 작업이 PENDING으로 남아 재제출 → 중복 워커 작업 가능 |
| `markSubmitted` 저장 성공 → 폴링 시작 전 크래시 | IN_PROGRESS 상태로 남아 재기동 후 폴링 재개. 정합성 유지 |
| 폴링 중 결과 수신 → DB 저장 전 크래시 | IN_PROGRESS 상태로 남아 재기동 후 폴링 재개. 동일 결과 재수신 |

---

## 아키텍처 개요

```
Client
  │ POST /api/v1/jobs
  ▼
JobController → ImageJobService → DB (image_job 테이블)
                                      ▲
                              (PENDING 행 조회)
                                      │
JobSubmitter ─── HTTP POST ──► Mock Worker
     │              ↑ 성공
     │         workerJobId 저장 (IN_PROGRESS 전이)
     │
JobPoller ─── HTTP GET ──► Mock Worker (workerJobId로 조회)
                               │
                          COMPLETED / FAILED / PROCESSING
                               │
                         DB 상태 업데이트
```

---

## 테스트 실행

```bash
./gradlew test
```
