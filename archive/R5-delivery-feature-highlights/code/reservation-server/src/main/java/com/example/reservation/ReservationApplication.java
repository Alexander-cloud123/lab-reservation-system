package com.example.reservation;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 高校实验室预约管理系统 - 启动类
 *
 * @author reservation-team
 */
@SpringBootApplication
@MapperScan("com.example.reservation.mapper")
public class ReservationApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReservationApplication.class, args);
    }
}
