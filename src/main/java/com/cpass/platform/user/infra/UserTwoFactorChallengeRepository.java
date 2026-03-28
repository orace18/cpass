package com.cpass.platform.user.infra;

import com.cpass.platform.user.domain.UserTwoFactorChallengeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserTwoFactorChallengeRepository extends JpaRepository<UserTwoFactorChallengeEntity, UUID> {

    Optional<UserTwoFactorChallengeEntity> findByIdAndConsumedAtIsNull(UUID id);

    List<UserTwoFactorChallengeEntity> findByUserIdAndConsumedAtIsNull(UUID userId);

    void deleteByExpiresAtBefore(Instant cutoff);
}
