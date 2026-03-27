package com.cpass.platform.client.infra;

import com.cpass.platform.client.domain.ClientEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ClientRepository extends JpaRepository<ClientEntity, UUID> {

    Optional<ClientEntity> findByName(String name);

    Page<ClientEntity> findByNameContainingIgnoreCase(String search, Pageable pageable);
}
