package com.mugsun.cloud.common.shutdown;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mugsun-cloud-defaults.yml 里停止等待的默认值：没配 profile 按生产处理，等 8 秒；本地开发（dev）不等。
 * 用 Spring Boot 读配置文件的那套规则来读，和服务启动时导入这个文件是同一条路。
 */
class DeregisterWaitDefaultsTest {

	private static final String WAIT = "mugsun.cloud.shutdown.deregister-wait";

	@Test
	void waitsEightSecondsUnlessInDev() {
		assertThat(load().getProperty(WAIT)).isEqualTo("8s");
		assertThat(load("prod").getProperty(WAIT)).isEqualTo("8s");
		assertThat(load("prod", "demo").getProperty(WAIT)).isEqualTo("8s");
		assertThat(load("dev").getProperty(WAIT)).isEqualTo("0s");
	}

	/** 框架自带的等待要关掉，不然停止时会等两遍 */
	@Test
	void frameworkOwnWaitIsOff() {
		String key = "spring.cloud.nacos.discovery.graceful-shutdown-wait-time";

		assertThat(load().getProperty(key)).isEqualTo("0");
		assertThat(load("dev").getProperty(key)).isEqualTo("0");
	}

	private static ConfigurableEnvironment load(String... profiles) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
			// 只读要测的这一个文件，不去找类路径上别的 application*.yml
			"spring.config.location", "optional:classpath:/no-such-directory/",
			"spring.config.import", "classpath:mugsun-cloud-defaults.yml")));
		ConfigDataEnvironmentPostProcessor.applyTo(environment, new DefaultResourceLoader(), null, profiles);
		return environment;
	}
}
