package com.ruleup.ruleup_backend.common.image;

import com.ruleup.ruleup_backend.TestcontainersConfiguration;
import com.ruleup.ruleup_backend.auth.AuthApiSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * 이미지 S3 저장 — 실제 S3 API(LocalStack)로 끝까지 검증한다.
 *
 * <p>목으로는 알 수 없는 것을 잡는 스위트다. <b>키 접두사 · presigned 서명 · 302 리다이렉트가
 * 실제로 맞물려 이미지 바이트까지 도달하는지</b>는 진짜 S3 구현을 상대해야 확인된다.
 *
 * <p>가장 중요한 계약은 <b>저장소가 바뀌어도 바깥 주소가 그대로</b>라는 것이다. 그 주소는
 * DB 에 저장되고 클라이언트가 되돌려 보내는 값이라, 저장소 사정으로 형식이 바뀌면 기존
 * 이미지가 전부 죽고 업로드 소유 검증도 깨진다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, ImageS3StorageIT.LocalStackConfig.class})
class ImageS3StorageIT extends AuthApiSupport {

    private static final String BUCKET = "ruleup-test-media";
    private static final String PREFIX = "media";

    /** 1x1 PNG — 매직넘버 검증을 통과하는 최소 이미지. */
    private static final byte[] PNG_1X1 = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE,
            0x42, 0x60, (byte) 0x82};

    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.S3);

    static {
        LOCALSTACK.start();
    }

    @DynamicPropertySource
    static void s3Properties(DynamicPropertyRegistry registry) {
        registry.add("app.upload.storage", () -> "s3");
        registry.add("app.upload.s3.bucket", () -> BUCKET);
        registry.add("app.upload.s3.key-prefix", () -> PREFIX);
        registry.add("app.upload.s3.region", () -> LOCALSTACK.getRegion());
        registry.add("app.upload.s3.endpoint",
                () -> LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3).toString());
        // LocalStack 은 아무 자격증명이나 받지만 SDK 는 체인에서 뭔가 찾아야 한다.
        System.setProperty("aws.accessKeyId", LOCALSTACK.getAccessKey());
        System.setProperty("aws.secretAccessKey", LOCALSTACK.getSecretKey());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalStackConfig {
        LocalStackConfig(S3Client s3) {
            try {
                s3.createBucket(b -> b.bucket(BUCKET));
            } catch (SdkException alreadyExists) {
                // 컨텍스트가 재사용되면 두 번째 생성은 실패한다 — 그건 정상이다.
            }
        }
    }

    @Autowired WebApplicationContext wac;
    @Autowired S3Client s3;
    @Autowired ImageStorageService imageStorage;
    MockMvc mvc;

    @Override protected MockMvc mvc() { return mvc; }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    private String uploadProfileImage() throws Exception {
        String tag = uniq("s3img");
        String at = read(signup(tag, "S3사진" + System.nanoTime() % 100000), "$.data.accessToken");
        MvcResult res = mvc.perform(multipart("/api/v1/users/me/profile-image")
                .file(new MockMultipartFile("image", "p.png", MediaType.IMAGE_PNG_VALUE, PNG_1X1))
                .header("Authorization", "Bearer " + at)).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return read(res, "$.data.imageUrl");
    }

    private static String filenameOf(String url) {
        return url.substring(url.indexOf("/files/") + "/files/".length());
    }

    private byte[] objectBytes(String filename) {
        try (InputStream in = s3.getObject(GetObjectRequest.builder()
                .bucket(BUCKET).key(PREFIX + "/" + filename).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new AssertionError("S3 객체를 읽지 못했다: " + filename, e);
        }
    }

    // =================================================================

    @Test
    @DisplayName("업로드하면 바이트는 S3 로 가지만 바깥 주소는 /files/{파일명} 그대로다")
    void bytesGoToS3ButUrlShapeIsUnchanged() throws Exception {
        String url = uploadProfileImage();

        // 저장소가 바뀌어도 이 형식이 유지돼야 한다 — DB 에 저장되고 클라이언트가 되돌려 보내는 값이다.
        assertThat(url).contains("/files/");
        assertThat(url).doesNotContain("amazonaws.com").doesNotContain("X-Amz-");

        // 실제 바이트는 접두사 아래 놓인다. 접두사는 주소에 드러나지 않는다.
        assertThat(objectBytes(filenameOf(url))).isEqualTo(PNG_1X1);
    }

    @Test
    @DisplayName("GET /files/{파일명} 은 presigned URL 로 302 하고, 그 주소로 실제 바이트가 내려온다")
    void serveRedirectsToPresignedUrlThatWorks() throws Exception {
        String filename = filenameOf(uploadProfileImage());

        MvcResult redirect = mvc.perform(get("/files/" + filename)).andReturn();

        assertThat(redirect.getResponse().getStatus()).isEqualTo(302);
        String presigned = redirect.getResponse().getHeader("Location");
        assertThat(presigned).contains("X-Amz-Signature").contains(PREFIX + "/" + filename);
        // 만료되는 주소를 프록시가 캐시하면 죽은 링크를 계속 나눠 준다.
        assertThat(redirect.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");

        HttpURLConnection conn = (HttpURLConnection) URI.create(presigned).toURL().openConnection();
        try (InputStream in = conn.getInputStream()) {
            assertThat(conn.getResponseCode()).isEqualTo(200);
            assertThat(in.readAllBytes()).isEqualTo(PNG_1X1);
        } finally {
            conn.disconnect();
        }
    }

    @Test
    @DisplayName("없는 파일도 302 한다 — 이미지 한 장마다 존재 확인을 걸지 않는다")
    void missingObjectStillRedirects() throws Exception {
        // 목록 한 화면에 이미지가 수십 개 뜨는 구조라, 서빙마다 HeadObject 를 붙이면 S3 호출이
        // 두 배가 된다. 없는 키의 404 는 S3 가 직접 주게 두는 편이 낫다 — 어차피 클라이언트가
        // 보는 결과(깨진 이미지)는 같고, 서명은 그 키 하나의 읽기만 허용하므로 위험도 없다.
        MvcResult res = mvc.perform(get("/files/does-not-exist.png")).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(302);

        String presigned = res.getResponse().getHeader("Location");
        HttpURLConnection conn = (HttpURLConnection) URI.create(presigned).toURL().openConnection();
        try {
            assertThat(conn.getResponseCode()).as("실제 404 는 S3 가 준다").isEqualTo(404);
        } finally {
            conn.disconnect();
        }
    }

    @Test
    @DisplayName("URL 로 삭제하면 S3 객체가 사라진다 — 정리 배치가 이 경로를 쓴다")
    void deleteByUrlRemovesTheObject() throws Exception {
        String url = uploadProfileImage();
        String filename = filenameOf(url);
        assertThat(objectBytes(filename)).isEqualTo(PNG_1X1);   // 지금은 있다

        imageStorage.deleteByUrl(url);

        assertThatObjectIsGone(filename);
    }

    private void assertThatObjectIsGone(String filename) {
        try {
            objectBytes(filename);
            throw new AssertionError("삭제됐어야 할 객체가 남아 있다: " + filename);
        } catch (AssertionError e) {
            if (!(e.getCause() instanceof NoSuchKeyException)) throw e;
        }
    }

    @Test
    @DisplayName("우리 경로가 아닌 URL 은 삭제 대상이 아니다 — 정리 배치가 외부 주소에 막히지 않는다")
    void foreignUrlIsIgnoredOnDelete() {
        // 예외를 던지면 배치가 그 한 건에서 멈춰 뒤에 밀린 정상 건까지 정리되지 않는다.
        imageStorage.deleteByUrl("https://evil.example.com/x.png");
        imageStorage.deleteByUrl(null);
        assertThat(imageStorage.filenameOf("https://evil.example.com/x.png")).isEmpty();
        assertThat(imageStorage.filenameOf("http://h/files/a/b.png"))
                .as("경로 구분자가 남아 있으면 우리가 만든 파일명이 아니다").isEmpty();
    }
}
