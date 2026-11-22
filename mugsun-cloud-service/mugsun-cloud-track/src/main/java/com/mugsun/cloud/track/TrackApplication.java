package com.mugsun.cloud.track;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import org.dromara.x.file.storage.spring.EnableFileStorage;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import tech.powerjob.worker.autoconfigure.PowerJobAutoConfiguration;

/**
 * track 服务启动入口。
 *
 * <p>注解和 mugsun-boot 的 {@code MugsunApplication} 保持一致，区别有两处：扫描范围多了微服务版自己的包；去掉了 PowerJob 执行器的自动配置。
 * {@code @EnableFileStorage} 要留着：会话回放、接口报文和 sourcemap 都存在文件存储里。boot 的启动类改了注解时，这里要跟着改。
 *
 * <p>PowerJob 执行器只在定时任务服务（mugsun-job）上启动。调度中心派任务时不分服务，同一个应用名下的执行器都可能被派到：
 * 这里也启动的话，定时任务服务的任务会被派到这里，而这里没有对应的处理器。用 {@code exclude} 关而不用配置项 {@code powerjob.worker.enabled} 关，
 * 是因为 {@code application-prod.yml} 按环境变量 {@code POWERJOB_ENABLED} 设置这个配置项，它的优先级比这个服务的
 * {@code application.yml} 高，各服务共用一份环境变量时关不住。埋点的聚合、清理和分区维护由服务内的定时任务执行，不依赖 PowerJob，
 * 说明见 docs/deployment.md。
 *
 * <p>{@code com.mugsun.boot.track} 和 boot 一样不走根扫描：由 {@code TrackAutoConfiguration} 在 {@code mugsun.track.enabled}
 * 为开时装配，开关的规则和单体版相同。
 */
@SpringBootApplication(exclude = PowerJobAutoConfiguration.class)
@ComponentScan(
	basePackages = {"com.mugsun.boot", "com.mugsun.cloud.common", "com.mugsun.cloud.track"},
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
public class TrackApplication {

	public static void main(String[] args) {
		SpringApplication.run(TrackApplication.class, args);
	}
}
