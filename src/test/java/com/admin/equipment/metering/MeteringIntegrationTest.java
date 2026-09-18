package com.admin.equipment.metering;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.metering.MeterDefinition;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.metering.*;
import com.admin.equipment.service.metering.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class MeteringIntegrationTest {

    @Autowired private MeterDefinitionService definitionService;
    @Autowired private MeterReadingService readingService;
    @Autowired private ReadingIngestor ingestor;
    @Autowired private MeterReplacementService replacementService;
    @Autowired private MaintenanceCompletionService completionService;
    @Autowired private MaintenanceReminderService reminderService;
    @Autowired private MeterStatusService statusService;
    @Autowired private EquipmentRepository equipmentRepo;
    @Autowired private WorkOrderRepository workOrderRepo;
    @Autowired private MeterReadingRepository readingRepo;
    @Autowired private MaintenanceBaselineRepository baselineRepo;
    @Autowired private MaintenanceReminderRepository reminderRepo;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private Equipment newEquipment(String prefix) {
        Equipment e = new Equipment();
        e.setCode(prefix + "-" + SEQ.incrementAndGet() + "-" + System.nanoTime());
        e.setName("测试设备");
        e.setLocation("测试区");
        e.setType("pump");
        e.setStatus("normal");
        return equipmentRepo.save(e);
    }

    private MeterDefinition meter(Equipment e, String metric, String name,
                                  String threshold, String lead) {
        return definitionService.create(e.getId(), metric, name, "h",
                new BigDecimal(threshold), new BigDecimal(lead), true);
    }

    private IngestResult read(Equipment e, String metric, String seq, String value) {
        return ingestor.ingestOne(e.getId(), null, metric, seq, new BigDecimal(value), null);
    }

    /** 未配置计量项的种子设备：状态为空、读数被拒但不报错，台账与手工工单照常工作。 */
    @Test
    void seedEquipmentWithoutMeterConfigKeepsWorking() {
        // EQ-1002 已配置空压机运行小时演示数据，这里选未配置的 EQ-1001
        Equipment seeded = equipmentRepo.findByCode("EQ-1001").orElseThrow();
        assertThat(statusService.statusByEquipment(seeded.getId(), false)).isEmpty();

        IngestResult r = read(seeded, "run_hours", "ctrl-1", "100");
        assertThat(r.status()).isEqualTo("rejected");
        assertThat(r.reason()).contains("未配置");

        WorkOrder wo = new WorkOrder();
        wo.setEquipmentId(seeded.getId());
        wo.setTitle("手工保养工单");
        wo.setType("maintenance");
        wo.setStatus("open");
        WorkOrder saved = workOrderRepo.save(wo);
        assertThat(saved.getId()).isNotNull();
    }

    /** 配置校验：阈值为正、提前量不超过阈值、同设备计量项唯一。 */
    @Test
    void validatesDefinition() {
        Equipment e = newEquipment("EQ-V");
        meter(e, "run_hours", "运行小时", "1000", "100");
        assertThatThrownBy(() -> meter(e, "run_hours", "重复", "1000", "100"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definitionService.create(e.getId(), "bad", "坏", "h",
                new BigDecimal("0"), BigDecimal.ZERO, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definitionService.create(e.getId(), "bad2", "坏", "h",
                new BigDecimal("100"), new BigDecimal("200"), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 去重、单调递增、提前提醒、越阈值自动开工单且不重复开。 */
    @Test
    void readingsDedupMonotonicReminderAndAutoWorkOrder() {
        Equipment e = newEquipment("EQ-C");
        MeterDefinition m = meter(e, "run_hours", "运行小时", "1000", "100");

        IngestResult first = read(e, "run_hours", "s-1", "800");
        assertThat(first.status()).isEqualTo("accepted");
        assertThat(first.reminderId()).isNull();

        // 重复来源序号
        IngestResult dup = read(e, "run_hours", "s-1", "900");
        assertThat(dup.status()).isEqualTo("duplicate");

        // 读数倒退
        IngestResult back = read(e, "run_hours", "s-0", "799");
        assertThat(back.status()).isEqualTo("rejected");
        assertThat(back.reason()).contains("倒退");

        // 进入提前量区间（剩 50）
        IngestResult soon = read(e, "run_hours", "s-2", "950");
        assertThat(soon.status()).isEqualTo("accepted");
        assertThat(soon.reminderId()).isNotNull();
        assertThat(soon.workOrderId()).isNull();

        // 同周期只提醒一次
        assertThat(read(e, "run_hours", "s-2b", "960").reminderId())
                .isEqualTo(soon.reminderId());

        // 确认提醒
        var confirmed = reminderService.confirm(soon.reminderId());
        assertThat(confirmed.getStatus()).isEqualTo("confirmed");

        // 越过阈值 → 自动开工单
        IngestResult hit = read(e, "run_hours", "s-3", "1000");
        assertThat(hit.workOrderId()).isNotNull();
        WorkOrder wo = workOrderRepo.findById(hit.workOrderId()).orElseThrow();
        assertThat(wo.getType()).isEqualTo("maintenance");
        assertThat(wo.getSourceType()).isEqualTo("metering");
        assertThat(wo.getMeterId()).isEqualTo(m.getId());
        assertThat(wo.getTriggerReadingId()).isEqualTo(hit.readingId());

        // 再次越阈值，不重复开工单
        IngestResult more = read(e, "run_hours", "s-4", "1050");
        assertThat(more.workOrderId()).isEqualTo(wo.getId());
        long autoCount = workOrderRepo.findByEquipmentIdOrderByIdDesc(e.getId()).stream()
                .filter(w -> "metering".equals(w.getSourceType())).count();
        assertThat(autoCount).isEqualTo(1);

        // 状态展示：剩余量、来源
        List<Map<String, Object>> rows = statusService.statusByEquipment(e.getId(), false);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("state")).isEqualTo("work_order_open");
        assertThat(new BigDecimal(row.get("remaining").toString())).isEqualByComparingTo("-50");
        Map<?, ?> openWorkOrder = (Map<?, ?>) row.get("openWorkOrder");
        assertThat(openWorkOrder.get("id")).isEqualTo(wo.getId());
    }

    /** 工单完成落基准；迟到的旧读数不触发下一周期；换表登记后历史不被覆盖。 */
    @Test
    void completionBaselineLateReadingsAndReplacement() {
        Equipment e = newEquipment("EQ-R");
        MeterDefinition m = meter(e, "run_hours", "运行小时", "1000", "100");

        read(e, "run_hours", "a-1", "900");
        IngestResult hit = read(e, "run_hours", "a-2", "1000");
        WorkOrder wo = workOrderRepo.findById(hit.workOrderId()).orElseThrow();

        // 完成工单 → 基准 1000，本周期提醒失效
        wo.setStatus("done");
        workOrderRepo.save(wo);
        completionService.onWorkOrderDone(wo);
        var baseline = baselineRepo.findTopByMeterIdOrderByIdDesc(m.getId()).orElseThrow();
        assertThat(baseline.getCumulativeValue()).isEqualByComparingTo("1000");
        assertThat(baseline.getCycleIndex()).isEqualTo(1);
        assertThat(reminderRepo.findByMeterIdAndCycleIndex(m.getId(), 1).orElseThrow().getStatus())
                .isEqualTo("obsolete");

        // 迟到旧读数：被单调递增挡下
        assertThat(read(e, "run_hours", "a-late", "950").status()).isEqualTo("rejected");

        // 保养后小幅增长不触发下一周期（用量 50，未进提前量）
        IngestResult small = read(e, "run_hours", "a-3", "1050");
        assertThat(small.reminderId()).isNull();
        assertThat(small.workOrderId()).isNull();

        // 旧表终值不能小于当前代表最后读数
        read(e, "run_hours", "a-4", "1100");
        assertThatThrownBy(() -> replacementService.register(m.getId(),
                new BigDecimal("5"), new BigDecimal("0"), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        // 授权换表：旧表终值 1200，新表起点 0
        var rep = replacementService.register(m.getId(),
                new BigDecimal("1200"), new BigDecimal("0"), "计数器故障换表", "张工", null);
        assertThat(rep.getOffsetValue()).isEqualByComparingTo("1200");

        // 历史读数累计值未被覆盖
        assertThat(readingRepo.findByMeterIdAndSourceSeq(m.getId(), "a-4").orElseThrow()
                .getCumulativeValue()).isEqualByComparingTo("1100");

        // 新表读数按偏移折算：raw 300 → cumulative 1500，本周期用量 500，不触发
        IngestResult nr = read(e, "run_hours", "b-0", "300");
        assertThat(nr.cumulativeValue()).isEqualByComparingTo("1500");
        assertThat(nr.reminderId()).isNull();

        // 新代表有读数后，旧表终值（新表终示数）不能小于新表最后读数
        assertThatThrownBy(() -> replacementService.register(m.getId(),
                new BigDecimal("100"), new BigDecimal("0"), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        // 新表继续走到第二周期越阈值（cumulative ≥ 2000），自动开第二张工单
        IngestResult hit2 = read(e, "run_hours", "b-1", "800");
        assertThat(hit2.cumulativeValue()).isEqualByComparingTo("2000");
        assertThat(hit2.workOrderId()).isNotNull();
        assertThat(hit2.workOrderId()).isNotEqualTo(wo.getId());

        Map<String, Object> row = statusService.statusByEquipment(e.getId(), false).get(0);
        assertThat(row.get("cycleIndex")).isEqualTo(2);
        assertThat(new BigDecimal(((Map<?, ?>) row.get("lastReplacement")).get("offsetValue").toString()))
                .isEqualByComparingTo("1200");
    }

    /** 工单重启撤回基准并恢复提醒，周期计算保持一致。 */
    @Test
    void reopenWithdrawsBaseline() {
        Equipment e = newEquipment("EQ-O");
        MeterDefinition m = meter(e, "run_hours", "运行小时", "1000", "100");

        read(e, "run_hours", "o-1", "1000");
        WorkOrder wo = workOrderRepo.findOpenMaintenanceForMeter(
                e.getId(), m.getId(), List.of("open", "in_progress")).get(0);
        wo.setStatus("done");
        workOrderRepo.save(wo);
        completionService.onWorkOrderDone(wo);
        assertThat(baselineRepo.findTopByMeterIdOrderByIdDesc(m.getId())).isPresent();

        wo.setStatus("open");
        workOrderRepo.save(wo);
        completionService.onWorkOrderReopened(wo);
        assertThat(baselineRepo.findTopByMeterIdOrderByIdDesc(m.getId())).isEmpty();
        var reminder = reminderRepo.findByMeterIdAndCycleIndex(m.getId(), 1).orElseThrow();
        assertThat(reminder.getStatus()).isEqualTo("pending");

        // 重启后读数不产生第二张工单（原工单仍在）
        IngestResult r = read(e, "run_hours", "o-2", "1080");
        assertThat(r.workOrderId()).isEqualTo(wo.getId());
    }

    /** 批量接口逐条返回接纳/重复/拒绝原因，一条失败不影响其他条。 */
    @Test
    void batchReportsPerItemOutcome() {
        Equipment e = newEquipment("EQ-B");
        meter(e, "run_hours", "运行小时", "1000", "0");

        var items = new ArrayList<MeterReadingService.ReadingItem>();
        items.add(new MeterReadingService.ReadingItem(e.getId(), null, "run_hours", "b-1", new BigDecimal("10"), null));
        items.add(new MeterReadingService.ReadingItem(e.getId(), null, "run_hours", "b-1", new BigDecimal("10"), null));
        items.add(new MeterReadingService.ReadingItem(e.getId(), null, "run_hours", "b-2", new BigDecimal("5"), null));
        items.add(new MeterReadingService.ReadingItem(e.getId(), null, "missing_metric", "b-3", new BigDecimal("1"), null));
        items.add(new MeterReadingService.ReadingItem(999999L, null, "run_hours", "b-4", new BigDecimal("1"), null));
        items.add(null);
        MeterReadingService.BatchResult result = readingService.ingestBatch(items);
        assertThat(result.total()).isEqualTo(6);
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.duplicate()).isEqualTo(1);
        assertThat(result.rejected()).isEqualTo(4);
        assertThat(result.results().get(0).status()).isEqualTo("accepted");
        assertThat(result.results().get(1).status()).isEqualTo("duplicate");
        assertThat(result.results().get(2).reason()).contains("倒退");
        assertThat(result.results().get(3).reason()).contains("未配置");
        assertThat(result.results().get(4).reason()).contains("设备不存在");
    }

    /**
     * 并发上报：相同来源序号只接纳一次；越阈值读数（等值不同序号）并发到达时只开一张工单。
     * 不投递乱序递减排号，因为读数被并发抢先提交后按单调递增规则拒绝本就是预期行为。
     */
    @Test
    void concurrentIngestStaysConsistent() throws Exception {
        Equipment e = newEquipment("EQ-X");
        MeterDefinition m = meter(e, "run_hours", "运行小时", "1000", "100");

        int threads = 8;
        int duplicateSeqs = 10; // 所有线程重复发送同一批序号
        int overThresholdReads = 20; // 20 个不同序号、同为 1000 的读数竞争开工单
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int s = 1; s <= duplicateSeqs; s++) {
                    ingestor.ingestOne(e.getId(), null, "run_hours",
                            "x-" + s, new BigDecimal(s * 100), null);
                }
                for (int k = 1; k <= overThresholdReads; k++) {
                    ingestor.ingestOne(e.getId(), null, "run_hours",
                            "hit-" + k, new BigDecimal(1000), null);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(readingRepo.findByMeterIdOrderByIdDesc(m.getId()))
                .hasSize(duplicateSeqs + overThresholdReads);
        long openAuto = workOrderRepo.findOpenMaintenanceForMeter(
                e.getId(), m.getId(), List.of("open", "in_progress"))
                .stream().filter(w -> "metering".equals(w.getSourceType())).count();
        assertThat(openAuto).isEqualTo(1);
        assertThat(reminderRepo.findByMeterIdAndCycleIndex(m.getId(), 1)).isPresent();
    }

    /** 同一设备可配置多个计量项，互不干扰；其他计量项工单不阻止本项开工单。 */
    @Test
    void multipleMetricsPerEquipmentAreIndependent() {
        Equipment e = newEquipment("EQ-M");
        MeterDefinition hours = meter(e, "run_hours", "运行小时", "1000", "0");
        meter(e, "starts", "启动次数", "500", "0");

        read(e, "starts", "st-1", "500");
        WorkOrder startsWo = workOrderRepo.findOpenMaintenanceForMeter(
                e.getId(), hours.getId(), List.of("open", "in_progress"))
                .stream().filter(w -> "starts".equals(w.getMetric())).findFirst().orElse(null);
        assertThat(startsWo).isNull(); // starts 的工单不挡 hours

        IngestResult hit = read(e, "run_hours", "h-1", "1000");
        assertThat(hit.workOrderId()).isNotNull();
    }
}
