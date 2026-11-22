package com.mugsun.cloud.job;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务服务启动入口。
 *
 * <p>这个服务管三件事：管理后台「定时任务」「调度中心」两个页面的接口（经 PowerJob 的客户端去调度中心增删改查任务）；
 * PowerJob 的执行器和平台自带的三个任务处理器（清日志、清缓存、示例）；按保留期清理日志的定时任务 {@code LogCleanJob}。
 * 这些类是从 mugsun-boot 的核心模块里分出来的，分哪些写在 {@code scripts/sync-rules.json} 的 {@code core.services} 里。
 *
 * <p>PowerJob 的执行器只在这个服务上启动：同一个应用名下的执行器，调度中心派任务时不加区分，所以处理器和执行器要在同一个服务里。
 * 「定时任务」页面上能选的处理器，就是这个服务里的处理器。
 *
 * <p>注解和 mugsun-boot 的 {@code MugsunApplication} 保持一致，区别有两处：扫描范围多了微服务版自己的包；
 * 没有 {@code @EnableFileStorage}，文件存储不在这个服务里。boot 的启动类改了注解时，这里要跟着改。
 */
@SpringBootApplication
@ComponentScan(
	basePackages = {"com.mugsun.boot", "com.mugsun.cloud.common", "com.mugsun.cloud.job"},
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
public class JobApplication {

	public static void main(String[] args) {
		SpringApplication.run(JobApplication.class, args);
	}
}
