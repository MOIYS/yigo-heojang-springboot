# yigo-heojang

카드 혜택 최적화 전자지갑 — 보유 카드·실적·가맹점을 바탕으로 결제 직전 최적 카드를 추천한다. (KB IT's Your Life 7기 종합실무)

## 폴더 구조

| 폴더 | 내용 |
| --- | --- |
| `backend/` | Spring Boot 3.5 백엔드 · 추천/계산 엔진 (Maven, 실행 JAR) |
| `ai-server/` | Python AI 챗봇 서버 (V2, 추후) |
| `frontend/` | Vue.js 프론트엔드 |
| `docs/` | API 명세 등 문서 |

각 폴더는 별개 프로세스로 실행되며 REST로 통신한다. 실행 방법은 각 폴더의 README를 참고한다.

## DB 세팅

테이블 구조는 애플리케이션 기동 시 Flyway가
`backend/src/main/resources/db/migration/V1__init_schema.sql`을 적용해 생성한다.
기존 수동 생성 DB는 baseline으로 등록하지 않으므로, 최초 전환 시 사용자가 DB를 직접 초기화해야 한다.
초기화와 검증 명령은 [backend 실행 문서](backend/README.md#flyway-초기화)에 정리되어 있다.

Flyway 적용이 끝난 뒤 `backend/db/`의 시드 SQL을 **번호 순서대로 수동 실행**한다.
순서를 지켜야 하는 이유는 뒤 파일이 앞 파일이 만든 행을 참조하기 때문이다.

| 순서 | 파일 | 내용 |
| --- | --- | --- |
| 1 | `data.sql` | 회원·약관·카테고리·포인트사 등 기준 데이터 |
| 2 | `91_seed_card_benefit.sql` | 카드·혜택·연회비 |
| 3 | `92_seed_user_data.sql` | 보유카드·소비내역·계산 상태 |
| 4 | `94_seed_membership_usage_place.sql` | 멤버십 주요 사용처·공식 사이트 URL |

```bash
cd backend/db
for f in data.sql 91_seed_card_benefit.sql 92_seed_user_data.sql 94_seed_membership_usage_place.sql; do
  mysql -u root -p <DB이름> < "$f"
done
```

### pull 받은 뒤 화면이 비거나 500이 날 때

애플리케이션 기동 로그에서 Flyway 마이그레이션 성공 여부를 먼저 확인한다.
V1 전환 전에 수동으로 만든 테이블이 남아 있으면 baseline을 만들지 않고 기동을 중단하므로,
개발 DB를 백업한 뒤 사용자가 직접 초기화해야 한다.

시드 데이터만 갱신하려면 필요한 `9x_` 파일을 수동으로 실행한다. 해당 파일은 다시 실행해도 중복이 쌓이지 않게 만들어 두었다.

```bash
mysql -u root -p <DB이름> < backend/db/94_seed_membership_usage_place.sql
```

### 카드 이미지

카드 이미지는 애플리케이션 JAR 바깥 폴더에서 서빙한다. `backend/src/main/resources/db.properties`에 경로를 적고,
그 아래 `cards/` 폴더에 이미지를 둔다.

```
app.image.storage-path=C:/Users/<사용자>/Desktop/wallet-images
```

### DB를 바꾸는 변경을 올릴 때

- 스키마 변경은 `backend/src/main/resources/db/migration/`에 새 버전 마이그레이션으로 추가한다. 적용된 V1은 수정하지 않는다.
- seed 파일에는 새 DDL을 추가하지 않는다. 기존 `94_seed_membership_usage_place.sql`의 호환용 ALTER 블록은
  Flyway 이전 수동 스키마를 위한 레거시 처리이며, 새 스키마 변경의 기준으로 사용하지 않는다.
- 시드에서 다른 테이블을 참조할 때는 **id가 아니라 이름으로** 찾는다. id는 DB마다 다르다 —
  실제로 개발 DB와 새로 만든 DB의 `point_provider` id가 2씩 어긋나 있었고, id로 적었을 때
  URL 18건이 에러 없이 엉뚱한 제공사에 들어갔다.
- PR 본문에 "이 스크립트를 돌려야 한다"를 적는다.
