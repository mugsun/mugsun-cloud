package com.mugsun.cloud.common.inner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 可选服务在注册中心里的名字，发起内部调用时用。
 *
 * <p>配置项和 system 服务判断「模块在不在」用的是同一组（{@code mugsun.cloud.module.<模块>.service-id}），
 * 默认值就是各服务自己的 {@code spring.application.name}。改了某个服务的名字，要在所有服务上把对应的配置项一起改掉。
 *
 * <p>名字会写进签名，只能用字母、数字、点、下划线、短横线，配错了启动时就报错。
 */
@Component
public class ServiceNames {

	private final String gis;

	private final String track;

	private final String ai;

	public ServiceNames(@Value("${mugsun.cloud.module.gis.service-id:mugsun-gis}") String gis,
						@Value("${mugsun.cloud.module.track.service-id:mugsun-track}") String track,
						@Value("${mugsun.cloud.module.ai.service-id:mugsun-ai}") String ai) {
		this.gis = checked("gis", gis);
		this.track = checked("track", track);
		this.ai = checked("ai", ai);
	}

	/** gis 服务 */
	public String gis() {
		return gis;
	}

	/** track 服务 */
	public String track() {
		return track;
	}

	/** ai 服务 */
	public String ai() {
		return ai;
	}

	private static String checked(String module, String configured) {
		String name = configured == null ? "" : configured.strip();
		if (!InnerProtocol.SERVICE_NAME.matcher(name).matches()) {
			throw new IllegalStateException("配置项 mugsun.cloud.module." + module + ".service-id 的值不能当服务名用："
				+ "只能是字母、数字、点、下划线、短横线，1 到 64 个字符");
		}
		return name;
	}
}
