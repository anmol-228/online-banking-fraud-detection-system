package com.sepro.obfds.repository;

import com.sepro.obfds.entity.RoleName;
import com.sepro.obfds.entity.UserRole;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    Optional<UserRole> findByName(RoleName name);
}
