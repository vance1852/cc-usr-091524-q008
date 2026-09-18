package com.admin.equipment.repo;

import com.admin.equipment.model.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EquipmentRepository extends JpaRepository<Equipment, Long> {
    boolean existsByCode(String code);
    Optional<Equipment> findByCode(String code);
    long countByStatus(String status);
}
