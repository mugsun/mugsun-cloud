package com.mugsun.cloud.gateway.lane;

import com.mugsun.cloud.gateway.lane.LaneRules.Lane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 灰度规则的文本怎么读：列表和逗号隔开的一行都认；比例最多两位小数；不认识的东西算错；报错不带配置里的值。
 * 读出来的规则怎么判断请求在 LaneRulesTest 里测。
 */
class LaneRuleParserTest {

	private static LaneRules oneLane(String name, Integer basisPoints) {
		return LaneRules.of(List.of(new Lane(name, List.of(), List.of(), basisPoints)), List.of());
	}

	@Test
	void readsListsAndCommaSeparatedLines() {
		LaneRules rules = LaneRuleParser.parse("""
			# 灰度规则
			lanes:
			  - name: canary
			    client-ips: [203.0.113.7, 10.8.0.0/16]
			    hosts:
			      - beta.example.com
			    percent: 5
			  - name: v2
			    client-ips: 198.51.100.0/24, 198.51.100.7
			    hosts: beta2.example.com
			    percent: 0.5
			header-from: 10.0.0.0/8, 172.16.0.0/12
			""");

		assertThat(rules).isEqualTo(LaneRules.of(
			List.of(new Lane("canary", List.of("203.0.113.7", "10.8.0.0/16"), List.of("beta.example.com"), 500),
				new Lane("v2", List.of("198.51.100.0/24", "198.51.100.7"), List.of("beta2.example.com"), 50)),
			List.of("10.0.0.0/8", "172.16.0.0/12")));
		assertThat(rules.decide("10.8.1.1", "www.example.com", null)).isEqualTo("canary");
		assertThat(rules.decide("198.51.100.7", "www.example.com", null)).isEqualTo("v2");
		assertThat(rules.decide("172.16.9.9", "www.example.com", "hotfix-1")).isEqualTo("hotfix-1");
	}

	@Test
	void headerFromMayBeAListAndMayStandAlone() {
		LaneRules rules = LaneRuleParser.parse("""
			header-from:
			  - 10.0.0.0/8
			  - 203.0.113.7
			""");

		assertThat(rules).isEqualTo(LaneRules.of(List.of(), List.of("10.0.0.0/8", "203.0.113.7")));
	}

	/** 名字全是数字（比如用日期当名字）时加上引号：写的是什么，读出来就是什么 */
	@Test
	void nameMadeOfDigitsIsReadWhenQuoted() {
		assertThat(LaneRuleParser.parse("lanes:\n  - name: \"0105\"\n    percent: 1\n"))
			.isEqualTo(oneLane("0105", 100));
		assertThat(LaneRuleParser.parse("lanes:\n  - name: '20261121'\n    percent: 1\n"))
			.isEqualTo(oneLane("20261121", 100));
		assertThat(LaneRuleParser.parse("lanes:\n  - name: \"on\"\n    percent: 1\n"))
			.isEqualTo(oneLane("on", 100));
	}

	/**
	 * 没加引号的数字、日期和 on、off 这类词，YAML 读出来已经不是写的那几个字（0105 是 69，on 是 true）。
	 * 照这个名字去找实例，和实例上配的对不上，该进灰度的请求全去了正式实例，而且没有任何提示：一律要求加引号。
	 */
	@ParameterizedTest
	@ValueSource(strings = {"0105", "20261121", "7", "0x1f", "1_000", "1e3", "2.5", "on", "off", "yes", "no", "true",
		"False", "2026-11-21"})
	void nameThatYamlReadsAsSomethingElseMustBeQuoted(String written) {
		String content = "lanes:\n  - name: canary\n    percent: 1\n  - name: " + written + "\n    percent: 1\n";

		assertThatThrownBy(() -> LaneRuleParser.parse(content))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("第 2 个灰度标记的 name 要加引号，例如 name: \"0105\"。");
	}

	/** 要求加引号的提示里也不带配置里写的值 */
	@Test
	void nameThatMustBeQuotedIsNotQuotedBackInTheProblem() {
		assertThatThrownBy(() -> LaneRuleParser.parse("lanes:\n  - name: 31337\n    percent: 1\n"))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("第 1 个灰度标记的 name 要加引号")
			.hasMessageNotContaining("31337");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "  \n ", "# 只有注释\n", "lanes:\n", "lanes: []\n", "lanes: ~\n", "header-from:\n",
		"lanes:\nheader-from:\n", "lanes: []\nheader-from: []\n"})
	void emptyContentMeansNoRules(String content) {
		LaneRules rules = LaneRuleParser.parse(content);

		assertThat(rules.isEmpty()).isTrue();
		assertThat(rules).isEqualTo(LaneRules.NONE);
	}

	@ParameterizedTest
	@CsvSource({
		"0, 0",
		"5, 500",
		"100, 10000",
		"0.5, 50",
		"0.01, 1",
		"12.5, 1250",
		"12.50, 1250",
		"100.00, 10000",
		"'\"5\"', 500",
		"'\"0.25\"', 25",
		"'\" 7 \"', 700"})
	void percentageIsReadAsHundredthsOfAPercent(String written, int basisPoints) {
		assertThat(LaneRuleParser.parse("lanes:\n  - name: canary\n    percent: " + written + "\n"))
			.isEqualTo(oneLane("canary", basisPoints));
	}

	@ParameterizedTest
	@ValueSource(strings = {"5%", "-5", "0.555", "half", "1000", "0.00001", "true", "1,5", "\"5 %\""})
	void percentageWrittenAnyOtherWayIsRefused(String written) {
		assertThatThrownBy(() -> LaneRuleParser.parse(
			"lanes:\n  - name: other\n    percent: 1\n  - name: canary\n    percent: " + written + "\n"))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("第 2 个灰度标记的 percent 写得不对");
	}

	@ParameterizedTest
	@ValueSource(strings = {"101", "100.01", "999.99"})
	void percentageAboveAHundredIsRefused(String written) {
		assertThatThrownBy(() -> LaneRuleParser.parse("lanes:\n  - name: canary\n    percent: " + written + "\n"))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessage("灰度标记 canary 的 percent 要在 0 到 100 之间");
	}

	/** percent 留空和没写一样：没有别的条件时，这个标记就是一项条件都没有 */
	@Test
	void blankPercentageCountsAsNotWritten() {
		assertThat(LaneRuleParser.parse("lanes:\n  - name: canary\n    client-ips: 203.0.113.7\n    percent:\n"))
			.isEqualTo(LaneRules.of(List.of(new Lane("canary", List.of("203.0.113.7"), List.of(), null)), List.of()));
		assertThatThrownBy(() -> LaneRuleParser.parse("lanes:\n  - name: canary\n    percent:\n"))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("灰度标记 canary 没有写分流条件");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"lane:\n  - name: canary\n    percent: 5\n",
		"- name: canary\n  percent: 5\n",
		"just some text",
		"lanes:\n  - name: canary\n    percent: 5\nspring:\n  application:\n    name: x\n",
		// 多写的那一项的值是布尔或数字时也要发现
		"lanes:\n  - name: canary\n    percent: 5\nenabled: true\n",
		"version: 2\n",
		"header_from: 10.0.0.0/8\n"})
	void onlyLanesAndHeaderFromAreAcceptedAtTheTop(String content) {
		assertThatThrownBy(() -> LaneRuleParser.parse(content))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("最外层只能有 lanes 和 header-from 两项");
	}

	/** 把标记的名字当成了键：顺序决定谁先中，所以只认列表 */
	@Test
	void lanesWrittenAsAMapAreRefused() {
		assertThatThrownBy(() -> LaneRuleParser.parse("lanes:\n  canary:\n    percent: 5\n"))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("lanes 下面要写成列表");
		assertThatThrownBy(() -> LaneRuleParser.parse("header-from:\n  office: 10.0.0.0/8\n"))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("header-from 下面要写成列表");
	}

	/** 把 percent 写成 precent：不能当成「没写比例」悄悄收下，那样该进灰度的流量没进，还没有任何提示 */
	@Test
	void unknownItemInALaneIsAnErrorAndIsNamed() {
		assertThatThrownBy(() -> LaneRuleParser.parse("""
			lanes:
			  - name: canary
			    client-ips: 203.0.113.7
			    precent: 5
			"""))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("有不认识的配置项：lanes[0].precent。")
			.hasMessageContaining("name、client-ips、hosts、percent");
		assertThatThrownBy(() -> LaneRuleParser.parse("""
			lanes:
			  - name: canary
			    percent: 5
			    tenants: [t-001]
			"""))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageContaining("不认识的配置项")
			.hasMessageContaining("lanes[0].tenants[0]")
			.hasMessageNotContaining("t-001");
	}

	@Test
	void somethingOtherThanAnAddressRangeUnderHeaderFromIsAnError() {
		assertThatThrownBy(() -> LaneRuleParser.parse("header-from:\n  - range: 10.0.0.0/8\n"))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageContaining("header-from[0]")
			.hasMessageNotContaining("10.0.0.0");
	}

	@Test
	void lanesGivenAsPlainTextAreRefusedWithoutQuotingIt() {
		assertThatThrownBy(() -> LaneRuleParser.parse("lanes: canary-TOP-SECRET\n"))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessage("配置项 lanes 的值写得不对")
			.hasMessageNotContaining("TOP-SECRET");
	}

	/** 规则本身不合格（不是文本读不出来）：报的是 LaneRules 的检查结果，同样不带写的值 */
	@Test
	void problemsFoundInTheRulesAreReported() {
		assertThatThrownBy(() -> LaneRuleParser.parse("""
			lanes:
			  - name: canary
			    client-ips: [203.0.113.7, office-TOP-SECRET]
			"""))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("灰度标记 canary 的 client-ips 的第 2 条不是合法的地址段")
			.hasMessageNotContaining("TOP-SECRET");
		assertThatThrownBy(() -> LaneRuleParser.parse("lanes:\n  - name: canary\n"))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("灰度标记 canary 没有写分流条件");
		assertThatThrownBy(() -> LaneRuleParser.parse("lanes:\n  - percent: 5\n"))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessage("第 1 个灰度标记没写 name");
		assertThatThrownBy(() -> LaneRuleParser.parse(
			"lanes:\n  - name: canary\n    percent: 5\n  - name: canary\n    percent: 6\n"))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessage("灰度标记 canary 出现了不止一次");
	}

	@Test
	void brokenYamlGivesThePositionWithoutQuotingTheText() {
		String content = "lanes:\n  - name: canary\n    client-ips: [203.0.113.7, TOP-SECRET\n";

		assertThatThrownBy(() -> LaneRuleParser.parse(content))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("不是合法的 YAML：第 ")
			.hasMessageNotContaining("TOP-SECRET")
			.hasMessageNotContaining("\n");
	}

	/** 同一项写了两遍：不去猜该认哪一个 */
	@Test
	void sameItemWrittenTwiceIsRefused() {
		assertThatThrownBy(() -> LaneRuleParser.parse("lanes:\n  - name: canary\n    percent: 5\n    percent: 50\n"))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("不是合法的 YAML");
	}

	/** 一小段文本靠层层引用展开成极大的内容：YAML 解析器自带的上限要还在 */
	@Test
	void contentThatExpandsEndlesslyIsStopped() {
		StringBuilder content = new StringBuilder("a0: &a0 [x, x, x, x, x, x, x, x, x]\n");
		for (int level = 1; level <= 8; level++) {
			String previous = "*a" + (level - 1);
			content.append("a").append(level).append(": &a").append(level).append(" [")
				.append(String.join(", ", Collections.nCopies(9, previous))).append("]\n");
		}
		content.append("lanes: *a8\n");

		assertThatThrownBy(() -> LaneRuleParser.parse(content.toString()))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessageStartingWith("不是合法的 YAML");
	}

	@Test
	void limitsOfTheRulesApplyToTheText() {
		StringBuilder content = new StringBuilder("lanes:\n");
		for (int i = 0; i < 17; i++) {
			content.append("  - name: lane-").append(i).append("\n    percent: 0\n");
		}

		assertThatThrownBy(() -> LaneRuleParser.parse(content.toString()))
			.isInstanceOf(InvalidLaneRulesException.class)
			.hasMessage("灰度标记最多 16 个，现在写了 17 个");
	}
}
