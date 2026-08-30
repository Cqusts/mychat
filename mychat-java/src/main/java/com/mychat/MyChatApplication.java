package com.mychat;

import com.mychat.entity.config.AppConfig;
import com.mychat.entity.constants.Constants;
import com.mychat.spring.ApplicationContextProvider;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import jakarta.servlet.MultipartConfigElement;

@EnableAsync
@SpringBootApplication(scanBasePackages = {"com.mychat"})
@MapperScan(basePackages = {"com.mychat.mappers"})
@EnableTransactionManagement
@EnableScheduling
public class MyChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyChatApplication.class, args);
    }

    @Bean
    @DependsOn({"applicationContextProvider"})
    MultipartConfigElement multipartConfigElement() {
        AppConfig appConfig = (AppConfig) ApplicationContextProvider.getBean("appConfig");
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setLocation(appConfig.getProjectFolder() + Constants.FILE_FOLDER_TEMP);
        return factory.createMultipartConfig();
    }
}
