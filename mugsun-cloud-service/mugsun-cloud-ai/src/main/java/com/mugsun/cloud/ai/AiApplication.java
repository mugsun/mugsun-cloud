package com.mugsun.cloud.ai;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ai 服务启动入口。
 *
 * <p>注解和 mugsun-boot 的 {@code MugsunApplication} 保持一致，区别有两处：扫描范围多了微服务版自己的包；
 * 没有 {@code @EnableFileStorage}，AI 模块不用文件存储（知识库资料上传后只留抽出来的正文）。boot 的启动类改了注解时，这里要跟着改。
 *
 * <p>{@code com.mugsun.boot.ai} 和 boot 一样不走根扫描：由 {@code AiAutoConfiguration} 在 {@code mugsun.ai.enabled}
 * 为开时装配，开关的规则和单体版相同。
 *
 * <p>这个服务带着流程引擎的库，但不执行审批流：审批流在审批流服务里跑。带它是因为 AI 模块里的预审监听器和实验室的演示数据
 * 用到了它的类，说明见 pom.xml。
 */
@SpringBootApplication
@ComponentScan(
	basePackages = {"com.mugsun.boot", "com.mugsun.cloud.common", "com.mugsun.cloud.ai"},
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
public class AiApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiApplication.class, args);
	}
}
