package com.mugsun.cloud.gateway.lane;

import com.mugsun.boot.common.web.IpCidr;
import com.mugsun.cloud.gateway.support.ClientView;
import com.mugsun.cloud.gateway.support.IpRanges;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 一份灰度规则：哪些请求带上哪个灰度标记。建好以后不再变，换规则就是换一个新的对象。
 *
 * <p>一个请求的标记这样定，先中的算：
 * <ol>
 * <li>请求头 {@code X-Mugsun-Lane} 指定的标记。只有客户端地址在 {@code header-from} 里才认；
 * 写 {@code base} 表示这次只走正式实例，后面的规则都不看；写法不对的当成没带</li>
 * <li>按地址、按域名的规则（{@code client-ips}、{@code hosts}）：按配置里的顺序，第一个中的标记</li>
 * <li>按比例的规则（{@code percent}）：按配置里的顺序，第一个中的标记</li>
 * </ol>
 * 都不中就没有标记。任何标记的按地址、按域名的规则，都排在所有按比例的规则前面：点了名的客户端，不会因为写在前面的标记抽中了它而去了别处。
 *
 * <p>按比例怎么抽：把「标记名 + 客户端」算成 0 到 9999 之间的一个数，小于比例（按万分之几算）就是抽中。
 * 同一个客户端每次算出来的数都一样，所以它要么一直走灰度、要么一直不走，换一台网关、重启网关也不变；
 * 把比例调大，原来抽中的还在里面，只是多了新的；每个标记各算各的，两个标记抽中的不是同一批客户端。
 * 客户端按 {@link ClientView#bucketKey} 分：IPv4 按单个地址，IPv6 按 /64。算不出地址的请求不参加按比例的分流。
 *
 * <p>域名只做整个相等的比较（不分大小写），没有通配符。地址和域名用的是按可信代理算出来的那一份，和传给后面服务的一样。
 */
final class LaneRules {

	/** 一份规则里最多有几个灰度标记 */
	static final int MAX_LANES = 16;

	/** 一个名单（某个标记的 client-ips、hosts，或者 header-from）最多有几条。每个请求都要把地址段逐条比一遍，不能无限长 */
	static final int MAX_ENTRIES = 32;

	/** 比例按万分之几算：100% 是 10000 */
	static final int FULL = 10_000;

	/** 没有任何规则：所有请求都没有标记 */
	static final LaneRules NONE = new LaneRules(List.of(), List.of(), List.of(), List.of());

	private static final Pattern SEPARATORS = Pattern.compile("[,;\\s]+");
	private static final Pattern HOST = Pattern.compile("[a-z0-9._-]{1,253}");

	/**
	 * 一个灰度标记和它的分流条件，照配置里写的。
	 *
	 * @param name        标记的名字
	 * @param clientIps   客户端地址落在这些地址段里的请求带这个标记
	 * @param hosts       访问的是这些域名的请求带这个标记
	 * @param basisPoints 其余的请求按这个比例（万分之几）抽；没写是 null
	 */
	record Lane(String name, List<String> clientIps, List<String> hosts, Integer basisPoints) {

		Lane {
			clientIps = clientIps == null ? List.of() : List.copyOf(clientIps);
			hosts = hosts == null ? List.of() : List.copyOf(hosts);
		}
	}

	/** 一个标记的条件，已经转成能直接比的形式 */
	private record Condition(String name, List<IpCidr> clientIps, Set<String> hosts, int basisPoints) {
	}

	/** 整理过的配置原样，只用来判断两份规则是不是一样、以及写日志 */
	private final List<Lane> lanes;
	private final List<String> headerFrom;

	private final List<Condition> conditions;
	private final List<IpCidr> headerFromRanges;
	private final boolean anyExplicit;
	private final boolean anyPercent;

	private LaneRules(List<Lane> lanes, List<String> headerFrom, List<Condition> conditions, List<IpCidr> headerFromRanges) {
		this.lanes = List.copyOf(lanes);
		this.headerFrom = List.copyOf(headerFrom);
		this.conditions = List.copyOf(conditions);
		this.headerFromRanges = List.copyOf(headerFromRanges);
		this.anyExplicit = conditions.stream().anyMatch(condition -> !condition.clientIps().isEmpty() || !condition.hosts().isEmpty());
		this.anyPercent = conditions.stream().anyMatch(condition -> condition.basisPoints() > 0);
	}

	/**
	 * 检查一份规则，合格才建出来。
	 *
	 * @param lanes      灰度标记，按配置里的顺序
	 * @param headerFrom 哪些地址发来的请求可以用请求头自己指定标记
	 * @throws InvalidLaneRulesException 有一处不合格
	 */
	static LaneRules of(List<Lane> lanes, List<String> headerFrom) {
		if (lanes.size() > MAX_LANES) {
			throw new InvalidLaneRulesException("灰度标记最多 " + MAX_LANES + " 个，现在写了 " + lanes.size() + " 个");
		}
		List<Lane> normalized = new ArrayList<>(lanes.size());
		List<Condition> conditions = new ArrayList<>(lanes.size());
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < lanes.size(); i++) {
			Lane lane = lanes.get(i);
			String name = checkedName(lane.name(), i + 1);
			if (!seen.add(name)) {
				throw new InvalidLaneRulesException("灰度标记 " + name + " 出现了不止一次");
			}
			List<String> clientIps = entries(lane.clientIps());
			List<IpCidr> ranges = ranges(clientIps, "灰度标记 " + name + " 的 client-ips");
			List<String> hosts = hosts(lane.hosts(), name);
			Integer basisPoints = lane.basisPoints();
			if (basisPoints != null && (basisPoints < 0 || basisPoints > FULL)) {
				throw new InvalidLaneRulesException("灰度标记 " + name + " 的 percent 要在 0 到 100 之间");
			}
			if (clientIps.isEmpty() && hosts.isEmpty() && basisPoints == null) {
				throw new InvalidLaneRulesException("灰度标记 " + name + " 没有写分流条件：client-ips、hosts、percent 至少写一项。"
					+ "只想先登记这个标记、暂时不分流量给它，写 percent: 0");
			}
			normalized.add(new Lane(name, clientIps, hosts, basisPoints));
			conditions.add(new Condition(name, ranges, Set.copyOf(hosts), basisPoints == null ? 0 : basisPoints));
		}
		List<String> from = entries(headerFrom);
		return new LaneRules(normalized, from, conditions, ranges(from, "header-from"));
	}

	/** 一条规则都没有：没有灰度标记，也没有能用请求头指定标记的地址 */
	boolean isEmpty() {
		return conditions.isEmpty() && headerFromRanges.isEmpty();
	}

	/** 有几个灰度标记 */
	int size() {
		return conditions.size();
	}

	/**
	 * 这次请求带哪个灰度标记。
	 *
	 * @param clientIp  客户端地址；算不出来时是空串
	 * @param host      客户端访问的域名；算不出来时是空串
	 * @param requested 请求头 X-Mugsun-Lane 的值；没带是 null
	 * @return 标记的名字；不走灰度返回 null
	 */
	String decide(String clientIp, String host, String requested) {
		if (requested != null && !headerFromRanges.isEmpty() && IpCidr.anyContains(headerFromRanges, clientIp)) {
			if (LaneNames.BASE.equals(requested)) {
				return null;
			}
			if (LaneNames.usable(requested)) {
				return requested;
			}
		}
		if (anyExplicit) {
			String visited = normalizedHost(host);
			for (Condition condition : conditions) {
				if (IpCidr.anyContains(condition.clientIps(), clientIp)
					|| (!visited.isEmpty() && condition.hosts().contains(visited))) {
					return condition.name();
				}
			}
		}
		if (anyPercent) {
			String client = ClientView.bucketKey(clientIp);
			if (!client.isEmpty()) {
				for (Condition condition : conditions) {
					if (condition.basisPoints() > 0 && bucket(condition.name(), client) < condition.basisPoints()) {
						return condition.name();
					}
				}
			}
		}
		return null;
	}

	/**
	 * 「标记名 + 客户端」算出来的那个 0 到 9999 之间的数。用 SHA-256 是图它分得匀，和安全没有关系。
	 */
	static int bucket(String lane, String client) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("这个 Java 运行环境没有 SHA-256", e);
		}
		digest.update(lane.getBytes(StandardCharsets.UTF_8));
		digest.update((byte) '\n');
		digest.update(client.getBytes(StandardCharsets.UTF_8));
		return (int) Long.remainderUnsigned(ByteBuffer.wrap(digest.digest()).getLong(), FULL);
	}

	/** 写进日志的一句话：有哪几个标记，各有什么条件。只有名字和条数，不带地址和域名 */
	String describe() {
		if (isEmpty()) {
			return "没有灰度规则";
		}
		String text = lanes.isEmpty() ? "没有灰度标记" : lanes.size() + " 个灰度标记："
			+ lanes.stream().map(LaneRules::describe).collect(Collectors.joining("；"));
		if (!headerFrom.isEmpty()) {
			text += "。能用请求头 " + LaneNames.HEADER + " 自己指定标记的地址 " + headerFrom.size() + " 段";
		}
		return text;
	}

	private static String describe(Lane lane) {
		List<String> conditions = new ArrayList<>(3);
		if (!lane.clientIps().isEmpty()) {
			conditions.add("地址 " + lane.clientIps().size() + " 段");
		}
		if (!lane.hosts().isEmpty()) {
			conditions.add("域名 " + lane.hosts().size() + " 个");
		}
		if (lane.basisPoints() != null) {
			conditions.add("比例 " + BigDecimal.valueOf(lane.basisPoints(), 2).stripTrailingZeros().toPlainString() + "%");
		}
		return lane.name() + "（" + String.join("，", conditions) + "）";
	}

	private static String checkedName(String raw, int position) {
		String name = raw == null ? "" : raw.strip();
		if (name.isEmpty()) {
			throw new InvalidLaneRulesException("第 " + position + " 个灰度标记没写 name");
		}
		if (LaneNames.BASE.equals(name)) {
			throw new InvalidLaneRulesException("第 " + position + " 个灰度标记的 name 写成了 base：base 是保留的名字，表示正式实例，不能当灰度标记");
		}
		if (!LaneNames.NAME.matcher(name).matches()) {
			throw new InvalidLaneRulesException("第 " + position
				+ " 个灰度标记的 name 写法不对：小写字母或数字开头，只能有小写字母、数字、横线，最长 32 个字符");
		}
		return name;
	}

	/** 名单可以写成 YAML 的列表，也可以写成用逗号、分号或空白隔开的一行：都拆成一条一条的 */
	private static List<String> entries(List<String> written) {
		List<String> entries = new ArrayList<>();
		if (written != null) {
			for (String item : written) {
				if (item == null) {
					continue;
				}
				for (String entry : SEPARATORS.split(item)) {
					if (!entry.isEmpty()) {
						entries.add(entry);
					}
				}
			}
		}
		return entries;
	}

	private static List<IpCidr> ranges(List<String> entries, String list) {
		if (entries.size() > MAX_ENTRIES) {
			throw new InvalidLaneRulesException(list + " 最多 " + MAX_ENTRIES + " 条，现在 " + entries.size() + " 条。连着的地址写成一段，例如 10.0.0.0/8");
		}
		List<IpCidr> ranges = new ArrayList<>(entries.size());
		for (int i = 0; i < entries.size(); i++) {
			IpCidr range = IpRanges.range(entries.get(i));
			if (range == null) {
				throw new InvalidLaneRulesException(list + " 的第 " + (i + 1)
					+ " 条不是合法的地址段。要写成地址或 CIDR，例如 203.0.113.7 或 10.0.0.0/8");
			}
			ranges.add(range);
		}
		return ranges;
	}

	private static List<String> hosts(List<String> written, String lane) {
		List<String> entries = entries(written);
		if (entries.size() > MAX_ENTRIES) {
			throw new InvalidLaneRulesException("灰度标记 " + lane + " 的 hosts 最多 " + MAX_ENTRIES + " 条，现在 " + entries.size() + " 条");
		}
		List<String> hosts = new ArrayList<>(entries.size());
		for (int i = 0; i < entries.size(); i++) {
			String host = normalizedHost(entries.get(i));
			if (!HOST.matcher(host).matches()) {
				throw new InvalidLaneRulesException("灰度标记 " + lane + " 的 hosts 的第 " + (i + 1)
					+ " 条写法不对：只写域名本身（例如 beta.example.com），不带协议、端口和路径，不支持通配符");
			}
			hosts.add(host);
		}
		return hosts;
	}

	/** 域名不分大小写；末尾多写的一个点（example.com.）不算在内 */
	private static String normalizedHost(String host) {
		if (host == null || host.isEmpty()) {
			return "";
		}
		String lower = host.toLowerCase(Locale.ROOT);
		return lower.length() > 1 && lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof LaneRules rules && lanes.equals(rules.lanes) && headerFrom.equals(rules.headerFrom);
	}

	@Override
	public int hashCode() {
		return 31 * lanes.hashCode() + headerFrom.hashCode();
	}
}
