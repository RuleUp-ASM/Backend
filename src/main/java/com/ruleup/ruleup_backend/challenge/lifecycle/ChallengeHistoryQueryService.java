package com.ruleup.ruleup_backend.challenge.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruleup.ruleup_backend.challenge.dto.ChallengeDetailResponse;
import com.ruleup.ruleup_backend.common.error.BusinessException;
import com.ruleup.ruleup_backend.common.error.ErrorCode;
import com.ruleup.ruleup_backend.report.BlockService;
import com.ruleup.ruleup_backend.room.dto.RoomDtos;
import com.ruleup.ruleup_backend.score.UserScoreSummaryRepository;
import com.ruleup.ruleup_backend.score.domain.Tier;
import com.ruleup.ruleup_backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChallengeHistoryQueryService {
    private static final ObjectMapper OM = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final BlockService blocks;
    private final UserScoreSummaryRepository scores;

    public ChallengeDetailResponse detail(UUID viewer, UUID id) {
        Map<String, Object> h = history(id);
        boolean member = isMember(viewer,id);
        if (!member && (!"GROUP".equals(h.get("mode")) || !"PUBLIC".equals(h.get("visibility"))))
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        UUID ownerId = h.get("owner_id_snapshot") == null ? null : uuid((byte[])h.get("owner_id_snapshot"));
        ChallengeDetailResponse.Owner owner = ownerId == null ? null : users.findById(ownerId)
                .map(u -> new ChallengeDetailResponse.Owner(ownerId.toString(),u.visibleNicknameTo(viewer))).orElse(null);
        String myTier = scores.findById(viewer).map(s->s.getDisplayTier().name()).orElse(Tier.BRONZE.name());
        Map<?,?> config = json(h.get("verification_config"));
        boolean auto = "AUTO".equals(config.get("selectedMethod"));
        return new ChallengeDetailResponse(id.toString(), str(h,"title_snapshot"),str(h,"description_snapshot"),
                str(h,"image_snapshot"),str(h,"category"),str(h,"mode"),str(h,"visibility"),"COMPLETED",owner,
                str(h,"owner_type_snapshot"),number(h,"final_member_count",0),number(h,"capacity",null),
                number(h,"weekly_count",null),false,
                new ChallengeDetailResponse.Period(str(h,"start_date"),str(h,"end_date"),0),
                new ChallengeDetailResponse.Verification(auto?"AUTO":"MANUAL", auto?str(config,"signalSource"):"SELF_CHECK",null,permissions(config)),
                new ChallengeDetailResponse.Stats(null,null),new ChallengeDetailResponse.Gate(str(h,"min_tier"),myTier,false),
                "CHALLENGE_COMPLETED",null,"IMMEDIATE",false,false,
                viewer.equals(ownerId)?"OWNER":member?"MEMBER":"NONE",null);
    }

    public RoomDtos.RankingResponse ranking(UUID viewer, UUID id) {
        history(id);
        if (!isMember(viewer,id)) throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        var blocked=blocks.blockedUsers(viewer);
        var items=jdbc.query("SELECT user_id,rank_no,score_snapshot,success_count,participations " +
                        "FROM challenge_final_ranking WHERE challenge_id=? ORDER BY rank_no IS NULL,rank_no,user_id",(rs,i)->{
            UUID userId=uuid(rs.getBytes("user_id"));
            var user=users.findById(userId).orElse(null);
            boolean masked=blocked.contains(userId);
            return new RoomDtos.RankingResponse.Item((Integer)rs.getObject("rank_no"),
                    new RoomDtos.User(userId.toString(),user==null?null:masked?user.deriveTempNickname():user.visibleNicknameTo(viewer),
                            user==null||masked?null:user.visibleProfileImageTo(viewer),masked),rs.getBigDecimal("score_snapshot"),
                    rs.getInt("success_count"),rs.getInt("participations"));
        },bytes(id));
        var mine=items.stream().filter(i->i.user().userId().equals(viewer.toString())).findFirst().orElse(null);
        BigDecimal top=items.stream().filter(i->i.rank()!=null).map(RoomDtos.RankingResponse.Item::successRate).findFirst().orElse(null);
        var me=new RoomDtos.RankingResponse.Me(mine==null?null:mine.rank(),mine!=null&&mine.rank()!=null,
                mine==null?null:mine.successRate(),mine==null?0:mine.participations(),
                mine!=null&&mine.rank()!=null&&top!=null?top.subtract(mine.successRate()):null);
        return new RoomDtos.RankingResponse(me,items);
    }

    public boolean archived(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM challenge_history h WHERE challenge_id=? " +
                "AND NOT EXISTS(SELECT 1 FROM challenges c WHERE c.id=h.challenge_id)",Integer.class,bytes(id))>0;
    }
    private boolean isMember(UUID user,UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM challenge_member_history WHERE challenge_id=? AND user_id=?",
                Integer.class,bytes(id),bytes(user))>0;
    }
    private Map<String,Object> history(UUID id) {
        return jdbc.queryForList("SELECT * FROM challenge_history WHERE challenge_id=?",(Object)bytes(id)).stream().findFirst()
                .orElseThrow(()->new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
    }
    private static List<String> permissions(Map<?, ?> config) {
        Object raw = config.get("requiredPermissions");
        return raw instanceof List<?> values ? values.stream().map(Object::toString).toList() : List.of();
    }
    private static Map<?,?> json(Object raw) {
        if(raw==null)return Map.of();
        try{return OM.readValue(raw.toString(),Map.class);}catch(Exception e){throw new IllegalStateException("Invalid history snapshot",e);}
    }
    private static String str(Map<?,?> row,String key){return row.get(key)==null?null:row.get(key).toString();}
    private static Integer number(Map<?,?> row,String key,Integer fallback){return row.get(key)==null?fallback:((Number)row.get(key)).intValue();}
    private static byte[] bytes(UUID id){return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();}
    private static UUID uuid(byte[] bytes){var b=ByteBuffer.wrap(bytes);return new UUID(b.getLong(),b.getLong());}
}
