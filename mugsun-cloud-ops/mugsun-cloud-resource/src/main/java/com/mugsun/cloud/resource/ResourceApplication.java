package com.mugsun.cloud.resource;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import org.dromara.x.file.storage.spring.EnableFileStorage;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 文件和短信服务启动入口。
 *
 * <p>这个服务管两件事。文件：上传下载、存储配置、头像、富文本图片、反馈附件，以及本地存储直出的 {@code /file/**}。
 * 短信：短信配置，发验证码、验验证码、发通知短信。
 * 这些类是从 mugsun-boot 的核心模块里分出来的，分哪些写在 {@code scripts/sync-rules.json} 的 {@code core.services} 里。
 *
 * <p>登录发验证码、双因子、短信通知还在 system 服务里，它们经内部接口调这个服务的短信，接收的一端是 {@link SmsCodeEndpoint} 和 {@link SmsTextEndpoint}。
 *
 * <p>注解和 mugsun-boot 的 {@code MugsunApplication} 保持一致，区别只有一处：扫描范围多了微服务版自己的包。
 * {@code @EnableFileStorage} 要留着：文件存储就在这个服务里。boot 的启动类改了注解时，这里要跟着改。
 */
@SpringBootApplication
@ComponentScan(
	basePackages = {"com.mugsun.boot", "com.mugsun.cloud.common", "com.mugsun.cloud.resource"},
	excludeFilters = {
		@ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.mugsun\\.boot\\.gis\\..*"),
		@ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.mugsun\\.boot\\.track\\..*"),
		@ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.mugsun\\.boot\\.ai\\..*")
	}
)
@EnableAsync
@EnableScheduling
@EnableFileStorage
@EnableMethodCache(basePackages = "com.mugsun.boot")
@MapperScan("com.mugsun.boot.**.mapper")
public class ResourceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ResourceApplication.class, args);
	}
}
