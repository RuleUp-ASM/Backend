package com.ruleup.ruleup_backend.common.image;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.util.Optional;

/**
 * S3 저장 — stg·prod.
 *
 * <h4>버킷을 공개로 열지 않는다</h4>
 * 미디어 버킷은 퍼블릭 액세스가 전부 차단돼 있고 그대로 둔다. 대신 읽기는 <b>짧게 유효한
 * presigned GET</b> 으로 넘긴다({@link #directUrl}). 이렇게 하면 이미지 바이트가 애플리케이션을
 * 거치지 않으므로 태스크 대역폭과 CPU 를 먹지 않는다.
 *
 * <h4>키에 접두사를 붙이되 파일명은 그대로 둔다</h4>
 * 실제 키는 {@code {prefix}/{파일명}} 이지만 바깥 URL 은 {@code /files/{파일명}} 그대로다.
 * 접두사는 <b>버킷 안 정리와 수명주기 규칙을 걸기 위한 것</b>이지 주소 체계가 아니다 —
 * 주소에 새어 나가면 나중에 접두사를 바꿀 때 저장된 URL 이 전부 죽는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.upload.storage", havingValue = "s3")
public class S3ImageObjectStore implements ImageObjectStore {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final String keyPrefix;

    public S3ImageObjectStore(S3Client s3, S3Presigner presigner,
                              @Value("${app.upload.s3.bucket}") String bucket,
                              @Value("${app.upload.s3.key-prefix:media}") String keyPrefix) {
        this.s3 = s3;
        this.presigner = presigner;
        // 조용히 로컬로 떨어지지 않는다 — 그러면 배포마다 사진이 사라지는 것을 아무도 모른다.
        if (bucket == null || bucket.isBlank())
            throw new IllegalStateException("app.upload.storage=s3 인데 app.upload.s3.bucket 이 비어 있다");
        this.bucket = bucket;
        // 접두사에 붙은 슬래시는 키를 // 로 만들어 조회가 어긋나게 한다.
        this.keyPrefix = keyPrefix.replaceAll("^/+|/+$", "");
    }

    @Override
    public void put(String filename, byte[] bytes, String contentType) {
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(keyOf(filename))
                            .contentType(contentType)
                            .contentLength((long) bytes.length)
                            .build(),
                    RequestBody.fromBytes(bytes));
        } catch (SdkException e) {
            // 업로드 실패는 사용자에게 보이는 실패다 — 삼키면 "올렸는데 사진이 없다"가 된다.
            log.error("S3 업로드 실패 bucket={} key={}: {}", bucket, keyOf(filename), e.toString());
            throw new BusinessException(ErrorCode.IMAGE_CORRUPTED);
        }
    }

    @Override
    public void delete(String filename) {
        try {
            // S3 의 DeleteObject 는 없는 키에도 성공을 준다 — 정리 배치가 두 번 돌아도 안전하다.
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket).key(keyOf(filename)).build());
        } catch (SdkException e) {
            // 삭제 실패로 상위 작업을 되돌리지 않는다. 남은 객체는 아무도 참조하지 않는
            // 쓰레기라 다음 회차나 수동 정리로 치우면 된다.
            log.warn("S3 이미지 삭제 실패 key={}: {}", keyOf(filename), e.toString());
        }
    }

    @Override
    public Optional<String> directUrl(String filename, Duration ttl) {
        try {
            return Optional.of(presigner.presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(ttl)
                            .getObjectRequest(GetObjectRequest.builder()
                                    .bucket(bucket).key(keyOf(filename)).build())
                            .build())
                    .url().toString());
        } catch (SdkException e) {
            log.warn("presigned URL 발급 실패 key={}: {}", keyOf(filename), e.toString());
            return Optional.empty();
        }
    }

    private String keyOf(String filename) {
        return keyPrefix.isEmpty() ? filename : keyPrefix + "/" + filename;
    }
}
