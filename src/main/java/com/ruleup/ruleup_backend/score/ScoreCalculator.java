package com.ruleup.ruleup_backend.score;

import com.ruleup.ruleup_backend.score.domain.*;
import java.time.Instant;
import java.util.*;

/** Deterministic integer calculation, independent of persistence and arrival order. */
public final class ScoreCalculator {
    public record Calculated(ScoreInput input, int raw, int limited, int applied, int balance,
                             Tier actual, Tier display, ScoreLedgerReason reason) {}
    public record Projection(int score, Tier actual, Tier display, List<Calculated> results, Map<UUID,Cycle> cycles) {}
    public static final Comparator<ScoreInput> ORDER=Comparator.comparing(ScoreInput::effectiveAt)
            .thenComparing(ScoreKeys::order, Arrays::compareUnsigned);
    public static final class Cycle {
        public final ScoreInput.CycleSpec spec;
        public Tier tier;
        public int success,miss,raw,limited,successStreak,failureStreak;
        public CycleResult result;
        public Instant closedAt;
        public long version;
        public final Map<java.time.LocalDate,String> days=new HashMap<>();
        Cycle(ScoreInput.CycleSpec s) { spec=s; }
    }
    public static Projection replay(List<ScoreInput> input) { return replay(input,0,Tier.BRONZE); }
    public static Projection replay(List<ScoreInput> input, int baselineScore, Tier baselineDisplay) {
        List<ScoreInput> sorted=input.stream().sorted(ORDER).toList();
        Map<UUID,Cycle> cycles=new HashMap<>();
        for(var i:sorted) if(i.cycle()!=null && "AUTO".equals(i.authType())) {
            Cycle old=cycles.putIfAbsent(i.cycle().cycleId(),new Cycle(i.cycle()));
            if(old!=null && !old.spec.equals(i.cycle())) throw new IllegalArgumentException("SCORE_CYCLE_SNAPSHOT_CONFLICT");
        }
        List<Cycle> starts=cycles.values().stream().sorted(Comparator.comparing(c->c.spec.startsAt())).toList();
        int startIndex=0,score=baselineScore;Tier display=baselineDisplay;
        List<Calculated> results=new ArrayList<>();
        for(int at=0;at<sorted.size();) {
            Instant effective=sorted.get(at).effectiveAt();
            while(startIndex<starts.size() && !starts.get(startIndex).spec.startsAt().isAfter(effective)) {
                starts.get(startIndex++).tier=TierBands.of(score);
            }
            int end=at;while(end<sorted.size() && sorted.get(end).effectiveAt().equals(effective))end++;
            int outputStart=results.size();
            for(int p=at;p<end;p++) {
                ScoreInput i=sorted.get(p); validate(i);
                if(i.kind()!=ScoreInput.Kind.SIGNUP && !"AUTO".equals(i.authType())) continue;
                Cycle c=i.cycle()==null?null:cycles.get(i.cycle().cycleId());
                int raw=0,limited=0,applied;ScoreLedgerReason reason;
                if(i.kind()==ScoreInput.Kind.SIGNUP) { raw=10;limited=10;reason=ScoreLedgerReason.SIGNUP; }
                else if(i.kind()==ScoreInput.Kind.INCIDENT) {
                    raw=i.confirmedDelta();limited=raw;reason=ScoreLedgerReason.INCIDENT;
                } else if(i.kind()==ScoreInput.Kind.DAILY) {
                    if(c.closedAt!=null) throw new IllegalArgumentException("SCORE_DAILY_AFTER_CLOSE");
                    if(c.days.putIfAbsent(i.targetDate(),i.judgement())!=null) throw new IllegalArgumentException("SCORE_DUPLICATE_DATE");
                    int success=(int)c.days.values().stream().filter("SUCCESS"::equals).count();
                    success=Math.min(c.spec.target(),success);
                    int remaining=(int)c.spec.eligibleDates().stream().filter(d->d.isAfter(i.targetDate())).count();
                    int miss=Math.max(0,c.spec.target()-success-remaining);
                    if(c.days.values().stream().allMatch("INVALID"::equals)) miss=0;
                    raw=IntegerScore.f(TierPoints.weeklyGain(c.tier),c.spec.target(),success)-IntegerScore.f(TierPoints.weeklyGain(c.tier),c.spec.target(),c.success)
                            -IntegerScore.f(TierPoints.weeklyPenalty(c.tier),c.spec.target(),miss)+IntegerScore.f(TierPoints.weeklyPenalty(c.tier),c.spec.target(),c.miss);
                    c.success=success;c.miss=miss;reason=raw>=0?ScoreLedgerReason.DAILY_SUCCESS:ScoreLedgerReason.CONFIRMED_MISS;
                } else {
                    if(c.days.size()!=c.spec.eligibleDates().size()) throw new IllegalArgumentException("SCORE_DATES_NOT_FINAL");
                    Cycle prev=cycles.values().stream().filter(v->v.spec.challengeId().equals(c.spec.challengeId()) && v.spec.startOn().isBefore(c.spec.startOn()))
                            .max(Comparator.comparing(v->v.spec.startOn())).orElse(null);
                    if(prev!=null && prev.closedAt==null)throw new IllegalArgumentException("SCORE_PREVIOUS_CYCLE_OPEN");
                    if(prev==null && c.spec.startOn().isAfter(c.spec.firstScorableOn()))throw new IllegalArgumentException("SCORE_PREVIOUS_CYCLE_MISSING");
                    if(prev!=null && prev.spec.endOn().plusDays(1).isBefore(c.spec.startOn()) && c.spec.firstScorableOn().isBefore(c.spec.startOn()))
                        throw new IllegalArgumentException("SCORE_PREVIOUS_CYCLE_MISSING");
                    c.successStreak=prev==null?0:prev.successStreak;c.failureStreak=prev==null?0:prev.failureStreak;
                    if(c.days.values().stream().allMatch("INVALID"::equals))c.result=CycleResult.INVALID;
                    else {
                        if(c.success+c.miss!=c.spec.target())throw new IllegalArgumentException("SCORE_CLOSE_COUNTS");
                        c.result=CycleResult.of(c.success,c.spec.target());
                    }
                    switch(c.result) {
                        case SUCCESS -> { c.successStreak++;c.failureStreak=0;raw=TierPoints.streakBonus(c.tier,c.successStreak); }
                        case PARTIAL -> c.failureStreak=0;
                        case FAILURE -> { c.successStreak=0;c.failureStreak++;raw=TierPoints.failurePenalty(c.failureStreak); }
                        case INVALID -> { }
                    }
                    c.closedAt=i.effectiveAt();reason=raw>0?ScoreLedgerReason.STREAK_BONUS:raw<0?ScoreLedgerReason.STREAK_PENALTY:ScoreLedgerReason.CYCLE_CLOSED;
                }
                if(c!=null) {
                    var limit=CycleLimit.apply(raw,c.raw,c.limited,score);
                    limited=limit.limitedDelta();applied=limit.appliedDelta();score=(int)limit.scoreAfter();
                    c.raw=limit.rawCumulative();c.limited=limit.limitedCumulative();
                } else { int next=Math.max(0,Math.min(2000,score+limited));applied=next-score;score=next; }
                results.add(new Calculated(i,raw,limited,applied,score,TierBands.of(score),display,reason));
            }
            display=TierBands.displayTier(score,TierBands.of(score),display);
            if(results.size()>outputStart) {
                int last=results.size()-1;var v=results.get(last);
                results.set(last,new Calculated(v.input(),v.raw(),v.limited(),v.applied(),v.balance(),v.actual(),display,v.reason()));
            }
            at=end;
        }
        return new Projection(score,TierBands.of(score),display,results,cycles);
    }
    static void validate(ScoreInput i) {
        if(i.kind()==null || i.sourceId()==null || i.sourceId().isBlank() || i.effectiveAt()==null || i.sourceVersion()<0)throw new IllegalArgumentException("SCORE_INVALID_SOURCE");
        if(i.kind()==ScoreInput.Kind.SIGNUP)return;
        if(!Set.of("AUTO","MANUAL").contains(i.authType()))throw new IllegalArgumentException("SCORE_AUTH_SNAPSHOT_MISSING");
        if(!"AUTO".equals(i.authType()))return;
        if(i.kind()==ScoreInput.Kind.INCIDENT) {
            if(i.cycle()!=null || i.incidentType()==null || i.challengeId()==null || i.completedWeeks()<0
                    || (i.exempt() && i.incidentType()!=IncidentType.VOLUNTARY_LEAVE)
                    || i.confirmedDelta()!=(i.exempt()?0:i.incidentType().deduction(i.completedWeeks()))) throw new IllegalArgumentException("SCORE_INCIDENT_POLICY_MISMATCH");
            return;
        }
        var c=Objects.requireNonNull(i.cycle(),"SCORE_CYCLE_MISSING");
        if(!ScoreInput.POLICY.equals(c.policyVersion()))throw new IllegalArgumentException("SCORE_POLICY_VERSION_MISSING");
        if(c.target()<1||c.target()>7||!c.endOn().equals(c.startOn().plusDays(6))||c.startOn().isBefore(c.firstScorableOn())
                || c.eligibleDates().size()>7 || c.eligibleDates().isEmpty()
                || new HashSet<>(c.eligibleDates()).size()!=c.eligibleDates().size()
                || c.cycleNo()<1 || c.cycleId()==null || !c.challengeId().equals(i.challengeId()) || c.joinedAt()==null
                || c.target()>c.eligibleDates().size()
                || c.eligibleDates().stream().anyMatch(d->d.isBefore(c.startOn())||d.isAfter(c.endOn())))throw new IllegalArgumentException("SCORE_CYCLE_INVALID");
        if(i.kind()==ScoreInput.Kind.DAILY && !i.effectiveAt().equals(i.targetDate().atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant()))throw new IllegalArgumentException("SCORE_DAILY_POSITION_INVALID");
        if(i.kind()==ScoreInput.Kind.CLOSE && !i.effectiveAt().equals(c.closesAt()))throw new IllegalArgumentException("SCORE_CLOSE_POSITION_INVALID");
        if(i.kind()==ScoreInput.Kind.DAILY && (!c.eligibleDates().contains(i.targetDate())||!Set.of("SUCCESS","FAILED","INVALID").contains(i.judgement())))throw new IllegalArgumentException("SCORE_JUDGEMENT_INVALID");
    }
}
