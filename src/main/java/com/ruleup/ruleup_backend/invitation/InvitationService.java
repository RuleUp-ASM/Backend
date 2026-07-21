package com.ruleup.ruleup_backend.invitation;

import com.ruleup.ruleup_backend.invitation.domain.InvitationSignup;
import com.ruleup.ruleup_backend.invitation.domain.InviteCode;
import com.ruleup.ruleup_backend.me.dto.MeInvitationResponse;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 친구 초대(마이프로필 §6.4). 초대 코드(유저당 1개 멱등) + 피초대 가입 기록.
 * 보상 지급은 미정(기록만) — rewardDescription은 안내 문구.
 */
@Service
@RequiredArgsConstructor
public class InvitationService {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int CODE_LEN = 6;
    private static final int MAX_GEN_ATTEMPTS = 10;
    private static final String INVITE_URL_PREFIX = "https://android.ruleup.app/inv/";
    private static final String REWARD_DESCRIPTION = "초대한 친구가 가입하면 혜택이 지급될 예정이에요";

    private final SecureRandom random = new SecureRandom();

    private final InviteCodeRepository inviteCodeRepository;
    private final InvitationSignupRepository invitationSignupRepository;
    private final UserRepository userRepository;

    /** 내 초대 코드/현황(코드는 없으면 생성 후 반환 — 멱등). */
    @Transactional
    public MeInvitationResponse myInvitation(UUID userId) {
        String code = getOrCreateCode(userId).getCode();

        List<InvitationSignup> signups = invitationSignupRepository.findByInviterUserIdOrderByOccurredAtAsc(userId);
        Map<UUID, User> inviteeUsers = userRepository.findAllById(
                        signups.stream().map(InvitationSignup::getInviteeUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<MeInvitationResponse.Invitee> invitees = signups.stream().map(s -> {
            User u = inviteeUsers.get(s.getInviteeUserId());
            String nickname = (u != null) ? u.visibleNicknameTo(userId) : null;   // 검수 전이면 tempNickname
            return new MeInvitationResponse.Invitee(nickname, "SIGNED_UP", s.getOccurredAt().toString());
        }).toList();

        return new MeInvitationResponse(code, INVITE_URL_PREFIX + code, REWARD_DESCRIPTION, invitees);
    }

    /** 유저 초대 코드 조회 또는 생성(멱등). */
    @Transactional
    public InviteCode getOrCreateCode(UUID userId) {
        return inviteCodeRepository.findByUserId(userId).orElseGet(() -> createCode(userId));
    }

    private InviteCode createCode(UUID userId) {
        for (int i = 0; i < MAX_GEN_ATTEMPTS; i++) {
            String code = randomCode();
            if (inviteCodeRepository.existsByCode(code)) continue;
            try {
                return inviteCodeRepository.saveAndFlush(InviteCode.of(userId, code));
            } catch (DataIntegrityViolationException race) {
                // 동시 생성 경합: 내 코드가 이미 만들어졌으면 그것을, 코드 충돌이면 재시도.
                var existing = inviteCodeRepository.findByUserId(userId);
                if (existing.isPresent()) return existing.get();
            }
        }
        throw new IllegalStateException("초대 코드 생성에 실패했습니다.");
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LEN);
        for (int i = 0; i < CODE_LEN; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /**
     * 피초대 가입 기록(가입 API 연동). 코드가 유효하면 초대자↔피초대자 기록을 남긴다(멱등·자기초대 제외).
     * 유효하지 않은 코드는 조용히 무시(가입 자체는 실패시키지 않는다).
     */
    @Transactional
    public void recordSignup(String inviteCode, UUID inviteeUserId, Instant occurredAt) {
        if (inviteCode == null || inviteCode.isBlank()) return;
        InviteCode code = inviteCodeRepository.findByCode(inviteCode.trim().toUpperCase()).orElse(null);
        if (code == null) return;
        if (code.getUserId().equals(inviteeUserId)) return;                      // 자기초대 방지
        if (invitationSignupRepository.existsByInviteeUserId(inviteeUserId)) return;  // 1회만
        try {
            invitationSignupRepository.save(InvitationSignup.of(code.getUserId(), inviteeUserId, occurredAt));
        } catch (DataIntegrityViolationException dup) {
            // 동시성으로 이미 기록됨 → 멱등 무시.
        }
    }
}
