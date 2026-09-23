package com.ruleup.ruleup_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * {@code /files/{파일명}} → 로컬 업로드 폴더의 파일을 정적으로 서빙.
 *
 * <p>S3 모드에서는 이 핸들러를 등록하지 않는다 — 같은 경로를
 * {@code ImageFileController} 가 맡아 presigned URL 로 넘긴다. 둘을 동시에 등록하면
 * 어느 쪽이 이기는지가 매핑 우선순위에 달리게 되므로 <b>한쪽만</b> 존재하게 한다.
 */
@Configuration
@ConditionalOnProperty(name = "app.upload.storage", havingValue = "local", matchIfMissing = true)
public class WebConfig implements WebMvcConfigurer {

    private final String uploadDir;

    public WebConfig(@Value("${app.upload.dir:./uploads}") String dir) {
        this.uploadDir = Paths.get(dir).toAbsolutePath().normalize().toString();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/files/**")
                .addResourceLocations("file:" + uploadDir + "/");
    }
}