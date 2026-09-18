package com.admin.equipment.service.meter;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.meter.MaintenanceBaseline;
import com.admin.equipment.model.meter.MeterDefinition;
import com.admin.equipment.model.meter.MeterReplacement;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.meter.MaintenanceBaselineRepository;
import com.admin.equipment.repo.meter.MeterDefinitionRepository;
import com.admin.equipment.repo.meter.MeterReadingRepository;
import com.admin.equipment.repo.meter.MeterReplacementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MeterServiceTest {

    @Autowired MeterService meterService;
    @Autowired MeterTxService txService;
    @Autowired EquipmentRepository equipmentRepo;
    @Autowired WorkOrderRepository workOrderRepo;
    @Autowired MeterDefinitionRepository defRepo;
    @Autowired MeterReadingRepository readingRepo;
    @Autowired MeterReplacementRepository replacementRepo;
    @Autowired MaintenanceBaselineRepository baselineRepo;
    @Autowired TransactionTemplate txTemplate;

    private long newEquipment(String code) {
        Equipment e = new Equipment();
        e.setCode(code);
        e.setName("测试设备" + code);
        e.setType("pump");
        e.setStatus("normal");
        return equipmentRepo.save(e).getId();
    }

    private long defMetric(long equipmentId, String metric, String threshold, String lead) {
        return meterService.saveDefinition(new MeterService.DefinitionInput(
                equipmentId, metric, "累计运行小时", "h",
                new BigDecimal(threshold), new BigDecimal(lead), true)).getId();
    }

    private MeterService.ReadingInput reading(long equipmentId, String seq, String value) {
        return new MeterService.ReadingInput(equipmentId, null, "running_hours",
                seq, new BigDecimal(value), null);
    }

    private List<WorkOrder> activeMeterOrders(long equipmentId) {
        return workOrderRepo
                .findByEquipmentIdAndTypeAndStatusInOrderByIdDesc(
                        equipmentId, "maintenance", List.of("open", "in_progress"));
    }

    @Test
    void batch_returns_accepted_duplicate_and_rejected_with_reasons() {
        long eq = newEquipment("T-EQ-01");
        defMetric(eq, "running_hours", "1000", "100");

        List<MeterService.ReadingResult> results = meterService.ingestBatch(List.of(
                reading(eq, "S1", "100"),
                reading(eq, "S1", "100"),           // 来源序号重复
                reading(eq, "S2", "90"),            // 表显回退
                new MeterService.ReadingInput(999999L, null, "running_hours",
                        "S3", new BigDecimal("200"), null), // 设备不存在
                new MeterService.ReadingInput(eq, null, "running_hours",
                        "", new BigDecimal("200"), null),    // 来源序号缺失
                reading(eq, "S4", "200")));

        assertEquals(MeterService.ACCEPTED, results.get(0).status());
        assertEquals(MeterService.DUPLICATE, results.get(1).status());
        assertTrue(results.get(1).reason().contains("重复"));
        assertEquals(MeterService.REJECTED, results.get(2).status());
        assertTrue(results.get(2).reason().contains("回退"));
        assertEquals(MeterService.REJECTED, results.get(3).status());
        assertTrue(results.get(3).reason().contains("设备不存在"));
        assertEquals(MeterService.REJECTED, results.get(4).status());
        assertTrue(results.get(4).reason().contains("来源序号"));
        assertEquals(MeterService.ACCEPTED, results.get(5).status());
    }

    @Test
    void lead_window_creates_pending_reminder_and_threshold_creates_single_work_order() {
        long eq = newEquipment("T-EQ-02");
        defMetric(eq, "running_hours", "1000", "100");

        var r1 = meterService.ingestBatch(List.of(reading(eq, "A1", "800")));
        assertEquals(MeterService.ACCEPTED, r1.get(0).status());

        // 进入提前窗口（900 起提醒）
        var r2 = meterService.ingestBatch(List.of(reading(eq, "A2", "950")));
        assertTrue(r2.get(0).reminderCreated());
        // 同周期再来一条提前区读数，不重复建提醒
        var r3 = meterService.ingestBatch(List.of(reading(eq, "A3", "960")));
        assertFalse(r3.get(0).reminderCreated());
        assertEquals(1, meterService.listReminders("pending").stream()
                .filter(x -> x.getEquipmentId() == eq).count());

        // 越过阈值自动开工单
        var r4 = meterService.ingestBatch(List.of(reading(eq, "A4", "1000")));
        assertTrue(r4.get(0).workOrderCreated());
        assertNotNull(r4.get(0).workOrderId());
        // 再越阈值不重复开工单
        var r5 = meterService.ingestBatch(List.of(reading(eq, "A5", "1020")));
        assertFalse(r5.get(0).workOrderCreated());

        List<WorkOrder> orders = activeMeterOrders(eq);
        assertEquals(1, orders.size());
        WorkOrder order = orders.get(0);
        assertEquals("maintenance", order.getType());
        assertEquals("running_hours", order.getMeterMetric());
        assertEquals(0, order.getCycleStartValue().compareTo(BigDecimal.ZERO));
        assertTrue(order.getMeterTriggerSource().contains("A4"));
        // 提醒已随工单转换
        assertEquals(0, meterService.listReminders("pending").stream()
                .filter(x -> x.getEquipmentId() == eq).count());
    }

    @Test
    void completion_records_baseline_and_late_reading_cannot_trigger_next_cycle() {
        long eq = newEquipment("T-EQ-03");
        defMetric(eq, "running_hours", "1000", "100");

        meterService.ingestBatch(List.of(reading(eq, "B1", "1000")));
        WorkOrder order = activeMeterOrders(eq).get(0);

        meterService.ingestBatch(List.of(reading(eq, "B2", "1020")));
        txService.applyWorkOrderStatus(
                workOrderRepo.findById(order.getId()).orElseThrow(), "done");

        MaintenanceBaseline baseline = baselineRepo
                .findTopByEquipmentIdAndMetricOrderByIdDesc(eq, "running_hours").orElseThrow();
        assertEquals(0, baseline.getBaselineValue().compareTo(new BigDecimal("1020")));
        // 完成即幂等，不能改回未完成
        assertThrows(MeterValidationException.class, () -> txService.applyWorkOrderStatus(
                workOrderRepo.findById(order.getId()).orElseThrow(), "open"));

        // 迟到的旧读数（累计值 <= 基准）：直接以回退形式被拒，绝不触发下一周期
        var late = meterService.ingestBatch(List.of(reading(eq, "B3", "1010")));
        assertEquals(MeterService.REJECTED, late.get(0).status());

        // 新周期按新基准计算：1020 + 1000 = 2020 才到期
        MeterService.EquipmentMeterStatus st = meterService.equipmentStatus(eq);
        MeterService.MetricStatus ms = st.metrics().get(0);
        assertEquals(0, ms.cycleStartValue().compareTo(new BigDecimal("1020")));
        assertEquals("normal", ms.state());

        meterService.ingestBatch(List.of(reading(eq, "B4", "2000")));
        assertEquals(0, activeMeterOrders(eq).size());
        var crossed = meterService.ingestBatch(List.of(reading(eq, "B5", "2020")));
        assertTrue(crossed.get(0).workOrderCreated());
        WorkOrder second = activeMeterOrders(eq).get(0);
        assertEquals(0, second.getCycleStartValue().compareTo(new BigDecimal("1020")));
    }

    @Test
    void authorized_replacement_keeps_history_and_new_meter_readings_continue_monotonic() {
        long eq = newEquipment("T-EQ-04");
        defMetric(eq, "running_hours", "10000", "0");

        meterService.ingestBatch(List.of(
                reading(eq, "M1", "9990"),
                reading(eq, "M2", "9999.5")));

        // 旧表终值与最新表显不符必须拒绝，防止丢用量
        assertThrows(MeterValidationException.class, () -> meterService.registerReplacement(
                eq, "running_hours",
                new MeterService.ReplacementInput(new BigDecimal("9000"), new BigDecimal("0"),
                        "计数器损坏换表"), "admin"));

        MeterReplacement repl = meterService.registerReplacement(eq, "running_hours",
                new MeterService.ReplacementInput(
                        new BigDecimal("9999.5"), new BigDecimal("0"), "计数器回绕换表"), "admin");
        assertEquals(0, repl.getCarryOverValue().compareTo(new BigDecimal("9999.5")));
        // 历史读数原样保留
        assertEquals(2, readingRepo.findByEquipmentIdAndMetricOrderByIdAsc(eq, "running_hours").size());

        // 新表读数 5h => 历史累计 10004.5，且不能覆盖历史
        var onNewMeter = meterService.ingestBatch(List.of(reading(eq, "M3", "5")));
        assertEquals(MeterService.ACCEPTED, onNewMeter.get(0).status());
        assertEquals(0, onNewMeter.get(0).cumulativeValue().compareTo(new BigDecimal("10004.5")));

        // 新表表显必须从新表起点起单调
        var backwards = meterService.ingestBatch(List.of(reading(eq, "M4", "4")));
        assertEquals(MeterService.REJECTED, backwards.get(0).status());
        // 历史累计仍单调
        var same = meterService.ingestBatch(List.of(reading(eq, "M5", "5")));
        assertEquals(MeterService.REJECTED, same.get(0).status());

        MeterService.MetricStatus ms = meterService.equipmentStatus(eq).metrics().get(0);
        assertEquals(0, ms.latestCumulativeValue().compareTo(new BigDecimal("10004.5")));
        assertEquals(repl.getId(), ms.segmentReplacementId());
    }

    @Test
    void concurrent_crossing_reports_create_exactly_one_work_order() throws Exception {
        long eq = newEquipment("T-EQ-05");
        defMetric(eq, "running_hours", "100", "0");
        meterService.ingestBatch(List.of(reading(eq, "C0", "99")));

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            String seq = "C" + (i + 1);
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                var r = txService.ingestGroup(eq, "running_hours",
                        List.of(new MeterService.IndexedReading(1,
                                new MeterService.ReadingInput(eq, null, "running_hours",
                                        seq, new BigDecimal("100"), null))));
                if (MeterService.ACCEPTED.equals(r.get(0).status())) accepted.incrementAndGet();
                if (MeterService.REJECTED.equals(r.get(0).status())) rejected.incrementAndGet();
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, accepted.get());
        assertEquals(threads - 1, rejected.get());
        assertEquals(1, activeMeterOrders(eq).size());
    }

    @Test
    void equipment_without_meter_config_keeps_working() {
        long eq = newEquipment("T-EQ-06");
        MeterService.EquipmentMeterStatus st = meterService.equipmentStatus(eq);
        assertNotNull(st.equipment());
        assertTrue(st.metrics().isEmpty());
        // 无配置上报被逐条拒绝，不影响设备台账/工单
        var r = meterService.ingestBatch(List.of(reading(eq, "X1", "1")));
        assertEquals(MeterService.REJECTED, r.get(0).status());
        assertTrue(equipmentRepo.findById(eq).isPresent());
    }

    @Test
    void definition_validation_rejects_bad_threshold_and_lead() {
        long eq = newEquipment("T-EQ-07");
        assertThrows(MeterValidationException.class, () -> meterService.saveDefinition(
                new MeterService.DefinitionInput(eq, "running_hours", "n", "h",
                        BigDecimal.ZERO, BigDecimal.ZERO, true)));
        assertThrows(MeterValidationException.class, () -> meterService.saveDefinition(
                new MeterService.DefinitionInput(eq, "running_hours", "n", "h",
                        new BigDecimal("100"), new BigDecimal("100"), true)));
        assertThrows(MeterValidationException.class, () -> meterService.saveDefinition(
                new MeterService.DefinitionInput(eq, "Bad Metric", "n", "h",
                        new BigDecimal("100"), new BigDecimal("10"), true)));
    }
}
