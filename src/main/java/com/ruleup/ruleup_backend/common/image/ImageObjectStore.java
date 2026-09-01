package com.ruleup.ruleup_backend.common.image;

import java.time.Duration;
import java.util.Optional;

/**
 * 이미지 바이트가 실제로 놓이는 곳.
 *
 * <h4>왜 URL 이 아니라 파일명을 다루나</h4>
 * 이 인터페이스는 <b>파일명만</b> 안다. 바깥에 노출되는 주소는 저장소가 어디든
 * {@code /files/{파일명}} 으로 고정이며({@link ImageStorageService#urlOf}), 그래서 저장소를
 * 로컬↔S3 로 바꿔도 <b>DB 에 이미 쌓인 URL 과 클라이언트 왕복 계약이 그대로 산다</b>.
 * 주소 형식까지 저장소가 정하게 두면 저장소를 바꿀 때마다 데이터 마이그레이션이 따라온다.
 */
public interface ImageObjectStore {

    /** 바이트를 저장한다. 같은 파일명이면 덮어쓴다. */
    void put(String filename, byte[] bytes, String contentType);

    /**
     * 지운다. <b>없는 파일을 지우는 것은 성공</b>으로 취급한다 —
     * 정리 배치가 두 번 돌았다고 실패로 볼 이유가 없다.
     */
    void delete(String filename);

    /**
     * 짧게 유효한 직접 접근 주소. 로컬 저장소는 빈 값을 주고, 그때는 서버가 파일을 직접 서빙한다.
     *
     * <p>S3 는 이 값을 주고 {@code /files/{파일명}} 이 302 로 넘긴다 — 버킷을 공개로 열지 않고도
     * 이미지가 보이게 하는 유일한 방법이고, 이미지 트래픽이 애플리케이션을 거치지 않게 한다.
     */
    Optional<String> directUrl(String filename, Duration ttl);
}
