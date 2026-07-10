-- 경로: src/main/resources/db/migration/V3__drop_dead_member_columns.sql
-- 코드 정리: ChallengeMember.periodsTotal/periodsMet 은 쓰기만 하고 어디서도 읽지 않던 죽은 역정규화 컬럼.
-- 엔티티 매핑·갱신 로직을 제거했으므로 컬럼도 정리한다(둘 다 nullable, 소비 쿼리 없음 → 안전).
ALTER TABLE ChallengeMember
    DROP COLUMN periodsTotal,
    DROP COLUMN periodsMet;
