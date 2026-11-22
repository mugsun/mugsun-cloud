package com.mugsun.cloud.api.resource;

import com.mugsun.boot.common.sms.SmsGateway;
import com.mugsun.cloud.common.inner.InnerClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ResourceApiAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ResourceApiAutoConfiguration.class))
		.withBean(InnerClient.class, () -> mock(InnerClient.class));

	/** system 这样的服务：自己没有短信的实现，拿到的是转给文件和短信服务的那一个 */
	@Test
	void forwardsWhenTheServiceHasNoSmsOfItsOwn() {
		runner.run(context -> assertThat(context.getBean(SmsGateway.class)).isInstanceOf(RemoteSmsGateway.class));
	}

	/** 文件和短信服务自己：已经有 SmsService，转发的不注册，不然就成了自己调自己 */
	@Test
	void standsAsideInTheServiceThatSendsSmsItself() {
		SmsGateway local = mock(SmsGateway.class);
		runner.withBean("smsService", SmsGateway.class, () -> local).run(context -> {
			assertThat(context.getBeansOfType(SmsGateway.class)).hasSize(1);
			assertThat(context.getBean(SmsGateway.class)).isSameAs(local);
		});
	}

	@Test
	void readsTheServiceNameFromSettings() {
		runner.withPropertyValues("mugsun.cloud.resource.service-id=bad name").run(context ->
			assertThat(context).hasFailed().getFailure().rootCause()
				.hasMessageContaining("mugsun.cloud.resource.service-id"));
	}

	/** 自动配置要登记在这个文件里才会生效：漏了的话编译和别的测试都不报错，system 启动时才发现没有短信的实现 */
	@Test
	void isRegisteredAsAnAutoConfiguration() throws IOException {
		String file = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
		boolean found = false;
		var urls = getClass().getClassLoader().getResources(file);
		while (urls.hasMoreElements()) {
			try (InputStream in = urls.nextElement().openStream()) {
				found |= new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
					.anyMatch(line -> line.trim().equals(ResourceApiAutoConfiguration.class.getName()));
			}
		}
		assertThat(found).as("%s 里要有 %s", file, ResourceApiAutoConfiguration.class.getName()).isTrue();
	}
}
