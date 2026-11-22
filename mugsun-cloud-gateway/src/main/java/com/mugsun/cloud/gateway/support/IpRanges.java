package com.mugsun.cloud.gateway.support;

import com.mugsun.boot.common.web.IpCidr;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把文字认成 IP 地址、把名单认成地址段。只看写法，不查 DNS。
 *
 * <p>{@link IpCidr} 把文字交给 JDK 去认，而 JDK 遇到「像 IPv4、但有一段超过 255」的写法（比如 {@code 10.0.0.300}），
 * 会把它当成主机名去查 DNS。在什么名字都解析得出地址的网络里（开着 fake-ip 的代理、带通配记录的 DNS），
 * 查回来的是一个不相干的地址：名单里写错的一条不报错，悄悄变成了别的地址；请求头里带着这种值，处理请求的线程还要停下来等 DNS。
 * 所以网关里的文字一律先经过这里，不直接交给 {@link IpCidr#toBytes}、{@link IpCidr#parse} 和 {@link IpCidr#parseList}。
 */
public final class IpRanges {

	private static final Pattern V4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

	/** 带冒号、又以十六进制数字或冒号开头的文字，JDK 只按 IPv6 的写法检查，不合法就报错，不会拿去查 DNS */
	private static final Pattern V6 = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]*");

	private IpRanges() {
	}

	/**
	 * 字面的 IP 地址转成字节；不是字面的 IP 地址返回 null。和 {@link IpCidr#toBytes} 一样，方括号和 {@code %} 后面的网卡名不算在地址里。
	 */
	public static byte[] bytes(String text) {
		if (text == null || text.isEmpty()) {
			return null;
		}
		String address = text;
		if (address.startsWith("[") && address.endsWith("]")) {
			address = address.substring(1, address.length() - 1);
		}
		int zone = address.indexOf('%');
		if (zone >= 0) {
			address = address.substring(0, zone);
		}
		boolean spelledLikeAnAddress = address.indexOf(':') >= 0 ? V6.matcher(address).matches() : isV4(address);
		return spelledLikeAnAddress ? IpCidr.toBytes(address) : null;
	}

	/**
	 * 解析名单里的地址段（逗号、分号或空白分隔），有一条写错就报错。不忽略写错的条目：白名单里唯一的一条写错了被忽略的话，
	 * 白名单就成了空的，等于没有限制。
	 *
	 * @param property 这份名单的配置项名，写进报错信息
	 */
	public static List<IpCidr> parse(String text, String property) {
		List<IpCidr> result = new ArrayList<>();
		if (text != null) {
			for (String item : text.split("[,;\\s]+")) {
				if (item.isEmpty()) {
					continue;
				}
				IpCidr range = range(item);
				if (range == null) {
					throw new IllegalArgumentException(property + " 里的地址段不合法：" + item
						+ "。要写成地址或 CIDR，例如 203.0.113.7 或 10.0.0.0/8");
				}
				result.add(range);
			}
		}
		return List.copyOf(result);
	}

	/** 名单里的一条：单个地址、CIDR，或者旧的通配写法（{@code 10.1.*}）。写错了返回 null */
	public static IpCidr range(String item) {
		// 通配写法由 IpCidr 自己一段一段检查，不经过 JDK
		if (item.indexOf('*') < 0) {
			int slash = item.indexOf('/');
			if (bytes(slash < 0 ? item : item.substring(0, slash)) == null) {
				return null;
			}
		}
		return IpCidr.parse(item);
	}

	private static boolean isV4(String address) {
		if (!V4.matcher(address).matches()) {
			return false;
		}
		for (String part : address.split("\\.")) {
			if (Integer.parseInt(part) > 255) {
				return false;
			}
		}
		return true;
	}
}
