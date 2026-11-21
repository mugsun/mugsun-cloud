package com.mugsun.cloud.gis;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * gis 服务启动入口。
 *
 * <p>注解和 mugsun-boot 的 {@code MugsunApplication} 保持一致，区别有两处：扫描范围多了微服务版自己的包；
 * 没有 {@code @EnableFileStorage}，文件存储只在 system 服务里。boot 的启动类改了注解时，这里要跟着改。
 *
 * <p>{@code com.mugsun.boot.gis} 和 boot 一样不走根扫描：由 {@code GisAutoConfiguration} 在 {@code mugsun.gis.enabled}
 * 为开时装配，开关的规则和单体版相同。
 */
@SpringBootApplication
@ComponentScan(
	basePackages = {"com.mugsun.boot", "com.mugsun.cloud.common", "com.mugsun.cloud.gis"},
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
public class GisApplication {

	public static void main(String[] args) {
		SpringApplication.run(GisApplication.class, args);
	}
}
