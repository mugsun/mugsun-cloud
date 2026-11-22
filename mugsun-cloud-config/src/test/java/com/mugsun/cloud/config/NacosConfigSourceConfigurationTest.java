package com.mugsun.cloud.config;

import com.alibaba.cloud.nacos.NacosServiceAutoConfiguration;
import com.alibaba.cloud.nacos.discovery.NacosDiscoveryAutoConfiguration;
import com.alibaba.cloud.nacos.util.UtilIPv6AutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.commons.util.UtilAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关和各服务都不扫描这个包，{@link NacosConfigSource} 靠自动配置生效。登记漏了的话别的测试照样通过（它们都不启用 Nacos），
 * 线上却是写在 Nacos 里的配置一份都不读，也没有任何报错，所以单独守着。
 */
class NacosConfigSourceConfigurationTest {

	/** Nacos 自己的几个自动配置照真的来。不会去连 Nacos：没有登记要读的配置，也没有人查服务 */
	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(UtilAutoConfiguration.class, UtilIPv6AutoConfiguration.class,
			NacosServiceAutoConfiguration.class, NacosDiscoveryAutoConfiguration.class, NacosConfigSourceConfiguration.class))
		.withPropertyValues("spring.cloud.nacos.discovery.server-addr=127.0.0.1:1",
			"spring.cloud.nacos.discovery.ip=127.0.0.1");

	@Test
	void listedAmongTheAutoConfigurations() {
		assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
			.contains(NacosConfigSourceConfiguration.class.getName());
	}

	@Test
	void presentWheneverNacosIsEnabled() {
		runner.run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(NacosConfigSource.class);
			assertThat(context.getBean(NacosConfigSource.class).isRunning()).isTrue();
		});
	}

	/** 关掉 Nacos 的开关有两个，用哪一个关都不创建，也不让启动失败 */
	@ParameterizedTest
	@ValueSource(strings = {"spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false"})
	void absentWhenNacosIsSwitchedOff(String off) {
		runner.withPropertyValues(off)
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(NacosConfigSource.class));
	}
}
