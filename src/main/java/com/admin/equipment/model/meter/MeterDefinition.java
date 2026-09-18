package com.admin.equipment.model.meter;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备累计计量项配置：例如空压机的“累计运行小时”。
 * 每台设备可配置多个计量项，(equipmentId, metric) 唯一；同一行也作为并发上报的行锁对象。
 */
@Entity
@Table(name = "meter_definitions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_meter_def_equip_metric",
                columnNames = {"equipment_id", "metric"}))
public class MeterDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    // 计量项编码，设备内唯一，如 running_hours / starts
    @Column(nullable = false, length = 32)
    private String metric;

    // 计量项名称，如 累计运行小时
    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 16)
    private String unit = "";

    // 保养阈值：周期内累计用量达到该值即应保养
    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal threshold;

    // 提前量：达到 threshold - lead 即生成待确认提醒
    @Column(nullable = false, precision = 18, scale = 3)
    private BigDecimal lead = BigDecimal.ZERO;

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
    public BigDecimal getLead() { return lead; }
    public void setLead(BigDecimal lead) { this.lead = lead; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
