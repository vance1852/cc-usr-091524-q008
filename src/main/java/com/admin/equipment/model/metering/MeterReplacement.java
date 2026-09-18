package com.admin.equipment.model.metering;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 经授权的换表登记：记录旧表终值与新表起点，并保存折算偏移量。
 * 登记后历史读数与历史累计值保持不变，新表读数按 offsetValue 折算累计值：
 * cumulative = rawValue + offsetValue。
 */
@Entity
@Table(name = "meter_replacements",
        indexes = {
                @Index(name = "idx_replacement_meter", columnList = "meter_id"),
                @Index(name = "idx_replacement_equipment", columnList = "equipment_id")
        })
public class MeterReplacement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "meter_id", nullable = false)
    private Long meterId;

    @Column(nullable = false, length = 48)
    private String metric;

    // 旧表终值（旧表盘最后示数）
    @Column(name = "old_final_value", nullable = false, precision = 20, scale = 3)
    private BigDecimal oldFinalValue;

    // 新表起点（新表盘装表时示数，通常为 0）
    @Column(name = "new_start_value", nullable = false, precision = 20, scale = 3)
    private BigDecimal newStartValue;

    // 本代表的折算偏移：新表 raw=0 时对应的全生命周期累计
    // offset = 上一代表offset + oldFinalValue - newStartValue
    @Column(name = "offset_value", nullable = false, precision = 20, scale = 3)
    private BigDecimal offsetValue;

    @Column(length = 256)
    private String reason = "";

    @Column(length = 64)
    private String operator = "";

    @Column(name = "replaced_at")
    private LocalDateTime replacedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public Long getMeterId() { return meterId; }
    public void setMeterId(Long meterId) { this.meterId = meterId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public BigDecimal getOldFinalValue() { return oldFinalValue; }
    public void setOldFinalValue(BigDecimal oldFinalValue) { this.oldFinalValue = oldFinalValue; }
    public BigDecimal getNewStartValue() { return newStartValue; }
    public void setNewStartValue(BigDecimal newValue) { this.newStartValue = newValue; }
    public BigDecimal getOffsetValue() { return offsetValue; }
    public void setOffsetValue(BigDecimal offsetValue) { this.offsetValue = offsetValue; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason == null ? "" : reason; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator == null ? "" : operator; }
    public LocalDateTime getReplacedAt() { return replacedAt; }
    public void setReplacedAt(LocalDateTime replacedAt) { this.replacedAt = replacedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
