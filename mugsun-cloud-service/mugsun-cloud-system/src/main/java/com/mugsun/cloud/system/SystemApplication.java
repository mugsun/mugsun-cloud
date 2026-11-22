package com.mugsun.cloud.system;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * system 服务启动入口。
 *
 * <p>注解和 mugsun-boot 的 {@code MugsunApplication} 保持一致（那个类不同步过来，每个服务有自己的启动类），
 * 区别有两处：扫描范围多了微服务版自己的包 {@code com.mugsun.cloud.common} 和 {@code com.mugsun.cloud.system}；
 * 没有 {@code @EnableFileStorage}，文件存储在文件和短信服务里。boot 的启动类改了注解时，这里要跟着改。
 *
 * <p>{@code gis}/{@code track}/{@code ai} 三个包照样排除：和 boot 一致，也防止以后误把这些包带进来时被根扫描直接注册。
 * 这个服务里没有 gis 和 track 的代码；ai 包里只有删租户时清 AI 密钥要用的实体和 Mapper（见 {@code CloudAiSecretPurger}），
 * 它们不是组件，Mapper 由下面的 {@code @MapperScan} 注册，不受排除规则影响。
 */
@SpringBootApplication
@ComponentScan(
	basePackages = {"com.mugsun.boot", "com.mugsun.cloud.common", "com.mugsun.cloud.system"},
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
public class SystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(SystemApplication.class, args);
	}
}
