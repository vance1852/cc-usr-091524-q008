package com.admin.equipment.model.meter;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 控制器上报的累计读数流水，只追加、不修改、不删除。
 * displayValue 为表显读数（同一表段内非递减）；cumulativeValue 为跨换表的历史累计值，全局单调递增。
 * (equipmentId, metric, sourceSeq) 唯一，用于来源序号去重。
 */
@Entity
@Table(name = "meter_readings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_meter_reading_dedup",
                columnNames = {"equipment_id", "metric", "source_seq"}),
        indexes = {
                @Index(name = "idx_meter_reading_equip_metric", columnList = "equipment_id,metric")
        })
public class MeterReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "equipment_id", nullable = false)
    private Long equipmentId;

    @Column(nullable = false, length = 32)
    private String metric;

    // 控制器来源序号（报文序号/采集流水号），同设备同计量项内唯一
    @Column(name = "source_seq", nullable = false, length = 64)
    private String sourceSeq;

    // 所属表段：null 为原始表，否则指向登记换表后的新表段
    @Column(name = "segment_replacement_id")
    private Long segmentReplacementId;

    // 表显读数
    @Column(name = "display_value", nullable = false, precision = 18, scale = 3)
    private BigDecimal displayValue;

    // 历史累计值（含历次换表结转）
    @Column(name = "cumulative_value", nullable = false, precision = 18, scale = 3)
    private BigDecimal cumulativeValue;

    // 读数测量时间（报文携带或取接收时刻）
    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "recorded_at")
    private LocalDateTime recordedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEquipmentId() { return equipmentId; }
    public void setEquipmentId(Long equipmentId) { this.equipmentId = equipmentId; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public String getSourceSeq() { return sourceSeq; }
    public void setSourceSeq(String sourceSeq) { this.sourceSeq = sourceSeq; }
    public Long getSegmentReplacementId() { return segmentReplacementId; }
    public void setSegmentReplacementId(Long segmentReplacementId) { this.segmentReplacementId = segmentReplacementId; }
    public BigDecimal getDisplayValue() { return displayValue; }
    public void setDisplayValue(BigDecimal displayValue) { this.displayValue = displayValue; }
    public BigDecimal getCumulativeValue() { return cumulativeValue; }
    public void setCumulativeValue(BigDecimal cumulativeValue) { this.cumulativeValue = cumulativeValue; }
    public LocalDateTime getReadAt() { return readAt; }
    public void setReadAt(LocalDateTime readAt) { this.readAt = readAt; }
    public LocalDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(LocalDateTime recordedAt) { this.recordedAt = recordedAt; }
}
