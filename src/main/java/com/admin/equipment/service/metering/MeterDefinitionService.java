package com.admin.equipment.service.metering;

import com.admin.equipment.model.metering.MeterDefinition;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.metering.MeterDefinitionRepository;
import com.admin.equipment.repo.metering.MeterReadingRepository;
import com.admin.equipment.repo.metering.MeterReplacementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class MeterDefinitionService {

    private final MeterDefinitionRepository meterRepo;
    private final MeterReadingRepository readingRepo;
    private final MeterReplacementRepository replacementRepo;
    private final EquipmentRepository equipmentRepo;

    public MeterDefinitionService(MeterDefinitionRepository meterRepo,
                                  MeterReadingRepository readingRepo,
                                  MeterReplacementRepository replacementRepo,
                                  EquipmentRepository equipmentRepo) {
        this.meterRepo = meterRepo;
        this.readingRepo = readingRepo;
        this.replacementRepo = replacementRepo;
        this.equipmentRepo = equipmentRepo;
    }

    public List<MeterDefinition> listByEquipment(Long equipmentId) {
        return meterRepo.findByEquipmentIdOrderByIdAsc(equipmentId);
    }

    public MeterDefinition getById(Long id) {
        return meterRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("计量项不存在"));
    }

    @Transactional
    public MeterDefinition create(Long equipmentId, String metric, String name, String unit,
                                  BigDecimal threshold, BigDecimal leadDistance, Boolean enabled) {
        if (equipmentId == null || !equipmentRepo.existsById(equipmentId)) {
            throw new IllegalArgumentException("设备不存在");
        }
        if (metric == null || metric.isBlank()) {
            throw new IllegalArgumentException("计量项标识必填");
        }
        metric = metric.trim();
        if (meterRepo.existsByEquipmentIdAndMetric(equipmentId, metric)) {
            throw new IllegalArgumentException("该设备已配置计量项：" + metric);
        }
        if (threshold == null || threshold.signum() <= 0) {
            throw new IllegalArgumentException("保养阈值必须为正数");
        }
        if (leadDistance != null && (leadDistance.signum() < 0 || leadDistance.compareTo(threshold) > 0)) {
            throw new IllegalArgumentException("提前量必须在 0 与阈值之间");
        }
        MeterDefinition d = new MeterDefinition();
        d.setEquipmentId(equipmentId);
        d.setMetric(metric);
        d.setName((name == null || name.isBlank()) ? metric : name.trim());
        d.setUnit(unit);
        d.setThreshold(threshold);
        d.setLeadDistance(leadDistance == null ? BigDecimal.ZERO : leadDistance);
        d.setEnabled(enabled == null || enabled);
        return meterRepo.save(d);
    }

    @Transactional
    public MeterDefinition update(Long id, String name, String unit, BigDecimal threshold,
                                  BigDecimal leadDistance, Boolean enabled) {
        MeterDefinition d = getById(id);
        if (name != null && !name.isBlank()) d.setName(name.trim());
        if (unit != null) d.setUnit(unit);
        if (threshold != null) {
            if (threshold.signum() <= 0) throw new IllegalArgumentException("保养阈值必须为正数");
            if (d.getLeadDistance() != null && d.getLeadDistance().compareTo(threshold) > 0) {
                throw new IllegalArgumentException("提前量不能大于阈值");
            }
            d.setThreshold(threshold);
        }
        if (leadDistance != null) {
            if (leadDistance.signum() < 0 || leadDistance.compareTo(d.getThreshold()) > 0) {
                throw new IllegalArgumentException("提前量必须在 0 与阈值之间");
            }
            d.setLeadDistance(leadDistance);
        }
        if (enabled != null) d.setEnabled(enabled);
        return meterRepo.save(d);
    }

    @Transactional
    public void delete(Long id) {
        MeterDefinition d = getById(id);
        if (hasAnyReading(d.getId())) {
            throw new IllegalArgumentException("该计量项已有读数历史，不能删除；可改为停用");
        }
        if (!replacementRepo.findByMeterIdOrderByIdAsc(d.getId()).isEmpty()) {
            throw new IllegalArgumentException("该计量项存在换表记录，不能删除；可改为停用");
        }
        meterRepo.delete(d);
    }

    private boolean hasAnyReading(Long meterId) {
        return !readingRepo.findByMeterIdOrderByIdDesc(meterId).isEmpty();
    }
}
