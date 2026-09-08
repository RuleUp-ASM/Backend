package com.ruleup.ruleup_backend.notification.announcement;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {

    /** 팬아웃 대기분 — {@code ix_announcements_pending (fanned_out_at, id)} 를 탄다. */
    List<Announcement> findByFannedOutAtIsNullOrderByIdAsc(Limit limit);
}
