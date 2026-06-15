package com.ruleup.ruleup_backend.routine.repository;

import com.ruleup.ruleup_backend.routine.domain.UserRoutine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 사용자가 만든 루틴 저장/조회. */
public interface UserRoutineRepository extends JpaRepository<UserRoutine, Long> {

    List<UserRoutine> findByUserIdOrderByCreatedAtDesc(UUID userId);
}