package com.mugsun.cloud.api.desk;

import com.mugsun.boot.app.AppNoticeReader;
import com.mugsun.boot.system.service.NoticeTitleFinder;
import com.mugsun.cloud.common.inner.InnerClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 依赖了这个模块的服务自动得到的 Bean：服务自己没有取公告的实现时，用转给工作台服务的那两个。
 *
 * <p>写成自动配置而不是 {@code @Component}：自动配置排在各服务自己扫描到的类后面处理，这样才看得出这个服务里
 * 有没有取公告的实现。工作台服务自己带着 mugsun-boot 的 {@code AppNoticeService} 和 {@code NoticeReadService}，
 * 转发的那两个在它那里不注册；别的服务里没有，就注册转发的。两个各看各的接口，互不影响。
 *
 * <p>所以这个包不能放在各服务启动类扫描的包（{@code com.mugsun.cloud.common}、{@code com.mugsun.cloud.<服务名>}）下面：
 * 被扫描到的话就不是排在后面处理了。
 */
@AutoConfiguration
public class DeskApiAutoConfiguration {

	/** 工作台服务的服务名，不配是 mugsun-desk */
	static final String SERVICE_ID_KEY = "mugsun.cloud.desk.service-id";

	@Bean
	@ConditionalOnMissingBean(AppNoticeReader.class)
	public AppNoticeReader remoteAppNoticeReader(InnerClient client,
												 @Value("${" + SERVICE_ID_KEY + ":mugsun-desk}") String service) {
		return new RemoteAppNoticeReader(client, service);
	}

	@Bean
	@ConditionalOnMissingBean(NoticeTitleFinder.class)
	public NoticeTitleFinder remoteNoticeTitleFinder(InnerClient client,
													 @Value("${" + SERVICE_ID_KEY + ":mugsun-desk}") String service) {
		return new RemoteNoticeTitleFinder(client, service);
	}
}
