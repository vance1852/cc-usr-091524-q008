package com.admin.equipment.model.meter;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 授权换表登记。旧表读数流水原样保留，不覆盖历史累计值。
 * 新表段的历史累计值 = carryOverValue + (表显读数 - newStartValue)，
 * 其中 carryOverValue 是旧表终值 oldFinalValue 对应的历史累计值。
 */
@Entity
@Table(name = "meter_replacements",
        indexes = @Index(name = "idx_meter_repl_equip_metric", columnList = "equipment_id,metric"))
public class MeterReplacement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(nullable = false, length = 32)
    private String metric;

    // 旧表终值（旧表表显读数）
    @Column(name = "old_final_value", nullable = false, precision = 18, scale = 3)
    private BigDecimal oldFinalValue;

    // 新表起点（新表表显读数，常见为 0）
    @Column(name = "new_start_value", nullable = false, precision = 18, scale = 3)
    private BigDecimal newStartValue;

    // 旧表终值对应的历史累计值，即带入新表段的结转量
    @Column(name = "carry_over_value", nullable = false, precision = 18, scale = 3)
    private BigDecimal carryOverValue;

    // 授权/登记人（登录用户名）
    @Column(name = "authorized_by", length = 64)
    private String authorizedBy;

    @Column(length = 512)
    private String reason = "";

    @Column(name = "replaced_at")
    private LocalDateTime replacedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public BigDecimal getOldFinalValue() { return oldFinalValue; }
    public void setOldFinalValue(BigDecimal oldFinalValue) { this.oldFinalValue = oldFinalValue; }
    public BigDecimal getNewStartValue() { return newStartValue; }
    public void setNewStartValue(BigDecimal newStartValue) { this.newStartValue = newStartValue; }
    public BigDecimal getCarryOverValue() { return carryOverValue; }
    public void setCarryOverValue(BigDecimal carryOverValue) { this.carryOverValue = carryOverValue; }
    public String getAuthorizedBy() { return authorizedBy; }
    public void setAuthorizedBy(String authorizedBy) { this.authorizedBy = authorizedBy; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason == null ? "" : reason; }
    public LocalDateTime getReplacedAt() { return replacedAt; }
    public void setReplacedAt(LocalDateTime replacedAt) { this.replacedAt = replacedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
