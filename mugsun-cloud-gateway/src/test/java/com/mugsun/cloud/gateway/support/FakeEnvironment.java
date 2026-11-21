package com.mugsun.cloud.gateway.support;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在测试里给网关设操作系统的环境变量。子类给出变量，用 {@code @ContextConfiguration(initializers = …)} 挂到测试类上。
 *
 * <p>为什么不写在 {@code @SpringBootTest(properties = …)} 里：那里的值不算环境变量。Spring Boot 只对真正的环境变量
 * 按名字去找对应的配置项（{@code MUGSUN_GATEWAY_ACCESS_LOG} 会被当成 {@code mugsun.gateway.access-log}），
 * 这一步出的问题写在 properties 里测不出来：有过一次，测试都过了，网关在容器里启动不了。
 */
public abstract class FakeEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	/** 要设的环境变量。值是空字符串表示「变量在，值是空的」 */
	protected abstract Map<String, String> variables();

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		ConfigurableEnvironment environment = context.getEnvironment();
		Map<String, Object> merged = new LinkedHashMap<>(environment.getSystemEnvironment());
		merged.putAll(variables());
		String name = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
		environment.getPropertySources().replace(name, new SystemEnvironmentPropertySource(name, merged));
	}
}
