package com.admin.equipment.model.metering;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备累计计量项配置：每台设备可配置一种或多种累计计量（如运行小时、启动次数），
 * 并设定保养阈值与提前量。未配置任何计量项的设备保持原有台账/工单行为不变。
 */
@Entity
@Table(name = "meter_definitions",
        uniqueConstraints = @UniqueConstraint(name = "uk_meter_equipment_metric",
                columnNames = {"equipment_id", "metric"}))
public class MeterDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    // 计量项标识：run_hours 运行小时 / starts 启动次数 ...
    @Column(nullable = false, length = 48)
    private String metric;

    @Column(nullable = false, length = 96)
    private String name;

    @Column(length = 16)
    private String unit = "";

    // 保养阈值（本周期累计用量达到该值即越过阈值）
    @Column(name = "threshold_value", nullable = false, precision = 20, scale = 3)
    private BigDecimal threshold;

    // 提前量：距阈值还剩该量时即生成待确认提醒
    @Column(name = "lead_distance", nullable = false, precision = 20, scale = 3)
    private BigDecimal leadDistance = BigDecimal.ZERO;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit == null ? "" : unit; }
    public BigDecimal getThreshold() { return threshold; }
    public void setThreshold(BigDecimal threshold) { this.threshold = threshold; }
    public BigDecimal getLeadDistance() { return leadDistance; }
    public void setLeadDistance(BigDecimal leadDistance) {
        this.leadDistance = leadDistance == null ? BigDecimal.ZERO : leadDistance;
    }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled == null ? Boolean.TRUE : enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
