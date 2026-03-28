package com.cpass.platform.email.infra;

import com.cpass.platform.email.domain.GlobalEmailConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface GlobalEmailConfigRepository extends JpaRepository<GlobalEmailConfigEntity, UUID> {
}
