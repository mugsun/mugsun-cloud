package com.mugsun.cloud.api.desk;

import com.mugsun.boot.app.AppNoticeReader;
import com.mugsun.boot.system.service.NoticeTitleFinder;
import com.mugsun.cloud.common.inner.InnerClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DeskApiAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(DeskApiAutoConfiguration.class))
		.withBean(InnerClient.class, () -> mock(InnerClient.class));

	/** system 这样的服务：自己没有取公告的实现，拿到的是转给工作台服务的那两个 */
	@Test
	void forwardsWhenTheServiceHasNoNoticesOfItsOwn() {
		runner.run(context -> {
			assertThat(context.getBean(AppNoticeReader.class)).isInstanceOf(RemoteAppNoticeReader.class);
			assertThat(context.getBean(NoticeTitleFinder.class)).isInstanceOf(RemoteNoticeTitleFinder.class);
		});
	}

	/** 工作台服务自己：已经有 AppNoticeService 和 NoticeReadService，转发的不注册，不然就成了自己调自己 */
	@Test
	void standsAsideInTheDeskServiceItself() {
		AppNoticeReader reader = mock(AppNoticeReader.class);
		NoticeTitleFinder finder = mock(NoticeTitleFinder.class);
		runner.withBean("appNoticeService", AppNoticeReader.class, () -> reader)
			.withBean("noticeReadService", NoticeTitleFinder.class, () -> finder)
			.run(context -> {
				assertThat(context.getBeansOfType(AppNoticeReader.class)).hasSize(1);
				assertThat(context.getBean(AppNoticeReader.class)).isSameAs(reader);
				assertThat(context.getBeansOfType(NoticeTitleFinder.class)).hasSize(1);
				assertThat(context.getBean(NoticeTitleFinder.class)).isSameAs(finder);
			});
	}

	/** 两个各看各的接口：只有一个有本地的实现时，另一个照样转发 */
	@Test
	void eachForwarderLooksOnlyAtItsOwnInterface() {
		NoticeTitleFinder finder = mock(NoticeTitleFinder.class);
		runner.withBean("noticeReadService", NoticeTitleFinder.class, () -> finder).run(context -> {
			assertThat(context.getBean(NoticeTitleFinder.class)).isSameAs(finder);
			assertThat(context.getBean(AppNoticeReader.class)).isInstanceOf(RemoteAppNoticeReader.class);
		});
	}

	@Test
	void readsTheServiceNameFromSettings() {
		runner.withPropertyValues("mugsun.cloud.desk.service-id=bad name").run(context ->
			assertThat(context).hasFailed().getFailure().rootCause()
				.hasMessageContaining("mugsun.cloud.desk.service-id"));
	}

	/** 自动配置要登记在这个文件里才会生效：漏了的话编译和别的测试都不报错，system 启动时才发现没有取公告的实现 */
	@Test
	void isRegisteredAsAnAutoConfiguration() throws IOException {
		String file = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
		boolean found = false;
		var urls = getClass().getClassLoader().getResources(file);
		while (urls.hasMoreElements()) {
			try (InputStream in = urls.nextElement().openStream()) {
				found |= new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
					.anyMatch(line -> line.trim().equals(DeskApiAutoConfiguration.class.getName()));
			}
		}
		assertThat(found).as("%s 里要有 %s", file, DeskApiAutoConfiguration.class.getName()).isTrue();
	}
}
