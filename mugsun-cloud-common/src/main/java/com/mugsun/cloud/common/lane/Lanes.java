package com.mugsun.cloud.common.lane;

import org.springframework.cloud.client.ServiceInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 灰度标记（代码里叫 lane）的约定，和按标记挑实例的规则。
 *
 * <p>标记是一个名字，比如 {@code canary}。实例启动时带上它（{@code mugsun.cloud.lane}）就是这个标记的灰度实例，
 * 没带的是正式实例。网关按规则给一个请求定了标记以后，写在请求头 {@link #HEADER} 里往后传，
 * 服务之间互相调用时接着带，这样一个请求一路上找的都是同一个标记的实例。
 *
 * <p>挑实例的规则，网关转发和服务之间互相调用是同一条：
 * <ul>
 *   <li>没有标记的请求只找正式实例，任何时候都不会落到灰度实例上</li>
 *   <li>带标记的请求先找同一个标记的实例；对方没有这个标记的实例，或者找到的那个不行，就找正式实例。
 *       别的标记的实例不找</li>
 * </ul>
 *
 * <p>网关不依赖这个模块，请求头的名字、注册中心里的键和名字的写法在网关那边另有一份
 * （{@code com.mugsun.cloud.gateway.lane.LaneNames}），改的时候两边一起改。
 */
public final class Lanes {

	/** 带着标记往后传的请求头。客户端自己带来的不算数：网关一律先去掉，再按自己的规则写 */
	public static final String HEADER = "X-Mugsun-Lane";

	/** 实例登记到注册中心时，标记放在元数据的这个键下面 */
	public static final String METADATA_KEY = "lane";

	/** 这个名字表示「正式」，不能拿来当标记 */
	public static final String BASE = "base";

	/** 标记的写法：小写字母、数字和短横线，字母或数字开头，最长 32 个字符 */
	public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

	private Lanes() {
	}

	/**
	 * 请求带来的标记能不能用：写法合规的原样返回；没带、写法不对、写的是 {@code base}，都返回 null，按没有标记处理。
	 */
	public static String normalize(String raw) {
		if (raw == null || BASE.equals(raw) || !NAME.matcher(raw).matches()) {
			return null;
		}
		return raw;
	}

	/**
	 * 实例登记的标记。没登记或者登记的是空的，返回 null，是正式实例；登记了别的任何内容都原样返回，哪怕写法不合规。
	 * 写法不合规的不能当成正式实例：那样正常的请求就会发给它。原样留着的话没有哪个请求的标记能和它对上，谁也不找它。
	 */
	public static String of(ServiceInstance instance) {
		Map<String, String> metadata = instance.getMetadata();
		String lane = metadata == null ? null : metadata.get(METADATA_KEY);
		if (lane == null || lane.isBlank()) {
			return null;
		}
		return lane.strip();
	}

	/**
	 * 一次请求能找的实例，分成先找的和退回去找的两组。
	 *
	 * @param preferred 先找的：带标记的请求是同一个标记的实例，没有标记的请求是正式实例
	 * @param fallback  先找的那一组没有实例、或者试过的那个不行时找的：带标记的请求是正式实例，没有标记的请求没有这一组
	 */
	public record Candidates(List<ServiceInstance> preferred, List<ServiceInstance> fallback) {

		public boolean isEmpty() {
			return preferred.isEmpty() && fallback.isEmpty();
		}
	}

	/**
	 * 按请求的标记从一个服务的全部实例里挑出能找的。
	 *
	 * @param lane 请求的标记，null 表示没有标记
	 */
	public static Candidates candidates(List<ServiceInstance> all, String lane) {
		List<ServiceInstance> same = new ArrayList<>();
		List<ServiceInstance> base = new ArrayList<>();
		for (ServiceInstance instance : all) {
			String its = of(instance);
			if (its == null) {
				base.add(instance);
			} else if (its.equals(lane)) {
				same.add(instance);
			}
		}
		if (lane == null) {
			return new Candidates(base, List.of());
		}
		return new Candidates(same, base);
	}
}
