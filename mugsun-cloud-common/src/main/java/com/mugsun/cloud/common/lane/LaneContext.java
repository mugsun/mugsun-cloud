package com.mugsun.cloud.common.lane;

import org.slf4j.MDC;

/**
 * 当前这次请求的灰度标记。处理请求的入口（{@link LaneFilter}、内部接口的 {@code InnerRequestFilter}）写进来，
 * 处理完清掉；去调别的服务时 {@code InnerClient} 从这里取。
 *
 * <p>存在日志的 MDC 里，和链路号是同一个做法：异步任务的线程池（{@code TenantTaskDecorator}）会把 MDC 原样带到
 * 执行任务的线程上，标记就跟着过去了。自己另起的线程带不过去，在那里取到的是没有标记。
 */
public final class LaneContext {

	/** MDC 里的键。日志格式里写 {@code %X{lane}} 可以把标记打出来 */
	public static final String MDC_KEY = "lane";

	private LaneContext() {
	}

	/** 当前请求的标记，没有标记时是 null */
	public static String current() {
		return Lanes.normalize(MDC.get(MDC_KEY));
	}

	/** 记下当前请求的标记。没带、写法不对、写的是 {@code base}，都按没有标记记 */
	public static void set(String lane) {
		String name = Lanes.normalize(lane);
		if (name == null) {
			MDC.remove(MDC_KEY);
		} else {
			MDC.put(MDC_KEY, name);
		}
	}

	public static void clear() {
		MDC.remove(MDC_KEY);
	}
}
