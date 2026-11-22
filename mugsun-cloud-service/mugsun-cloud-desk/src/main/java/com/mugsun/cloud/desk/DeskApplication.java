package com.mugsun.cloud.desk;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 工作台服务启动入口。
 *
 * <p>这个服务管两块：通知公告（管理端的增删改查和阅读记录、「我的通知」、手机端消息中心里的公告），
 * 和登录后首页的工作台（概览统计、每个人的快捷入口）。公告的三张表只有这个服务读写。
 * 这些类是从 mugsun-boot 的核心模块里分出来的，分哪些写在 {@code scripts/sync-rules.json} 的 {@code core.services} 里。
 *
 * <p>手机端首页和全局搜索还在 system 服务里，它们要的公告（未读数、最近几条、按标题找）经内部接口来取，
 * 接收的一端是 {@link AppNoticeSummaryEndpoint} 和 {@link NoticeTitlesEndpoint}。
 *
 * <p>注解和 mugsun-boot 的 {@code MugsunApplication} 保持一致，区别有两处：扫描范围多了微服务版自己的包；
 * 没有 {@code @EnableFileStorage}，文件存储不在这个服务里。boot 的启动类改了注解时，这里要跟着改。
 */
@SpringBootApplication
@ComponentScan(
	basePackages = {"com.mugsun.boot", "com.mugsun.cloud.common", "com.mugsun.cloud.desk"},
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
public class DeskApplication {

	public static void main(String[] args) {
		SpringApplication.run(DeskApplication.class, args);
	}
}
