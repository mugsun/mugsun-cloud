package com.mugsun.cloud.gateway.support;

import com.mugsun.boot.common.web.IpCidr;
import org.springframework.http.HttpHeaders;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 客户端看到的这次请求：客户端地址，以及它访问时用的协议、主机名和端口。
 *
 * <p>客户端地址的取法和 mugsun-boot 的 {@code ClientIpResolver} 是同一个规则：默认只认直接连上来的地址；
 * 只有这个地址属于可信代理时，才从 X-Forwarded-For 右往左取第一个不在可信代理内的地址，
 * 遇到不是 IP 的值就停下，退回直接连上来的地址。协议、主机名、端口同样只采信可信代理带来的值。
 *
 * @param clientIp 客户端地址，取不到时为空串
 * @param scheme   http 或 https
 * @param host     客户端访问的主机名（不带端口），取不到时为空串
 * @param port     客户端访问的端口
 */
public record ClientView(String clientIp, String scheme, String host, int port) {

	public static final String X_FORWARDED_FOR = "X-Forwarded-For";
	public static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";
	public static final String X_FORWARDED_HOST = "X-Forwarded-Host";
	public static final String X_FORWARDED_PORT = "X-Forwarded-Port";

	private static final Pattern HOST_PORT = Pattern
		.compile("(\\[[0-9A-Fa-f:.]{2,45}]|[A-Za-z0-9._-]{1,253})(?::(\\d{1,5}))?");

	/**
	 * @param remoteAddr     直接连上网关的地址
	 * @param uri            网关收到的请求地址（协议来自连接本身，主机和端口来自 Host 头）
	 * @param headers        网关收到的请求头
	 * @param trustedProxies 网关前面的可信代理
	 */
	public static ClientView resolve(String remoteAddr, URI uri, HttpHeaders headers, List<IpCidr> trustedProxies) {
		String remote = canonicalIp(remoteAddr);
		boolean viaProxy = remote != null && !trustedProxies.isEmpty() && IpCidr.anyContains(trustedProxies, remote);

		String clientIp = remote == null ? "" : remote;
		String scheme = "https".equalsIgnoreCase(uri.getScheme()) ? "https" : "http";
		String host = "";
		int port = uri.getPort();
		if (uri.getHost() != null && HOST_PORT.matcher(uri.getHost()).matches()) {
			host = uri.getHost();
		}

		if (viaProxy) {
			clientIp = fromForwardedFor(remote, headers.getOrEmpty(X_FORWARDED_FOR), trustedProxies);
			String proto = forwardedProto(headers.getOrEmpty(X_FORWARDED_PROTO));
			if (proto != null) {
				scheme = proto;
			}
			String forwardedHost = firstToken(headers.getOrEmpty(X_FORWARDED_HOST));
			Matcher matcher = forwardedHost == null ? null : HOST_PORT.matcher(forwardedHost);
			if (matcher != null && matcher.matches()) {
				host = matcher.group(1);
				port = parsePort(matcher.group(2));
			}
			int forwardedPort = parsePort(firstToken(headers.getOrEmpty(X_FORWARDED_PORT)));
			if (forwardedPort > 0) {
				port = forwardedPort;
			}
		}
		if (port <= 0) {
			port = "https".equals(scheme) ? 443 : 80;
		}
		return new ClientView(clientIp, scheme, host, port);
	}

	/** 把字面 IP 转成统一写法（去掉方括号和网卡后缀）；不是字面 IP 返回 null。 */
	public static String canonicalIp(String text) {
		byte[] bytes = IpCidr.toBytes(text == null ? null : text.trim());
		if (bytes == null) {
			return null;
		}
		try {
			return InetAddress.getByAddress(bytes).getHostAddress();
		} catch (UnknownHostException e) {
			return null;
		}
	}

	private static String fromForwardedFor(String remote, List<String> lines, List<IpCidr> trustedProxies) {
		// 同名的头出现多行时，按顺序拼成一个列表：越靠右的值离网关越近
		String[] hops = String.join(",", lines).split(",");
		for (int i = hops.length - 1; i >= 0; i--) {
			String hop = hops[i].trim();
			if (hop.isEmpty()) {
				continue;
			}
			String ip = canonicalIp(hop);
			if (ip == null) {
				return remote;
			}
			if (!IpCidr.anyContains(trustedProxies, ip)) {
				return ip;
			}
		}
		return remote;
	}

	/** 经过多层代理时这个头是一串值：全部是 https 才算 https；出现别的值返回 null，表示不采信。 */
	private static String forwardedProto(List<String> lines) {
		String result = null;
		for (String token : String.join(",", lines).split(",")) {
			String value = token.trim().toLowerCase(Locale.ROOT);
			if (value.isEmpty()) {
				continue;
			}
			if (!value.equals("http") && !value.equals("https")) {
				return null;
			}
			result = result == null || result.equals(value) ? value : "http";
		}
		return result;
	}

	private static String firstToken(List<String> lines) {
		for (String token : String.join(",", lines).split(",")) {
			if (!token.isBlank()) {
				return token.trim();
			}
		}
		return null;
	}

	private static int parsePort(String text) {
		if (text == null || !text.matches("\\d{1,5}")) {
			return -1;
		}
		int port = Integer.parseInt(text);
		return port >= 1 && port <= 65535 ? port : -1;
	}
}
