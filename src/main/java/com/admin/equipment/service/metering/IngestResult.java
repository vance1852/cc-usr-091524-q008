package com.admin.equipment.service.metering;

import java.math.BigDecimal;

/** 批量上报中单条读数的处理结果。 */
public record IngestResult(String equipmentCode, Long equipmentId, String metric, String sourceSeq,
                           String status, String reason,
                           Long readingId, BigDecimal rawValue, BigDecimal cumulativeValue,
                           Long reminderId, Long workOrderId) {

    public static IngestResult rejected(String code, Long equipmentId, String metric, String sourceSeq, String reason) {
        return new IngestResult(code, equipmentId, metric, sourceSeq, "rejected", reason,
                null, null, null, null, null);
    }
}
