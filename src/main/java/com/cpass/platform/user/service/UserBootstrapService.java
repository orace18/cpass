package com.cpass.platform.user.service;

import com.cpass.platform.user.domain.UserEntity;
import com.cpass.platform.user.domain.UserRole;
import com.cpass.platform.user.infra.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class UserBootstrapService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrapService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminBootstrapProperties adminBootstrapProperties;

    public UserBootstrapService(UserRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                AdminBootstrapProperties adminBootstrapProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminBootstrapProperties = adminBootstrapProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!adminBootstrapProperties.isConfigured()) {
            log.warn("No bootstrap admin credentials configured under app.admin.*");
            return;
        }

        String email = adminBootstrapProperties.email().trim().toLowerCase();
        userRepository.findByEmail(email).orElseGet(() -> {
            UserEntity user = new UserEntity();
            user.setEmail(email);
            user.setPasswordHash(passwordEncoder.encode(adminBootstrapProperties.password()));
            user.setRole(UserRole.ADMIN);
            user.setEnabled(true);
            log.info("Bootstrap admin user created for {}", email);
            return userRepository.save(user);
        });
    }
}
