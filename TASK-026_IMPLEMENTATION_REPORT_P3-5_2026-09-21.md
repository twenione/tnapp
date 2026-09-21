# TASK-026 P3-5 구현 보고

## 변경

- `session_schema.json`에 `ondemand.request`·`ondemand.config`, guide `trigger`, 동적 reason 세부정보, 경로 고도 사용 판정(used/reason), 웨이포인트 수와 합성 표기를 가산적으로 반영했다.
- `validate_session.py`와 `replay.py`가 trigger guide를 프레임 loc↔guide 수에서 제외하고 `src_seq` 실재성을 확인한다. replay는 decision, `reason.rule`, 기록된 reason 세부 키·값, GPX 고도 판정을 대조하며 `--strict`에서 불일치 시 `FAIL:`로 종료한다.
- `EngineCli`가 실제 엔진 reason 세부정보와 trigger 레코드를 JSONL로 내보낸다.
- privacy scanner에 `route.gpx.elevation`과 `route.gpx.waypoint_name`을 추가하고 합성 표기가 없는 경로를 반증 fixture로 검사한다.
- 합성 생성기는 합성 고도 프로파일·웨이포인트 세션을 생성할 수 있고, log-shape 생성기는 고도·웨이포인트를 제거한 경로와 `absent` 판정을 고정한다.
- trigger `src_seq` 누락, reason.rule 변조, elevation manifest 변조 반증을 guard에 연결했다.

## PR·검증

- PR #83: `feat(tools): align Phase 3 session contracts`
- 최종 head: `3d480d73c83d3de5cbff01fb78084854f6864b27` (CI 완료 후 병합 예정)
- 초기 실패 run `35593915706`: replay job shell loop 누락, privacy fixture 좌표가 기존 좌표 kind와 중복, reason detail strict 비교가 기존 log-shape 직렬화 오차를 탐지. 수정 커밋 `4208372`, `e60604f`.
- 실패 run `35594831454`: 기존 fixture의 수치 직렬화 오차로 292건 불일치. 수정 `81a3860`.
- 실패 run `35595596851`: 잔여 turn detail 1건. 수정 `d88105b`.
- 실패 run `35596220607`: 잔여 geometry detail 표현 차이. 수정 `5d9f71f`.
- 실패 run `35596357585`: turn detail label 표현 차이. 수정 `3d480d7`.
- 각 수정은 `fix(build):` 제목과 해당 `Failed-Run:` trailer를 사용했다.

## 결과

로컬에서 privacy coverage, synthetic-data 검사, fixture shape, trigger validator와 기존 golden/synth strict replay(기존 CLI)를 확인했다. 최신 pull_request run은 모든 필수 guard 결과가 완료된 뒤 병합한다. P3-5는 엔진 파라미터나 픽스처를 재생성하지 않았다.
