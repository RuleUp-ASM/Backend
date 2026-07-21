-- tempNickname을 저장 컬럼에서 계산 프로퍼티(User.tempNickname())로 전환.
-- PK(UUID v7)에서 결정적으로 파생되므로 저장할 필요가 없어 컬럼을 제거한다.
-- (인덱스/FK가 참조하지 않는 단순 컬럼이라 바로 DROP 가능.)
ALTER TABLE `User`
    DROP COLUMN `tempNickname`;
