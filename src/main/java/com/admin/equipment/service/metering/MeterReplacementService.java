package com.admin.equipment.service.metering;

import com.admin.equipment.model.metering.MeterDefinition;
import com.admin.equipment.model.metering.MeterReading;
import com.admin.equipment.model.metering.MeterReplacement;
import com.admin.equipment.repo.metering.MeterDefinitionRepository;
import com.admin.equipment.repo.metering.MeterReadingRepository;
import com.admin.equipment.repo.metering.MeterReplacementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class MeterReplacementService {

    private final MeterDefinitionRepository meterRepo;
    private final MeterReadingRepository readingRepo;
    private final MeterReplacementRepository replacementRepo;

    public MeterReplacementService(MeterDefinitionRepository meterRepo,
                                   MeterReadingRepository readingRepo,
                                   MeterReplacementRepository replacementRepo) {
        this.meterRepo = meterRepo;
        this.readingRepo = readingRepo;
        this.replacementRepo = replacementRepo;
    }

    public List<MeterReplacement> listByEquipment(Long equipmentId) {
        return replacementRepo.findByEquipmentIdOrderByIdDesc(equipmentId);
    }

    public List<MeterReplacement> listByMeter(Long meterId) {
        return replacementRepo.findByMeterIdOrderByIdAsc(meterId);
    }

    /**
     * 登记授权换表。行锁串行化；历史读数与历史累计值一律不改写，
     * 仅记录旧表终值、新表起点与折算偏移：新表 cumulative = raw + offset。
     */
    @Transactional
    public MeterReplacement register(Long meterId, BigDecimal oldFinalValue, BigDecimal newStartValue,
                                     String reason, String operator, LocalDateTime replacedAt) {
        if (oldFinalValue == null || newStartValue == null) {
            throw new IllegalArgumentException("旧表终值与新表起点必填");
        }
        if (oldFinalValue.signum() < 0 || newStartValue.signum() < 0) {
            throw new IllegalArgumentException("表底数值不能为负");
        }
        MeterDefinition meter = meterRepo.findByIdForUpdate(meterId)
                .orElseThrow(() -> new IllegalArgumentException("计量项不存在"));

        MeterReplacement last = replacementRepo.findTopByMeterIdOrderByIdDesc(meterId).orElse(null);
        BigDecimal prevOffset = last == null ? BigDecimal.ZERO : last.getOffsetValue();
        Long currentGenerationId = last == null ? null : last.getId();

        // 旧表终值不得小于本代表最后一次上报的原始读数，否则历史累计无法闭合
        MeterReading latest = readingRepo.findTopByMeterIdOrderByIdDesc(meterId).orElse(null);
        if (latest != null
                && (currentGenerationId == null ? latest.getReplacementId() == null
                                                 : currentGenerationId.equals(latest.getReplacementId()))
                && oldFinalValue.compareTo(latest.getRawValue()) < 0) {
            throw new IllegalArgumentException(
                    "旧表终值(" + oldFinalValue + ")不能小于最后读数(" + latest.getRawValue() + ")");
        }

        BigDecimal offset = prevOffset.add(oldFinalValue).subtract(newStartValue);

        MeterReplacement r = new MeterReplacement();
        r.setEquipmentId(meter.getEquipmentId());
        r.setMeterId(meterId);
        r.setMetric(meter.getMetric());
        r.setOldFinalValue(oldFinalValue);
        r.setNewStartValue(newStartValue);
        r.setOffsetValue(offset);
        r.setReason(reason == null ? "" : reason);
        r.setOperator(operator == null ? "" : operator);
        r.setReplacedAt(replacedAt == null ? LocalDateTime.now() : replacedAt);
        return replacementRepo.save(r);
    }
}
