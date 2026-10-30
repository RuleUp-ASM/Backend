package com.ruleup.ruleup_backend.challenge;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.challenge.creation.ChallengeImageUpload;
import com.ruleup.ruleup_backend.challenge.creation.ChallengeImageUploadRepository;
import com.ruleup.ruleup_backend.challenge.lifecycle.ChallengeCleanupService;
import com.ruleup.ruleup_backend.common.image.ImageObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * 미등록 업로드 이미지 정리 — 등록과의 경합(#381)과 저장소 삭제 실패 재시도(#382).
 *
 *  - 정리가 후보를 읽은 뒤 그중 하나가 등록되면, 그 파일은 지우지 않는다(각 건을 잠그며 다시 확인).
 *  - 정리가 한 건을 지우는 동안 같은 이미지를 등록하려는 쪽은 기다렸다가 「없는 이미지」를 본다.
 *  - 저장소 삭제가 실패하면 소유 기록이 남아 다음 회차에 다시 지운다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChallengeImageCleanupIT extends ChallengeApiSupport {

    @Autowired WebApplicationContext wac;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ChallengeCleanupService cleanupService;
    @Autowired ChallengeImageUploadRepository uploadRepository;
    @Autowired TransactionTemplate tx;
    @MockitoSpyBean ImageObjectStore store;

    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }
    @Override protected JdbcTemplate jdbc() { return jdbcTemplate; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    /** 25시간 전에 올리고 등록하지 않은 업로드 1건 → 파일명. */
    private String insertOrphan(UUID userId) {
        String filename = UUID.randomUUID() + ".png";
        jdbcTemplate.update("INSERT INTO challenge_image_uploads (user_id, image_url, registered_at, created_at) " +
                        "VALUES (?, ?, NULL, DATE_SUB(NOW(6), INTERVAL 25 HOUR))",
                bytes(userId), urlOf(filename));
        return filename;
    }

    private static String urlOf(String filename) {
        return "http://localhost/files/" + filename;
    }

    private int rowCount(String filename) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM challenge_image_uploads WHERE image_url = ?", Integer.class, urlOf(filename));
    }

    @Test
    @DisplayName("후보를 읽은 뒤 등록된 이미지는 지우지 않고, 지우는 중인 이미지의 등록은 끝날 때까지 기다린다")
    void registrationDuringCleanupKeepsTheFile() throws Exception {
        Member m = member(uniq("img-race"));
        String first = insertOrphan(m.id());     // id 순서상 먼저 처리된다
        String second = insertOrphan(m.id());

        // 첫 건의 파일 삭제 안에서 멈춘다 — 이 시점에 후보 목록(두 건 모두)은 이미 읽혔다
        CountDownLatch inDelete = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            inDelete.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return inv.callRealMethod();
        }).when(store).delete(first);

        CompletableFuture<Void> cleanup = CompletableFuture.runAsync(cleanupService::cleanupOrphanImageUploads);
        assertThat(inDelete.await(10, TimeUnit.SECONDS)).isTrue();

        // ① 목록에 오른 두 번째 건이 그 사이 챌린지에 등록된다(커밋까지)
        jdbcTemplate.update("UPDATE challenge_image_uploads SET registered_at = NOW(6) WHERE image_url = ?",
                urlOf(second));

        // ② 지우는 중인 첫 건을 등록하려는 쪽은 그 행에서 기다린다
        CompletableFuture<Optional<ChallengeImageUpload>> registering = CompletableFuture.supplyAsync(() ->
                tx.execute(status -> uploadRepository.findByImageUrlForUpdate(urlOf(first))));
        Thread.sleep(500);
        assertThat(registering).as("정리가 그 행을 잡은 동안 등록은 진행되지 않는다").isNotDone();

        release.countDown();
        cleanup.get(10, TimeUnit.SECONDS);

        assertThat(registering.get(10, TimeUnit.SECONDS))
                .as("정리가 끝난 뒤의 등록은 사라진 이미지를 보고 거절된다").isEmpty();
        assertThat(rowCount(first)).isZero();
        verify(store, never()).delete(second);
        assertThat(rowCount(second)).as("등록된 이미지의 소유 기록은 남는다").isEqualTo(1);
    }

    @Test
    @DisplayName("저장소 삭제가 한 번 실패하면 기록이 남고, 다음 회차에 다시 지운다")
    void failedDeleteIsRetriedNextRun() throws Exception {
        Member m = member(uniq("img-retry"));
        String filename = insertOrphan(m.id());
        doThrow(new UncheckedIOException(new IOException("일시 장애")))
                .doCallRealMethod()
                .when(store).delete(filename);

        cleanupService.cleanupOrphanImageUploads();
        assertThat(rowCount(filename)).as("삭제에 실패한 건은 다음 회차의 대상으로 남는다").isEqualTo(1);

        cleanupService.cleanupOrphanImageUploads();
        assertThat(rowCount(filename)).isZero();
        verify(store, times(2)).delete(eq(filename));
    }
}
