package com.ruleup.ruleup_backend.push.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Firebase Admin 초기화 — 고스트(무음) 푸시 전송용 {@link FirebaseMessaging} 빈을 노출한다.
 *
 * <p>{@code app.fcm.enabled=true} 일 때만 활성화된다. 비활성(로컬/CI 기본값)이면 이 빈들이 뜨지 않고
 * {@link com.ruleup.ruleup_backend.push.LoggingPushSender} 스텁이 유일한 {@code PushSender} 로 남아
 * 기동/테스트에 영향이 없다.
 *
 * <p>자격증명(서비스 계정 JSON) 로딩 우선순위: {@code credentials-json}(원문, Secrets Manager 주입)
 * → {@code credentials-path}(마운트 파일) → Application Default Credentials(ECS/GCE 환경).
 */
@Configuration
@ConditionalOnProperty(prefix = "app.fcm", name = "enabled", havingValue = "true")
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Bean
    FirebaseApp firebaseApp(@Value("${app.fcm.credentials-json:}") String credentialsJson,
                            @Value("${app.fcm.credentials-path:}") String credentialsPath,
                            @Value("${app.fcm.project-id:}") String projectId) throws IOException {
        GoogleCredentials credentials = loadCredentials(credentialsJson, credentialsPath);

        FirebaseOptions.Builder builder = FirebaseOptions.builder().setCredentials(credentials);
        if (projectId != null && !projectId.isBlank()) {
            builder.setProjectId(projectId);
        }
        FirebaseOptions options = builder.build();

        // 이미 초기화된 앱이 있으면 재사용(devtools 재기동·다중 컨텍스트에서 중복 init 방지).
        FirebaseApp app = FirebaseApp.getApps().isEmpty()
                ? FirebaseApp.initializeApp(options)
                : FirebaseApp.getInstance();
        log.info("Firebase Admin 초기화 완료 (projectId={})", (projectId == null || projectId.isBlank()) ? "(default)" : projectId);
        return app;
    }

    @Bean
    FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
        return FirebaseMessaging.getInstance(firebaseApp);
    }

    private GoogleCredentials loadCredentials(String credentialsJson, String credentialsPath) throws IOException {
        if (credentialsJson != null && !credentialsJson.isBlank()) {
            try (InputStream in = new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8))) {
                return GoogleCredentials.fromStream(in);
            }
        }
        if (credentialsPath != null && !credentialsPath.isBlank()) {
            try (InputStream in = new FileInputStream(credentialsPath)) {
                return GoogleCredentials.fromStream(in);
            }
        }
        return GoogleCredentials.getApplicationDefault();
    }
}
