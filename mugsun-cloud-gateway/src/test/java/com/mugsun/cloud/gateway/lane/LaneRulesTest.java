package com.mugsun.cloud.gateway.lane;

import com.mugsun.cloud.gateway.lane.LaneRules.Lane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一份灰度规则怎么定一个请求的灰度标记：按地址、按域名、按比例、请求头指定，谁先谁后；以及哪些写法不收。
 * 配置的文本怎么读在 LaneRuleParserTest 里测。
 */
class LaneRulesTest {

	private static final String ANY_HOST = "www.example.com";

	/** 2000 个各不相同的客户端地址 */
	private static final List<String> CLIENTS = IntStream.range(0, 2000)
		.mapToObj(i -> "10." + i / 250 + "." + i % 250 + ".7")
		.toList();

	private static Lane byAddress(String name, String... clientIps) {
		return new Lane(name, List.of(clientIps), List.of(), null);
	}

	private static Lane byHost(String name, String... hosts) {
		return new Lane(name, List.of(), List.of(hosts), null);
	}

	/** @param basisPoints 万分之几：500 是 5% */
	private static Lane byPercent(String name, int basisPoints) {
		return new Lane(name, List.of(), List.of(), basisPoints);
	}

	private static LaneRules rules(Lane... lanes) {
		return LaneRules.of(List.of(lanes), List.of());
	}

	private static Set<String> drawn(LaneRules rules, String lane) {
		return CLIENTS.stream().filter(client -> lane.equals(rules.decide(client, ANY_HOST, null)))
			.collect(Collectors.toSet());
	}

	@Test
	void withoutRulesNoRequestHasALane() {
		assertThat(LaneRules.NONE.isEmpty()).isTrue();
		assertThat(LaneRules.NONE.size()).isZero();
		assertThat(LaneRules.NONE.decide("203.0.113.7", ANY_HOST, null)).isNull();
		assertThat(LaneRules.NONE.decide("203.0.113.7", ANY_HOST, "canary")).isNull();
		assertThat(LaneRules.of(List.of(), List.of())).isEqualTo(LaneRules.NONE);
	}

	@Test
	void clientInsideAListedRangeGetsTheLane() {
		LaneRules rules = rules(byAddress("canary", "203.0.113.7", "10.8.0.0/16", "2001:db8:5::/48"));

		assertThat(rules.isEmpty()).isFalse();
		assertThat(rules.size()).isEqualTo(1);
		assertThat(rules.decide("203.0.113.7", ANY_HOST, null)).isEqualTo("canary");
		assertThat(rules.decide("10.8.3.4", ANY_HOST, null)).isEqualTo("canary");
		assertThat(rules.decide("2001:db8:5:1::9", ANY_HOST, null)).isEqualTo("canary");
		assertThat(rules.decide("203.0.113.8", ANY_HOST, null)).isNull();
		assertThat(rules.decide("10.9.0.1", ANY_HOST, null)).isNull();
		assertThat(rules.decide("2001:db8:6::1", ANY_HOST, null)).isNull();
	}

	/** 网关其他名单认的旧写法（10.8.* 就是 10.8.0.0/16），这里一样认 */
	@Test
	void oldWildcardNotationIsReadLikeInTheOtherListsOfTheGateway() {
		LaneRules rules = rules(byAddress("canary", "10.8.*"));

		assertThat(rules.decide("10.8.3.4", ANY_HOST, null)).isEqualTo("canary");
		assertThat(rules.decide("10.9.3.4", ANY_HOST, null)).isNull();
	}

	@Test
	void requestWithoutAKnownClientAddressMatchesNoAddressRule() {
		LaneRules rules = rules(byAddress("canary", "0.0.0.0/0", "::/0"));

		assertThat(rules.decide("198.51.100.1", ANY_HOST, null)).isEqualTo("canary");
		assertThat(rules.decide("", ANY_HOST, null)).isNull();
		assertThat(rules.decide(null, ANY_HOST, null)).isNull();
	}

	@Test
	void visitedHostIsComparedAsAWholeIgnoringCase() {
		LaneRules rules = rules(byHost("canary", "Beta.Example.com", "beta2.example.com."));

		assertThat(rules.decide("203.0.113.7", "beta.example.com", null)).isEqualTo("canary");
		assertThat(rules.decide("203.0.113.7", "BETA.EXAMPLE.COM", null)).isEqualTo("canary");
		assertThat(rules.decide("203.0.113.7", "beta.example.com.", null)).isEqualTo("canary");
		assertThat(rules.decide("203.0.113.7", "beta2.example.com", null)).isEqualTo("canary");
		// 没有客户端地址也能按域名定
		assertThat(rules.decide("", "beta.example.com", null)).isEqualTo("canary");
		// 不是前缀、后缀或子域名的比较
		assertThat(rules.decide("203.0.113.7", "example.com", null)).isNull();
		assertThat(rules.decide("203.0.113.7", "x.beta.example.com", null)).isNull();
		assertThat(rules.decide("203.0.113.7", "beta.example.com.cn", null)).isNull();
		assertThat(rules.decide("203.0.113.7", "", null)).isNull();
		assertThat(rules.decide("203.0.113.7", null, null)).isNull();
	}

	@Test
	void firstLaneInTheDocumentWhoseAddressOrHostMatchesWins() {
		LaneRules rules = rules(byAddress("wide", "10.0.0.0/8"), byAddress("narrow", "10.1.0.0/16"),
			byHost("beta", "beta.example.com"));

		assertThat(rules.decide("10.1.2.3", ANY_HOST, null)).isEqualTo("wide");
		assertThat(rules.decide("10.1.2.3", "beta.example.com", null)).isEqualTo("wide");
		assertThat(rules.decide("203.0.113.7", "beta.example.com", null)).isEqualTo("beta");
	}

	/** 点了名的客户端，不会因为写在前面的标记按比例抽中了它而去了别处 */
	@Test
	void everyAddressAndHostRuleComesBeforeAnyPercentage() {
		LaneRules rules = rules(byPercent("everyone", 10_000), byAddress("testers", "203.0.113.7"),
			byHost("beta", "beta.example.com"));

		assertThat(rules.decide("203.0.113.7", ANY_HOST, null)).isEqualTo("testers");
		assertThat(rules.decide("198.51.100.1", "beta.example.com", null)).isEqualTo("beta");
		assertThat(rules.decide("198.51.100.1", ANY_HOST, null)).isEqualTo("everyone");
	}

	@Test
	void hundredPercentTakesEveryClientAndZeroTakesNone() {
		assertThat(drawn(rules(byPercent("canary", 10_000)), "canary")).hasSize(CLIENTS.size());
		assertThat(drawn(rules(byPercent("canary", 0)), "canary")).isEmpty();
		// 比例写 0 的标记不分流量，但它是一条规则
		assertThat(rules(byPercent("canary", 0)).isEmpty()).isFalse();
	}

	@Test
	void aboutTheConfiguredShareOfClientsIsDrawn() {
		assertThat(drawn(rules(byPercent("canary", 500)), "canary")).hasSizeBetween(70, 130);
		assertThat(drawn(rules(byPercent("canary", 2000)), "canary")).hasSizeBetween(340, 460);
		assertThat(drawn(rules(byPercent("canary", 5000)), "canary")).hasSizeBetween(920, 1080);
		// 最小的一档是万分之一
		assertThat(drawn(rules(byPercent("canary", 1)), "canary")).hasSizeLessThan(5);
	}

	/** 同一个客户端要么一直走灰度，要么一直不走：另一台网关、重启以后的网关算出来的也一样 */
	@Test
	void sameClientAlwaysGetsTheSameAnswer() {
		LaneRules one = rules(byPercent("canary", 3000));
		LaneRules another = rules(byPercent("canary", 3000));

		Set<String> first = drawn(one, "canary");
		assertThat(drawn(one, "canary")).isEqualTo(first);
		assertThat(drawn(another, "canary")).isEqualTo(first);
	}

	/**
	 * 换了算法，正在灰度里的客户端就换了一批，而且新旧两个版本的网关同时在跑的那段时间里，同一个客户端会一会儿走灰度一会儿不走。
	 * 这几个数是用另一种语言照着说明算出来的：SHA-256(标记名 + 换行 + 客户端) 的前 8 个字节当成无符号整数，除以 10000 的余数。
	 */
	@Test
	void bucketIsComputedTheDocumentedWay() {
		assertThat(LaneRules.bucket("canary", "203.0.113.7")).isEqualTo(2178);
		assertThat(LaneRules.bucket("canary", "198.51.100.1")).isEqualTo(2400);
		assertThat(LaneRules.bucket("beta", "203.0.113.7")).isEqualTo(3068);
		assertThat(LaneRules.bucket("canary", "2001:db8:0:1:0:0:0:0/64")).isEqualTo(7431);

		// 203.0.113.7 的数是 2178：比例到 21.79% 才抽中它
		assertThat(rules(byPercent("canary", 2178)).decide("203.0.113.7", ANY_HOST, null)).isNull();
		assertThat(rules(byPercent("canary", 2179)).decide("203.0.113.7", ANY_HOST, null)).isEqualTo("canary");
	}

	/** 把比例调大，原来在灰度里的客户端都还在，只是多了新的 */
	@Test
	void raisingThePercentageKeepsEveryClientAlreadyDrawn() {
		Set<String> at5 = drawn(rules(byPercent("canary", 500)), "canary");
		Set<String> at20 = drawn(rules(byPercent("canary", 2000)), "canary");
		Set<String> at50 = drawn(rules(byPercent("canary", 5000)), "canary");

		assertThat(at5).isNotEmpty();
		assertThat(at20).containsAll(at5).hasSizeGreaterThan(at5.size());
		assertThat(at50).containsAll(at20).hasSizeGreaterThan(at20.size());
	}

	/** 每个标记各抽各的：两个标记都是 50% 时，抽中的不是同一批客户端，重合的大约是四分之一 */
	@Test
	void eachLaneDrawsItsOwnClients() {
		Set<String> ofA = drawn(rules(byPercent("a", 5000)), "a");
		Set<String> ofB = drawn(rules(byPercent("b", 5000)), "b");

		Set<String> both = new HashSet<>(ofA);
		both.retainAll(ofB);
		assertThat(both).hasSizeBetween(400, 600);
	}

	/** 两个按比例的标记写在一份规则里：两个都抽中的客户端归写在前面的那个 */
	@Test
	void clientDrawnByTwoLanesGoesToTheFirstOne() {
		Set<String> ofA = drawn(rules(byPercent("a", 5000)), "a");
		Set<String> ofB = drawn(rules(byPercent("b", 5000)), "b");
		LaneRules together = rules(byPercent("a", 5000), byPercent("b", 5000));

		Set<String> onlyB = new HashSet<>(ofB);
		onlyB.removeAll(ofA);
		assertThat(drawn(together, "a")).isEqualTo(ofA);
		assertThat(drawn(together, "b")).isEqualTo(onlyB).isNotEmpty();
	}

	/** IPv6 按 /64 分：同一个 /64 里的地址是同一个客户端，换个地址不会从灰度里进进出出 */
	@Test
	void ipv6ClientsAreDrawnBySlash64() {
		LaneRules rules = rules(byPercent("canary", 5000));
		int drawn = 0;
		for (int i = 0; i < 200; i++) {
			String prefix = "2001:db8:0:" + Integer.toHexString(i);
			String answer = rules.decide(prefix + "::1", ANY_HOST, null);
			assertThat(rules.decide(prefix + ":aaaa:bbbb:cccc:dddd", ANY_HOST, null)).isEqualTo(answer);
			if (answer != null) {
				drawn++;
			}
		}
		assertThat(drawn).isBetween(70, 130);
	}

	@Test
	void requestWithoutAKnownClientAddressIsNeverDrawn() {
		LaneRules rules = rules(byPercent("canary", 10_000));

		assertThat(rules.decide("198.51.100.1", ANY_HOST, null)).isEqualTo("canary");
		assertThat(rules.decide("", ANY_HOST, null)).isNull();
		assertThat(rules.decide(null, ANY_HOST, null)).isNull();
	}

	@Test
	void headerPicksTheLaneOnlyForClientsListedInHeaderFrom() {
		LaneRules rules = LaneRules.of(List.of(byPercent("canary", 0)), List.of("10.0.0.0/8", "203.0.113.7"));

		assertThat(rules.decide("10.1.1.1", ANY_HOST, "canary")).isEqualTo("canary");
		assertThat(rules.decide("203.0.113.7", ANY_HOST, "canary")).isEqualTo("canary");
		assertThat(rules.decide("203.0.113.8", ANY_HOST, "canary")).isNull();
		assertThat(rules.decide("", ANY_HOST, "canary")).isNull();
		assertThat(rules.decide("10.1.1.1", ANY_HOST, null)).isNull();
	}

	/** 请求头可以指定规则里没写的标记：只给某个实例带上标记、不分任何流量给它时，测试人员就是这样访问它的 */
	@Test
	void headerMayNameALaneThatHasNoRule() {
		LaneRules rules = LaneRules.of(List.of(), List.of("10.0.0.0/8"));

		assertThat(rules.isEmpty()).isFalse();
		assertThat(rules.size()).isZero();
		assertThat(rules.decide("10.1.1.1", ANY_HOST, "hotfix-1")).isEqualTo("hotfix-1");
		assertThat(rules.decide("10.1.1.1", ANY_HOST, null)).isNull();
	}

	@Test
	void headerSayingBaseKeepsTheRequestOutOfEveryLane() {
		LaneRules rules = LaneRules.of(List.of(new Lane("canary", List.of("10.0.0.0/8"), List.of(), 10_000)),
			List.of("10.0.0.0/8"));

		assertThat(rules.decide("10.1.1.1", ANY_HOST, null)).isEqualTo("canary");
		assertThat(rules.decide("10.1.1.1", ANY_HOST, "base")).isNull();
		// 不在 header-from 里的地址说 base 没有用
		assertThat(rules.decide("198.51.100.1", ANY_HOST, "base")).isEqualTo("canary");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "Canary", "a b", "canary,beta", "-x", "abcdefghijklmnopqrstuvwxyz0123456"})
	void malformedHeaderValueIsIgnoredAndTheOtherRulesApply(String header) {
		LaneRules rules = LaneRules.of(List.of(byAddress("canary", "10.1.0.0/16")), List.of("10.0.0.0/8"));

		assertThat(rules.decide("10.1.1.1", ANY_HOST, header)).isEqualTo("canary");
		assertThat(rules.decide("10.2.1.1", ANY_HOST, header)).isNull();
	}

	@Test
	void listsMayBeWrittenAsOneLineSeparatedByCommasSemicolonsOrBlanks() {
		LaneRules oneLine = LaneRules.of(
			List.of(new Lane("canary", List.of("203.0.113.7, 10.8.0.0/16;10.9.0.0/16  10.10.0.0/16"),
				List.of("beta.example.com,beta2.example.com"), null)),
			List.of(" 10.0.0.0/8 ,, 172.16.0.0/12 "));
		LaneRules listed = LaneRules.of(
			List.of(new Lane("canary", List.of("203.0.113.7", "10.8.0.0/16", "10.9.0.0/16", "10.10.0.0/16"),
				List.of("beta.example.com", "beta2.example.com"), null)),
			List.of("10.0.0.0/8", "172.16.0.0/12"));

		assertThat(oneLine).isEqualTo(listed).hasSameHashCodeAs(listed);
		assertThat(oneLine.decide("10.10.1.1", ANY_HOST, null)).isEqualTo("canary");
		assertThat(oneLine.decide("198.51.100.1", "beta2.example.com", null)).isEqualTo("canary");
		assertThat(oneLine.decide("172.16.5.5", ANY_HOST, "v2")).isEqualTo("v2");
	}

	@Test
	void nameIsUsedWithoutSurroundingBlanks() {
		LaneRules rules = rules(byAddress("  canary ", "203.0.113.7"));

		assertThat(rules.decide("203.0.113.7", ANY_HOST, null)).isEqualTo("canary");
	}

	@Test
	void rulesAreEqualWhenTheySayTheSame() {
		LaneRules rules = LaneRules.of(List.of(new Lane("canary", List.of("203.0.113.7"), List.of("beta.example.com"), 500),
			byPercent("v2", 100)), List.of("10.0.0.0/8"));
		LaneRules same = LaneRules.of(List.of(new Lane("canary", List.of("203.0.113.7"), List.of("Beta.Example.COM."), 500),
			byPercent("v2", 100)), List.of("10.0.0.0/8"));

		assertThat(same).isEqualTo(rules).hasSameHashCodeAs(rules);
		assertThat(rules).isNotEqualTo(LaneRules.NONE).isNotEqualTo(null).isNotEqualTo("canary");
		// 比例不一样
		assertThat(LaneRules.of(List.of(new Lane("canary", List.of("203.0.113.7"), List.of("beta.example.com"), 501),
			byPercent("v2", 100)), List.of("10.0.0.0/8"))).isNotEqualTo(rules);
		// 顺序不一样：顺序决定谁先中
		assertThat(LaneRules.of(List.of(byPercent("v2", 100),
			new Lane("canary", List.of("203.0.113.7"), List.of("beta.example.com"), 500)), List.of("10.0.0.0/8")))
			.isNotEqualTo(rules);
		// header-from 不一样
		assertThat(LaneRules.of(List.of(new Lane("canary", List.of("203.0.113.7"), List.of("beta.example.com"), 500),
			byPercent("v2", 100)), List.of())).isNotEqualTo(rules);
	}

	/** 写进日志的那句话只有名字和条数：地址和域名不进日志 */
	@Test
	void descriptionNamesTheLanesAndCountsTheirConditions() {
		LaneRules rules = LaneRules.of(
			List.of(new Lane("canary", List.of("203.0.113.7", "10.8.0.0/16"), List.of("beta.example.com"), 500),
				byPercent("v2", 50), byPercent("parked", 0), byPercent("everyone", 10_000)),
			List.of("10.0.0.0/8"));

		assertThat(rules.describe()).isEqualTo("4 个灰度标记：canary（地址 2 段，域名 1 个，比例 5%）；v2（比例 0.5%）；"
			+ "parked（比例 0%）；everyone（比例 100%）。能用请求头 X-Mugsun-Lane 自己指定标记的地址 1 段");
		assertThat(rules(byHost("beta", "beta.example.com", "beta2.example.com")).describe())
			.isEqualTo("1 个灰度标记：beta（域名 2 个）");
		assertThat(LaneRules.of(List.of(), List.of("10.0.0.0/8", "203.0.113.7")).describe())
			.isEqualTo("没有灰度标记。能用请求头 X-Mugsun-Lane 自己指定标记的地址 2 段");
		assertThat(LaneRules.NONE.describe()).isEqualTo("没有灰度规则");
	}

	@Test
	void atMostSixteenLanes() {
		List<Lane> sixteen = IntStream.range(0, 16).mapToObj(i -> byPercent("lane-" + i, 0)).toList();
		List<Lane> seventeen = new ArrayList<>(sixteen);
		seventeen.add(byPercent("lane-16", 0));

		assertThat(LaneRules.of(sixteen, List.of()).size()).isEqualTo(16);
		assertThatThrownBy(() -> LaneRules.of(seventeen, List.of()))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessage("灰度标记最多 16 个，现在写了 17 个");
	}

	@Test
	void laneNeedsAName() {
		assertThatThrownBy(() -> rules(byPercent("canary", 0), new Lane(null, List.of("203.0.113.7"), List.of(), null)))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessage("第 2 个灰度标记没写 name");
		assertThatThrownBy(() -> rules(byPercent("  ", 0)))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessage("第 1 个灰度标记没写 name");
	}

	@Test
	void baseIsReservedForTheInstancesWithoutALane() {
		assertThatThrownBy(() -> rules(byPercent("base", 500)))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("第 1 个灰度标记的 name 写成了 base");
	}

	@ParameterizedTest
	@ValueSource(strings = {"Canary", "a b", "-x", "a_b", "金丝雀", "abcdefghijklmnopqrstuvwxyz0123456"})
	void malformedNameIsRefusedWithoutBeingQuoted(String name) {
		assertThatThrownBy(() -> rules(byPercent("canary", 0), byPercent(name, 500)))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("第 2 个灰度标记的 name 写法不对")
			.hasMessageNotContaining(name);
	}

	@Test
	void sameNameTwiceIsRefused() {
		assertThatThrownBy(() -> rules(byPercent("canary", 500), byAddress("canary", "203.0.113.7")))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessage("灰度标记 canary 出现了不止一次");
	}

	@Test
	void atMostThirtyTwoEntriesInAList() {
		String[] thirtyTwo = IntStream.range(0, 32).mapToObj(i -> "10.0." + i + ".0/24").toArray(String[]::new);
		String[] thirtyThree = IntStream.range(0, 33).mapToObj(i -> "10.0." + i + ".0/24").toArray(String[]::new);
		String[] hosts = IntStream.range(0, 33).mapToObj(i -> "h" + i + ".example.com").toArray(String[]::new);

		assertThat(rules(byAddress("canary", thirtyTwo)).decide("10.0.31.9", ANY_HOST, null)).isEqualTo("canary");
		assertThatThrownBy(() -> rules(byAddress("canary", thirtyThree)))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("灰度标记 canary 的 client-ips 最多 32 条，现在 33 条");
		assertThatThrownBy(() -> LaneRules.of(List.of(), List.of(thirtyThree)))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("header-from 最多 32 条，现在 33 条");
		assertThatThrownBy(() -> rules(byHost("canary", hosts)))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessage("灰度标记 canary 的 hosts 最多 32 条，现在 33 条");
		// 写成一行的也按拆开以后的条数算
		assertThatThrownBy(() -> rules(byAddress("canary", String.join(",", thirtyThree))))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("灰度标记 canary 的 client-ips 最多 32 条，现在 33 条");
	}

	/** 报错说的是第几条，不把写的内容搬进日志 */
	@ParameterizedTest
	@ValueSource(strings = {"10.0.0.0/33", "10.0.0.1-10.0.0.9", "office-network", "10.9.8", "2001:db8::/129", "10.*.0.1",
		"10.0.0.300"})
	void malformedAddressRangeIsRefusedWithoutBeingQuoted(String range) {
		assertThatThrownBy(() -> rules(byAddress("canary", "203.0.113.7", range)))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("灰度标记 canary 的 client-ips 的第 2 条不是合法的地址段")
			.hasMessageNotContaining(range);
		assertThatThrownBy(() -> LaneRules.of(List.of(), List.of(range)))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("header-from 的第 1 条不是合法的地址段")
			.hasMessageNotContaining(range);
	}

	@ParameterizedTest
	@ValueSource(strings = {"http://beta.example.com", "beta.example.com:8443", "beta.example.com/path", "*.example.com",
		"域名.example.com", "[2001:db8::1]"})
	void malformedHostIsRefusedWithoutBeingQuoted(String host) {
		assertThatThrownBy(() -> rules(byHost("canary", "beta.example.com", host)))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("灰度标记 canary 的 hosts 的第 2 条写法不对")
			.hasMessageNotContaining(host);
	}

	@Test
	void percentageMustBeBetweenZeroAndAHundred() {
		assertThat(rules(byPercent("canary", 0)).size()).isEqualTo(1);
		assertThat(rules(byPercent("canary", 10_000)).size()).isEqualTo(1);
		assertThatThrownBy(() -> rules(byPercent("canary", 10_001)))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessage("灰度标记 canary 的 percent 要在 0 到 100 之间");
		assertThatThrownBy(() -> rules(byPercent("canary", -1)))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessage("灰度标记 canary 的 percent 要在 0 到 100 之间");
	}

	/** 一项条件都没写，多半是写漏了：不当成「这个标记不分流量」悄悄收下 */
	@Test
	void laneWithoutAnyConditionIsRefused() {
		assertThatThrownBy(() -> rules(new Lane("canary", List.of(), List.of(), null)))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("灰度标记 canary 没有写分流条件")
			.hasMessageContaining("percent: 0");
		assertThatThrownBy(() -> rules(new Lane("canary", null, List.of(" , "), null)))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("灰度标记 canary 没有写分流条件");
	}
}
