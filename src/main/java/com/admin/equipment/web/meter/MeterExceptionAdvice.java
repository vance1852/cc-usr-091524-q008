package com.admin.equipment.web.meter;

import com.admin.equipment.service.meter.MeterValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = MeterController.class)
public class MeterExceptionAdvice {

    @ExceptionHandler(MeterValidationException.class)
    public ResponseEntity<?> handle(MeterValidationException ex) {
        return ResponseEntity.status(HttpStatus.valueOf(ex.getHttpStatus()))
                .body(Map.of("detail", ex.getMessage()));
    }
}
