package com.admin.equipment.service.metering;

import com.admin.equipment.model.metering.MeterReading;
import com.admin.equipment.repo.metering.MeterReadingRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量接收控制器读数：逐条调用独立事务的 ReadingIngestor，
 * 一条接纳/重复/拒绝都不影响其他条目，逐条返回原因。
 */
@Service
public class MeterReadingService {

    private final ReadingIngestor ingestor;
    private final MeterReadingRepository readingRepo;

    public MeterReadingService(ReadingIngestor ingestor, MeterReadingRepository readingRepo) {
        this.ingestor = ingestor;
        this.readingRepo = readingRepo;
    }

    public record ReadingItem(Long equipmentId, String equipmentCode, String metric,
                              String sourceSeq, BigDecimal value, String readAt) {}

    public record BatchResult(int total, int accepted, int duplicate, int rejected,
                              List<IngestResult> results) {}

    public BatchResult ingestBatch(List<ReadingItem> items) {
        List<IngestResult> results = new ArrayList<>();
        int accepted = 0, duplicate = 0, rejected = 0;
        int total = items == null ? 0 : items.size();
        int index = 0;
        if (items != null) {
            for (ReadingItem item : items) {
                index++;
                IngestResult r;
                if (item == null) {
                    r = IngestResult.rejected(null, null, null, null, "第" + index + "条为空");
                } else {
                    LocalDateTime readAt = parseTime(item.readAt());
                    if (item.readAt() != null && readAt == null) {
                        r = IngestResult.rejected(item.equipmentCode(), item.equipmentId(),
                                item.metric(), item.sourceSeq(), "readAt 时间格式无法解析（应为 ISO-8601）");
                    } else {
                        r = ingestor.ingestOne(item.equipmentId(), item.equipmentCode(),
                                item.metric(), item.sourceSeq(), item.value(), readAt);
                    }
                }
                results.add(r);
                switch (r.status()) {
                    case "accepted" -> accepted++;
                    case "duplicate" -> duplicate++;
                    default -> rejected++;
                }
            }
        }
        return new BatchResult(total, accepted, duplicate, rejected, results);
    }

    public List<MeterReading> listByEquipment(Long equipmentId) {
        return readingRepo.findByEquipmentIdOrderByIdDesc(equipmentId);
    }

    public List<MeterReading> listByMeter(Long meterId) {
        return readingRepo.findByMeterIdOrderByIdDesc(meterId);
    }

    private LocalDateTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s.trim());
        } catch (DateTimeParseException ignored) {}
        try {
            return java.time.LocalDate.parse(s.trim()).atStartOfDay();
        } catch (DateTimeParseException ignored) {}
        return null;
    }
}
