package com.ruleup.ruleup_backend.agreement;

import com.ruleup.ruleup_backend.agreement.domain.AgreementType;
import com.ruleup.ruleup_backend.agreement.domain.UserAgreementEvent;
import com.ruleup.ruleup_backend.agreement.domain.UserAgreementState;
import com.ruleup.ruleup_backend.agreement.dto.AgreementDtos;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.config.AppProperties;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 동의 체계 — 온보딩 테크 스펙 5-7.
 *
 * <p>이 서비스의 유일한 불변식은 <b>상태와 이력을 한 트랜잭션에서 같이 쓴다</b>는 것이다.
 * 둘이 나뉘면 동의는 받았는데 근거가 없거나(법적 위험), 근거는 있는데 게이트가 막는(장애)
 * 상태가 남는다. 그래서 기록 경로를 {@link #record} 하나로 모아 두고 가입·제출이 함께 쓴다.
 */
@Service
@RequiredArgsConstructor
public class AgreementService {

    private final UserRepository userRepository;
    private final UserAgreementStateRepository stateRepository;
    private final UserAgreementEventRepository eventRepository;
    private final AppProperties props;

    // ===== 조회 =====

    /**
     * 동의 현재 상태 7종 + 재동의 필요 항목.
     *
     * <p>{@code user_agreement_states}만 읽는다 — 유저당 최대 7행이므로 이력 테이블을 뒤지지 않는다.
     * 잠금 계정도 열람할 수 있어야 하므로 상태 게이트를 걸지 않는다.
     */
    @Transactional(readOnly = true)
    public AgreementDtos.StatusResponse status(UUID userId) {
        Map<AgreementType, UserAgreementState> states = statesOf(userId);

        List<AgreementDtos.StatusResponse.Item> items = new ArrayList<>();
        List<String> reconsent = new ArrayList<>();

        for (AgreementType type : AgreementType.values()) {
            UserAgreementState s = states.get(type);
            items.add(toItem(type, s));
            if (needsReconsent(type, s)) reconsent.add(type.name());
        }
        return new AgreementDtos.StatusResponse(items, reconsent);
    }

    // ===== 제출·철회 =====

    /**
     * 동의 제출·철회. 403 {@code AGREEMENT_REQUIRED}의 유일한 해소 경로다.
     *
     * <p>배열 전체가 한 트랜잭션이므로 하나라도 실패하면 앞 항목까지 전부 롤백된다 — 부분 반영은
     * "동의 화면을 닫았는데 일부만 저장된" 상태를 만들어 재현이 어려운 문의로 이어진다.
     */
    @Transactional
    public AgreementDtos.StatusResponse submit(UUID userId, AgreementDtos.SubmitRequest request) {
        List<AgreementDtos.SubmitRequest.Item> raw =
                (request == null) ? null : request.agreements();
        if (raw == null || raw.isEmpty()) throw new BusinessException(ErrorCode.INVALID_REQUEST);

        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_REQUIRED));

        Instant now = Instant.now();
        List<AgreementDtos.StatusResponse.Item> updated = new ArrayList<>();

        for (AgreementDtos.SubmitRequest.Item item : raw) {
            AgreementType type = parseType(item.type());
            if (item.agreed() == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);

            // 필수 3종은 철회할 수 없다 — 철회하려면 탈퇴해야 한다.
            if (type.isRequired() && !item.agreed())
                throw new BusinessException(ErrorCode.AGREEMENT_REVOKE_FORBIDDEN);

            String current = props.client().termsVersions().of(type);
            if (!current.equals(item.version()))
                throw new BusinessException(ErrorCode.AGREEMENT_VERSION_MISMATCH);

            record(user, type, item.agreed(), current, now);
            updated.add(new AgreementDtos.StatusResponse.Item(
                    type.name(), type.isRequired(), item.agreed(), current, now.toString()));
        }

        // 처리 후 남은 재동의 항목 — 비어 있으면 클라이언트가 화면을 닫는다.
        Map<AgreementType, UserAgreementState> after = statesOf(userId);
        List<String> reconsent = new ArrayList<>();
        for (AgreementType type : AgreementType.values()) {
            if (needsReconsent(type, after.get(type))) reconsent.add(type.name());
        }
        return new AgreementDtos.StatusResponse(updated, reconsent);
    }

    // ===== 기록 (가입·제출 공용) =====

    /**
     * 상태 UPSERT + 이력 INSERT — <b>둘은 절대 나누지 않는다</b>.
     * 호출자의 트랜잭션에 참여하므로 별도 전파 설정을 두지 않는다.
     */
    public void record(User user, AgreementType type, boolean agreed, String version, Instant at) {
        stateRepository.findById(new UserAgreementState.Key(user.getId(), type))
                .ifPresentOrElse(
                        s -> s.apply(agreed, version, at),
                        () -> stateRepository.save(
                                UserAgreementState.of(user.getId(), type, agreed, version, at)));
        eventRepository.save(UserAgreementEvent.of(user, type, agreed, version));
    }

    // ===== 게이트 =====

    /**
     * 개별 동의 게이트 — 위치·건강 인증 수단이 호출한다.
     * PK 조회 한 번이라 인증 제출마다 불러도 부담이 없다.
     */
    @Transactional(readOnly = true)
    public boolean hasIndividualConsent(UUID userId, AgreementType type) {
        return stateRepository.findById(new UserAgreementState.Key(userId, type))
                .map(UserAgreementState::isAgreed)
                .orElse(false);
    }

    /** 미동의면 403 {@code AGREEMENT_REQUIRED} — 클라이언트는 동의 화면으로 보낸다. */
    public void requireIndividualConsent(UUID userId, AgreementType type) {
        if (!hasIndividualConsent(userId, type))
            throw new BusinessException(ErrorCode.AGREEMENT_REQUIRED);
    }

    // ===== 내부 =====

    private Map<AgreementType, UserAgreementState> statesOf(UUID userId) {
        Map<AgreementType, UserAgreementState> map = new EnumMap<>(AgreementType.class);
        stateRepository.findByUserId(userId).forEach(s -> map.put(s.getAgreementType(), s));
        return map;
    }

    private AgreementDtos.StatusResponse.Item toItem(AgreementType type, UserAgreementState s) {
        if (s == null) {
            // 한 번도 동의한 적 없음 — version·agreedAt 을 null 로 내려 "동의 후 철회"와 구분한다.
            return new AgreementDtos.StatusResponse.Item(type.name(), type.isRequired(), false, null, null);
        }
        return new AgreementDtos.StatusResponse.Item(
                type.name(), type.isRequired(), s.isAgreed(), s.getVersion(),
                s.getAgreedAt() != null ? s.getAgreedAt().toString() : null);
    }

    /**
     * 재동의 대상은 <b>필수 약관 중 저장 버전이 현행과 다른 것</b>뿐이다.
     * 선택 약관은 개정돼도 화면을 막지 않는다 — 막을 근거가 없고 이탈만 만든다.
     */
    private boolean needsReconsent(AgreementType type, UserAgreementState s) {
        if (!type.isRequired()) return false;
        if (s == null || !s.isAgreed()) return true;
        return !props.client().termsVersions().of(type).equals(s.getVersion());
    }

    private AgreementType parseType(String raw) {
        if (raw == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        try {
            return AgreementType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            // 폐지된 NIGHT_PUSH 를 그대로 보내는 구 클라이언트도 여기로 떨어진다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
