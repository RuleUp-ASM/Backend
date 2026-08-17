package com.ruleup.ruleup_backend.challenge.service;

import com.ruleup.ruleup_backend.challenge.domain.Challenge;
import com.ruleup.ruleup_backend.challenge.domain.ChallengeInvitation;
import com.ruleup.ruleup_backend.challenge.domain.InvitationTokens;
import com.ruleup.ruleup_backend.challenge.domain.JoinBlockReason;
import com.ruleup.ruleup_backend.challenge.dto.InvitationDtos;
import com.ruleup.ruleup_backend.challenge.dto.JoinResponse;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeInvitationRepository;
import com.ruleup.ruleup_backend.challenge.repository.ChallengeRepository;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 초대 링크 조회 · 수락.
 *
 * <p>발급은 {@link RoomAdminService#invite} 소관이고 여기는 <b>받은 사람</b> 쪽이다.
 * 두 엔드포인트의 역할이 뚜렷이 갈린다.
 * <ul>
 *   <li>조회 — 부작용 없음. 어떤 방인지 + 수락해도 되는지({@code joinable})만 알려준다.
 *       "수락을 눌렀더니 거절"보다 버튼을 누르기 전에 이유를 보여주는 편이 낫다.</li>
 *   <li>수락 — <b>가입 그 자체</b>다. 비공개 검증만 토큰으로 대체되고 나머지 게이트는 그대로 걸린다.</li>
 * </ul>
 *
 * <p>토큰은 1회성이다. 다만 <b>가입에 성공했을 때만</b> 소모한다 — 정원이 차서 막힌 사람이 링크까지
 * 잃으면, 자리가 난 뒤 다시 들어올 방법이 없어진다. 소모는 {@code used_at IS NULL} 조건부 UPDATE 라
 * 같은 링크로 두 명이 동시에 들어와도 한 명만 통과한다.
 */
@Service
@RequiredArgsConstructor
public class ChallengeInvitationService {

    private final ChallengeInvitationRepository invitationRepository;
    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberService memberService;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public InvitationDtos.PreviewResponse preview(UUID viewerId, String token) {
        ChallengeInvitation invitation = liveInvitation(token);
        Challenge challenge = challengeRepository.findByIdAndDeletedAtIsNull(invitation.getChallengeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
        JoinBlockReason blockReason = memberService.previewBlockReason(viewerId, challenge, true);

        return new InvitationDtos.PreviewResponse(
                invitation.getId().toString(),
                new InvitationDtos.PreviewResponse.Challenge(
                        challenge.getId().toString(), challenge.getTitle(), challenge.getImageUrl(),
                        challenge.getCategory(), challenge.getParticipantCount(), challenge.getMaxParticipants(),
                        challenge.getMinTier() == null ? null : challenge.getMinTier().name(),
                        challenge.getStartDate().toString(), challenge.getEndDate().toString()),
                inviterNickname(invitation.getInviterId(), viewerId),
                blockReason == null,
                blockReason == null ? null : blockReason.name(),
                invitation.getExpiresAt().toString());
    }

    @Transactional
    public JoinResponse accept(UUID userId, String token) {
        ChallengeInvitation invitation = liveInvitation(token);
        // 가입이 먼저다. 토큰을 먼저 소모하면 게이트에 막힌 사람이 링크까지 잃는다.
        JoinResponse response = memberService.join(userId, invitation.getChallengeId(), true);
        if (invitationRepository.markUsed(invitation.getId(), Instant.now()) == 0) {
            // 같은 링크로 동시에 들어온 다른 사람이 먼저 소모했다. 가입은 롤백된다.
            throw new BusinessException(ErrorCode.INVITATION_EXPIRED);
        }
        return response;
    }

    /**
     * 살아 있는 초대장. 없으면 404, 만료·사용됨은 410으로 합친다 —
     * 클라이언트가 할 일이 "이 링크는 이제 못 쓴다"로 동일하고, 사용 여부를 알려주면 남의 초대 상태가 샌다.
     */
    private ChallengeInvitation liveInvitation(String token) {
        ChallengeInvitation invitation = invitationRepository.findByTokenHash(InvitationTokens.hash(token))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVITATION_NOT_FOUND));
        if (invitation.getUsedAt() != null || invitation.getExpiresAt().isBefore(Instant.now()))
            throw new BusinessException(ErrorCode.INVITATION_EXPIRED);
        return invitation;
    }

    private String inviterNickname(UUID inviterId, UUID viewerId) {
        return userRepository.findById(inviterId)
                .map(inviter -> inviter.visibleNicknameTo(viewerId))
                .orElse(null);   // 초대한 방장이 그사이 탈퇴 — 링크 자체는 여전히 유효하다
    }
}
