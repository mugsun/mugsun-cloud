package com.mugsun.cloud.gateway.lane;

import org.springframework.cloud.client.ServiceInstance;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 灰度发布的几个约定：请求头叫什么、实例把自己的灰度标记登记在注册中心的哪个键上、标记的名字怎么写。
 *
 * <p>带着灰度标记启动的实例叫灰度实例，没带的叫正式实例。没有标记的请求只发给正式实例；
 * 有标记的请求先发给同一个标记的灰度实例，这个服务没有这样的实例时发给正式实例；别的标记的灰度实例一律不发。
 *
 * <p>服务那边的同一份约定在 mugsun-cloud-common 的 {@code com.mugsun.cloud.common.lane.Lanes} 里。网关不依赖那个模块，
 * 所以各写一份；两边必须一样，LaneNamesTest 会核对。
 */
public final class LaneNames {

	/** 带灰度标记的请求头。只由网关写：客户端自己带来的一律删掉，见 filter 包的 LaneHeaderFilter */
	public static final String HEADER = "X-Mugsun-Lane";

	/** 实例的灰度标记登记在注册中心元数据的这个键上 */
	public static final String METADATA_KEY = "lane";

	/** 保留的名字，表示正式实例，不能当灰度标记用 */
	public static final String BASE = "base";

	/** 标记的名字：小写字母或数字开头，只能有小写字母、数字、横线，最长 32 个字符 */
	public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

	/** 转发过程中记在请求上的属性：这次请求的灰度标记（String）。没有这个属性就是没有标记 */
	public static final String LANE_ATTR = LaneNames.class.getName() + ".lane";

	/** 转发过程中记在请求上的属性：刚才没连上的那个实例（ServiceInstance）。只在换实例再转发一次时有 */
	public static final String FAILED_INSTANCE_ATTR = LaneNames.class.getName() + ".failedInstance";

	private LaneNames() {
	}

	/** 这段文字能不能当灰度标记用：写法对，并且不是保留的 base */
	public static boolean usable(String name) {
		return name != null && !BASE.equals(name) && NAME.matcher(name).matches();
	}

	/**
	 * 实例登记的灰度标记；正式实例（没登记，或者登记的是空的）返回 null。
	 *
	 * <p>登记的内容不检查写法，原样返回：写法不对的标记不会和任何请求的标记相等，这样的实例谁的请求都收不到。
	 * 不能把它当成正式实例：它跑的可能是还没验证过的新版本。
	 */
	public static String of(ServiceInstance instance) {
		Map<String, String> metadata = instance.getMetadata();
		String lane = metadata == null ? null : metadata.get(METADATA_KEY);
		if (lane == null || lane.isBlank()) {
			return null;
		}
		return lane.strip();
	}
}
