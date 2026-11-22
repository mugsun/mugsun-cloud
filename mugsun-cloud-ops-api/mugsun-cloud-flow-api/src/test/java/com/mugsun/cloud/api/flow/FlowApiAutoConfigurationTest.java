package com.mugsun.cloud.api.flow;

import com.mugsun.boot.app.AppTodoReader;
import com.mugsun.cloud.common.inner.InnerClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FlowApiAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(FlowApiAutoConfiguration.class))
		.withBean(InnerClient.class, () -> mock(InnerClient.class));

	/** system 这样的服务：自己没有取待办的实现，拿到的是转给审批流服务的那一个 */
	@Test
	void forwardsWhenTheServiceHasNoTodosOfItsOwn() {
		runner.run(context -> assertThat(context.getBean(AppTodoReader.class)).isInstanceOf(RemoteAppTodoReader.class));
	}

	/** 审批流服务自己：已经有 AppTodoService，转发的不注册，不然就成了自己调自己 */
	@Test
	void standsAsideInTheFlowServiceItself() {
		AppTodoReader local = mock(AppTodoReader.class);
		runner.withBean("appTodoService", AppTodoReader.class, () -> local).run(context -> {
			assertThat(context.getBeansOfType(AppTodoReader.class)).hasSize(1);
			assertThat(context.getBean(AppTodoReader.class)).isSameAs(local);
		});
	}

	@Test
	void readsTheServiceNameFromSettings() {
		runner.withPropertyValues("mugsun.cloud.flow.service-id=bad name").run(context ->
			assertThat(context).hasFailed().getFailure().rootCause()
				.hasMessageContaining("mugsun.cloud.flow.service-id"));
	}

	/** 自动配置要登记在这个文件里才会生效：漏了的话编译和别的测试都不报错，system 启动时才发现没有取待办的实现 */
	@Test
	void isRegisteredAsAnAutoConfiguration() throws IOException {
		String file = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
		boolean found = false;
		var urls = getClass().getClassLoader().getResources(file);
		while (urls.hasMoreElements()) {
			try (InputStream in = urls.nextElement().openStream()) {
				found |= new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
					.anyMatch(line -> line.trim().equals(FlowApiAutoConfiguration.class.getName()));
			}
		}
		assertThat(found).as("%s 里要有 %s", file, FlowApiAutoConfiguration.class.getName()).isTrue();
	}
}
