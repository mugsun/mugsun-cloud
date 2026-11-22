package com.mugsun.cloud.flow;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 审批流服务启动入口。
 *
 * <p>这个服务管审批流：流程定义和设计、发起、待办、办理、流转记录，以及手机端的待办接口。流程引擎 warm-flow 只在这个服务里。
 * 这些类是从 mugsun-boot 的核心模块里分出来的，分哪些写在 {@code scripts/sync-rules.json} 的 {@code core.services} 里。
 *
 * <p>手机端首页还在 system 服务里，它要显示待办数和前几条待办，经内部接口来取，接收的一端是 {@link AppTodoEndpoint}。
 *
 * <p>注解和 mugsun-boot 的 {@code MugsunApplication} 保持一致，区别有两处：扫描范围多了微服务版自己的包；
 * 没有 {@code @EnableFileStorage}，文件存储不在这个服务里。boot 的启动类改了注解时，这里要跟着改。
 */
@SpringBootApplication
@ComponentScan(
	basePackages = {"com.mugsun.boot", "com.mugsun.cloud.common", "com.mugsun.cloud.flow"},
	excludeFilters = {
		@ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.mugsun\\.boot\\.gis\\..*"),
		@ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.mugsun\\.boot\\.track\\..*"),
		@ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.mugsun\\.boot\\.ai\\..*")
	}
)
@EnableAsync
@EnableScheduling
@EnableMethodCache(basePackages = "com.mugsun.boot")
@MapperScan("com.mugsun.boot.**.mapper")
public class FlowApplication {

	public static void main(String[] args) {
		SpringApplication.run(FlowApplication.class, args);
	}
}
