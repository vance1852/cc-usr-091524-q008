package com.admin.equipment.service.meter;

/** 计量业务校验异常，携带 HTTP 状态码。 */
public class MeterValidationException extends RuntimeException {

    private final int httpStatus;

    public MeterValidationException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
