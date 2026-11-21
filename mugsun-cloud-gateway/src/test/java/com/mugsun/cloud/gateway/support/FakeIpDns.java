package com.mugsun.cloud.gateway.support;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 测试用的域名解析，模仿「什么名字都解析得出地址」的网络（开着 fake-ip 的代理就是这样）：像 IPv4 的名字一律解析成
 * {@link #ANSWER}，别的名字照常解析。每一次查询都记下来，测试可以检查某个名字有没有被拿去查过。
 *
 * <p>JDK 遇到 {@code 10.0.0.300} 这种「像 IPv4、但有一段超过 255」的写法会去查 DNS。在普通网络里查不到、当成不合法，
 * 问题显不出来；有了这个类，在哪台机器上跑测试都能显出来。经 {@code META-INF/services} 登记，对这个模块的所有测试生效。
 */
public class FakeIpDns extends InetAddressResolverProvider {

	public static final String ANSWER = "198.18.0.1";

	private static final List<String> LOOKUPS = new CopyOnWriteArrayList<>();
	private static final Pattern LOOKS_LIKE_V4 = Pattern.compile("\\d+(\\.\\d+){3}");

	/** 到现在为止被拿去查过的名字 */
	public static List<String> lookups() {
		return List.copyOf(LOOKUPS);
	}

	@Override
	public InetAddressResolver get(Configuration configuration) {
		InetAddressResolver builtin = configuration.builtinResolver();
		return new InetAddressResolver() {
			@Override
			public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) throws UnknownHostException {
				LOOKUPS.add(host);
				if (LOOKS_LIKE_V4.matcher(host).matches()) {
					return Stream.of(InetAddress.getByAddress(host, new byte[] {(byte) 198, 18, 0, 1}));
				}
				return builtin.lookupByName(host, lookupPolicy);
			}

			@Override
			public String lookupByAddress(byte[] address) throws UnknownHostException {
				return builtin.lookupByAddress(address);
			}
		};
	}

	@Override
	public String name() {
		return "mugsun-cloud-gateway tests";
	}
}
