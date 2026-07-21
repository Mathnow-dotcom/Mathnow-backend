package com.infinityisland.config;

import com.infinityisland.InfinityIslandApp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.logging.Logger;

@EnableScheduling
@Configuration
public class StartupConfig {
    String dailyReportEmail;
    private static final Logger LOGGER = Logger.getLogger(InfinityIslandApp.class.getName());

    public StartupConfig(@Value("${DAILY_REPORT_EMAIL}") String dailyReportEmail) {
        this.dailyReportEmail = dailyReportEmail;
    }

    @Bean(name ="dailyReportEmail")
    public String getDailyReportEmail() {
        return dailyReportEmail;
    }

    @Bean(name ="logger")
    public Logger getLogger() {
        return LOGGER;
    }
}