package com.ruleup.ruleup_backend.profile;

import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.profile.dto.PublicProfileResponse;
import com.ruleup.ruleup_backend.report.BlockService;
import com.ruleup.ruleup_backend.score.repository.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.user.UserRepository;
import com.ruleup.ruleup_backend.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PublicProfileService {
    private final UserRepository userRepository;
    private final UserScoreSummaryRepository scoreRepository;
    private final BlockService blockService;
    private final com.ruleup.ruleup_backend.me.service.MeChallengeCounts counts;

    @Transactional(readOnly = true)
    public PublicProfileResponse get(UUID viewerId, UUID targetId) {
        User user = userRepository.findById(targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        boolean withdrawn = user.isWithdrawn();
        boolean blocked = blockService.isUserBlocked(viewerId, targetId);
        Tier tier = scoreRepository.findById(targetId).map(s -> s.getDisplayTier()).orElse(Tier.UNRANKED);
        long completed=counts.completedSuccessfully(targetId);
        return new PublicProfileResponse(targetId.toString(), withdrawn ? null
                        : blocked ? user.deriveTempNickname() : user.visibleNicknameTo(viewerId),
                withdrawn || blocked ? null : user.visibleProfileImageTo(viewerId), tier.name(),
                completed, withdrawn,
                blocked);
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
