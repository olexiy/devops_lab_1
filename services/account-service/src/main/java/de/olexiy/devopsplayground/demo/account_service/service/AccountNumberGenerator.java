package de.olexiy.devopsplayground.demo.account_service.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
public class AccountNumberGenerator {

    private static final DateTimeFormatter DATE_PATTERN = DateTimeFormatter.ofPattern("yyyyMMdd");

    public String generate(Long id, LocalDate openDate) {
        return "ACC-%s-%06d".formatted(openDate.format(DATE_PATTERN), id);
    }
}
