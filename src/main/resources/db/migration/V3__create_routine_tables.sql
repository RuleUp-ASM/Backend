-- 루틴 매칭 기능 (제목/설명 → 템플릿 매칭 → 인증방식 추천 → 생성).
-- V1/V2 컨벤션 유지:
--   · users.id 가 CHAR(36)(UUID) 이므로 user_routine.user_id 도 CHAR(36) 로 맞춘다.
--   · routine_template 은 정적 지식베이스라 BIGINT auto_increment 사용(원본 스키마 그대로).
--   · 배열/맵 설정은 JSON, 값 검증은 앱에서(신뢰 경계: LLM/유저 입력은 서버가 재검증).

-- ===== routine_template : 루틴 지식베이스(매칭 대상) =====
CREATE TABLE routine_template (
                                  id                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                                  name                      VARCHAR(100)    NOT NULL,
                                  description               VARCHAR(255)    NULL,
                                  category                  ENUM('EXERCISE','READING','MEDITATION','HEALTH','WAKEUP',
                                   'WORK','STUDY','HOBBY','COOKING','FINANCE',
                                   'ENVIRONMENT','RELATIONSHIP','MUSIC','WRITING','CODING') NOT NULL,

    -- 자동 인증 옵션(자동 불가 루틴은 전부 NULL)
                                  auto_verification_type    ENUM('PHONE','HEALTH_CONNECT','EXTERNAL') NULL,
                                  auto_signal_source        ENUM('GEOFENCE','GPS','ACTIVITY','SLEEP','USAGE',
                                   'APP_FEATURE','HC_RECORD','EXTERNAL_API') NULL,
                                  auto_wearable_req         ENUM('NONE','OPTIONAL','REQUIRED') NULL,
                                  auto_external_service     VARCHAR(40)     NULL,
                                  auto_required_permissions JSON            NULL,   -- ["ACCESS_FINE_LOCATION", ...]

    -- 수동 인증 옵션(항상 존재)
                                  manual_signal_source      ENUM('PHOTO','GROUP_CHECK') NOT NULL DEFAULT 'PHOTO',

    -- 추천 기본값(생성 컬럼 = 단일 원천)
                                  has_auto       BOOLEAN AS (auto_verification_type IS NOT NULL) STORED,
                                  default_method ENUM('AUTO','MANUAL')
                   AS (IF(auto_verification_type IS NOT NULL,'AUTO','MANUAL')) STORED,

    -- 목표 파라미터(거리/시간/횟수 등). 예: {"distance_km":{"default":3,"unit":"km","min":1,"max":50}}
    -- min/max 는 선택(있으면 서버가 범위 검증, 없으면 양수만 확인).
                                  param_schema   JSON NULL,

                                  rationale      VARCHAR(255) NULL,
                                  created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                  updated_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

                                  PRIMARY KEY (id),
                                  KEY idx_routine_template_category (category),
                                  KEY idx_routine_template_has_auto (has_auto),
                                  FULLTEXT KEY ftx_name_desc (name, description) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== user_routine : 사용자가 실제 생성한 루틴 =====
--   매칭 성공 → template_id 채움 / 실패 → NULL + 수동 인증.
--   선택한 인증방식은 "스냅샷"으로 저장(템플릿이 바뀌어도 사용자 루틴은 보존).
CREATE TABLE user_routine (
                              id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                              user_id              CHAR(36)        NOT NULL,         -- users.id (UUID, CHAR(36))
                              title                VARCHAR(100)    NOT NULL,
                              description          VARCHAR(255)    NULL,
                              template_id          BIGINT UNSIGNED NULL,             -- 매칭된 템플릿(없으면 NULL)
                              selected_method      ENUM('AUTO','MANUAL') NOT NULL DEFAULT 'MANUAL',

                              verification_type    ENUM('PHONE','HEALTH_CONNECT','EXTERNAL','MANUAL') NOT NULL,
                              signal_source        ENUM('GEOFENCE','GPS','ACTIVITY','SLEEP','USAGE','APP_FEATURE',
                              'HC_RECORD','EXTERNAL_API','PHOTO','GROUP_CHECK') NOT NULL,
                              wearable_req         ENUM('NONE','OPTIONAL','REQUIRED') NOT NULL DEFAULT 'NONE',
                              required_permissions JSON NULL,
                              external_service     VARCHAR(40) NULL,

                              params               JSON NULL,    -- 사용자가 입력/수정한 목표값. 예: {"distance_km":5}

                              created_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                              updated_at           DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

                              PRIMARY KEY (id),
                              KEY idx_user_routine_user (user_id),
                              CONSTRAINT fk_user_routine_user
                                  FOREIGN KEY (user_id) REFERENCES users(id),
                              CONSTRAINT fk_user_routine_template
                                  FOREIGN KEY (template_id) REFERENCES routine_template(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;