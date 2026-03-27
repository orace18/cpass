package com.cpass.platform.client.infra;

import com.cpass.platform.client.domain.ClientEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ClientRepository extends JpaRepository<ClientEntity, UUID> {

    Optional<ClientEntity> findByName(String name);

    Optional<ClientEntity> findByEmail(String email);

    Optional<ClientEntity> findByClientId(String clientId);

    Page<ClientEntity> findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(String name, String email, Pageable pageable);
}
