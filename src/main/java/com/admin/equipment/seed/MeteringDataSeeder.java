package com.admin.equipment.seed;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.metering.MeterDefinition;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.metering.MeterDefinitionRepository;
import com.admin.equipment.service.metering.IngestResult;
import com.admin.equipment.service.metering.ReadingIngestor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 为二号空压机（EQ-1002）演示运行计量保养：
 * 配置运行小时阈值 3000h、提前量 300h，并通过正式上报通道灌入控制器历史读数至 2850h
 * （进入提前量区间，启动后即可看到一条待确认提醒）。其他种子设备不配置计量项，
 * 保持无需计量配置也能工作的兼容行为。
 */
@Component
@Order(2)
public class MeteringDataSeeder implements CommandLineRunner {

    private final EquipmentRepository equipmentRepo;
    private final MeterDefinitionRepository meterRepo;
    private final ReadingIngestor ingestor;

    public MeteringDataSeeder(EquipmentRepository equipmentRepo,
                              MeterDefinitionRepository meterRepo,
                              ReadingIngestor ingestor) {
        this.equipmentRepo = equipmentRepo;
        this.meterRepo = meterRepo;
        this.ingestor = ingestor;
    }

    @Override
    public void run(String... args) {
        if (meterRepo.count() > 0) return;
        Equipment compressor = equipmentRepo.findByCode("EQ-1002").orElse(null);
        if (compressor == null) return;
        if (meterRepo.existsByEquipmentIdAndMetric(compressor.getId(), "run_hours")) return;

        MeterDefinition meter = new MeterDefinition();
        meter.setEquipmentId(compressor.getId());
        meter.setMetric("run_hours");
        meter.setName("累计运行小时");
        meter.setUnit("h");
        meter.setThreshold(new BigDecimal("3000"));
        meter.setLeadDistance(new BigDecimal("300"));
        meter.setEnabled(true);
        meter = meterRepo.save(meter);

        String[] seqs = {"ctrl-20260601-001", "ctrl-20260801-014", "ctrl-20260915-027"};
        String[] values = {"1000", "2100", "2850"};
        LocalDateTime[] times = {
                LocalDateTime.now().minusDays(110),
                LocalDateTime.now().minusDays(48),
                LocalDateTime.now().minusDays(3)
        };
        Long reminderId = null;
        for (int i = 0; i < seqs.length; i++) {
            IngestResult r = ingestor.ingestOne(compressor.getId(), null, "run_hours",
                    seqs[i], new BigDecimal(values[i]), times[i]);
            if (!"accepted".equals(r.status())) {
                System.out.println("空压机演示读数未接纳：" + r.reason());
                return;
            }
            reminderId = r.reminderId() != null ? r.reminderId() : reminderId;
        }
        System.out.println("已初始化空压机运行计量演示数据（2850h/阈值3000h/提前300h，"
                + (reminderId != null ? "待确认提醒#" + reminderId : "暂无提醒") + "）");
    }
}
