package com.admin.equipment.model.metering;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 控制器上报的累计计量读数。
 * 以 设备 + 计量项 + 来源序号 去重；normal 读数的累计值单调递增。
 * rawValue 为表盘原始值，cumulativeValue 为跨换表折算后的全生命周期累计值。
 */
@Entity
@Table(name = "meter_readings",
        uniqueConstraints = @UniqueConstraint(name = "uk_reading_meter_source_seq",
                columnNames = {"meter_id", "source_seq"}),
        indexes = {
                @Index(name = "idx_reading_meter_id", columnList = "meter_id"),
                @Index(name = "idx_reading_equipment", columnList = "equipment_id")
        })
public class MeterReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(name = "meter_id", nullable = false)
    private Long meterId;

    @Column(nullable = false, length = 48)
    private String metric;

    // 控制器侧来源序号，同一设备同一计量项内唯一，用于幂等去重
    @Column(name = "source_seq", nullable = false, length = 64)
    private String sourceSeq;

    // 表盘原始读数
    @Column(name = "raw_value", nullable = false, precision = 20, scale = 3)
    private BigDecimal rawValue;

    // 折算换表后的全生命周期累计值（保养判定一律以此为准）
    @Column(name = "cumulative_value", nullable = false, precision = 20, scale = 3)
    private BigDecimal cumulativeValue;

    // 读数归属的换表记录；null 表示首代表
    @Column(name = "replacement_id")
    private Long replacementId;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "received_at")
    private LocalDateTime receivedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public Long getMeterId() { return meterId; }
    public void setMeterId(Long meterId) { this.meterId = meterId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public String getSourceSeq() { return sourceSeq; }
    public void setSourceSeq(String sourceSeq) { this.sourceSeq = sourceSeq; }
    public BigDecimal getRawValue() { return rawValue; }
    public void setRawValue(BigDecimal rawValue) { this.rawValue = rawValue; }
    public BigDecimal getCumulativeValue() { return cumulativeValue; }
    public void setCumulativeValue(BigDecimal cumulativeValue) { this.cumulativeValue = cumulativeValue; }
    public Long getReplacementId() { return replacementId; }
    public void setReplacementId(Long replacementId) { this.replacementId = replacementId; }
    public LocalDateTime getReadAt() { return readAt; }
    public void setReadAt(LocalDateTime readAt) { this.readAt = readAt; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
}
