package com.mugsun.cloud.gateway.lane;

import com.mugsun.cloud.config.ConfigText;
import com.mugsun.cloud.config.ConfigYaml;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一份灰度规则的文本读出来。写法：
 *
 * <pre>
 * lanes:
 *   - name: canary
 *     client-ips: [203.0.113.7, 10.8.0.0/16]
 *     hosts: [beta.example.com]
 *     percent: 5
 * header-from: [10.0.0.0/8]
 * </pre>
 *
 * <p>{@code client-ips}、{@code hosts}、{@code header-from} 可以写成列表，也可以写成逗号隔开的一行。每一项的含义和谁先谁后见
 * {@link LaneRules}。
 *
 * <p>不认识的配置项一律算错，不悄悄忽略：把 percent 写成 precent 要是被当成没写，该进灰度的流量就没进，而且没有任何提示。
 * YAML 怎么读见 {@link ConfigYaml}。
 *
 * <p>名字没加引号、YAML 又把它读成了数字、日期或者真假值时也算错，要求加引号：0105 读出来是 69，on 读出来是 true，
 * 照这个名字去找实例，和实例上配的对不上，该进灰度的请求全去了正式实例，同样没有任何提示。
 */
final class LaneRuleParser {

	private static final String LANES = "lanes";
	private static final String HEADER_FROM = "header-from";

	/** 比例的写法：整数，或者带一两位小数。范围在 {@link LaneRules#of} 里检查 */
	private static final Pattern PERCENT = Pattern.compile("\\d{1,3}(\\.\\d{1,2})?");

	/** 一个灰度标记的名字在读出来的属性里的键：「lanes[3].name」 */
	private static final Pattern NAME_KEY = Pattern.compile("lanes\\[(\\d+)]\\.name");

	private LaneRuleParser() {
	}

	/** 配置里一个灰度标记的原样。每一项都按文字读进来，写得对不对由后面检查，报错才说得清是哪一项 */
	static class Entry {

		private String name;
		private List<String> clientIps = new ArrayList<>();
		private List<String> hosts = new ArrayList<>();
		private String percent;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public List<String> getClientIps() {
			return clientIps;
		}

		public void setClientIps(List<String> clientIps) {
			this.clientIps = clientIps;
		}

		public List<String> getHosts() {
			return hosts;
		}

		public void setHosts(List<String> hosts) {
			this.hosts = hosts;
		}

		public String getPercent() {
			return percent;
		}

		public void setPercent(String percent) {
			this.percent = percent;
		}
	}

	/**
	 * @param content 配置的全文；空的（没有这份配置、只有注释、两项下面都什么也没有）表示没有灰度规则
	 * @throws InvalidLaneRulesException 读不出来，有不认识的配置项，或者有一处写得不对
	 */
	static LaneRules parse(String content) {
		if (content == null || content.isBlank()) {
			return LaneRules.NONE;
		}
		Properties flat;
		try {
			flat = ConfigYaml.flatten(content);
		} catch (ConfigYaml.NotYamlException e) {
			throw new InvalidLaneRulesException("不是合法的 YAML：" + e.getMessage());
		}
		// 要看所有的键：stringPropertyNames() 不包括值是数字或布尔的那些
		for (Map.Entry<Object, Object> item : flat.entrySet()) {
			String name = String.valueOf(item.getKey());
			String top = topLevel(name);
			if (!top.equals(LANES) && !top.equals(HEADER_FROM)) {
				throw new InvalidLaneRulesException("最外层只能有 lanes 和 header-from 两项：灰度标记写在 lanes 下面，"
					+ "能用请求头自己指定标记的地址段写在 header-from 下面");
			}
			// 「lanes.canary.percent」：把标记的名字当成了键，没有写成列表
			if (name.startsWith(".", top.length())) {
				throw new InvalidLaneRulesException(top.equals(LANES)
					? "lanes 下面要写成列表：每个灰度标记前面加一个横线，名字写在 name 里"
					: "header-from 下面要写成列表（每个地址段前面加一个横线），或者写成逗号隔开的一行");
			}
			// 加了引号的名字读出来是文字；不是文字，就是 YAML 把它当成数字、日期或者真假值换算过了，原来写的字已经找不回来
			Matcher nameKey = NAME_KEY.matcher(name);
			if (nameKey.matches() && !(item.getValue() instanceof String)) {
				throw new InvalidLaneRulesException("第 " + (Integer.parseInt(nameKey.group(1)) + 1)
					+ " 个灰度标记的 name 要加引号，例如 name: \"0105\"。名字是纯数字、日期，或者是 on、off、yes、no、true、false 时，"
					+ "不加引号 YAML 读出来的就不是写的那几个字（0105 会读成 69），和实例上配的 MUGSUN_LANE 对不上");
			}
		}
		Binder binder = new Binder(new MapConfigurationPropertySource(flat));
		List<Entry> entries = bind(binder, LANES, Bindable.listOf(Entry.class),
			"一个灰度标记里能写的只有 name、client-ips、hosts、percent");
		List<String> headerFrom = bind(binder, HEADER_FROM, Bindable.listOf(String.class),
			"header-from 下面只能写地址段");
		List<LaneRules.Lane> lanes = new ArrayList<>(entries.size());
		for (int i = 0; i < entries.size(); i++) {
			Entry entry = entries.get(i);
			lanes.add(new LaneRules.Lane(entry.getName(), entry.getClientIps(), entry.getHosts(),
				basisPoints(entry.getPercent(), i + 1)));
		}
		return LaneRules.of(lanes, headerFrom);
	}

	/** 「lanes[0].client-ips[1]」最外层的那个名字：lanes */
	private static String topLevel(String key) {
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if (c == '.' || c == '[') {
				return key.substring(0, i);
			}
		}
		return key;
	}

	private static <T> List<T> bind(Binder binder, String name, Bindable<List<T>> target, String allowed) {
		try {
			return binder.bind(name, target, new NoUnboundElementsBindHandler(BindHandler.DEFAULT)).orElseGet(List::of);
		} catch (BindException e) {
			String unknown = ConfigYaml.unknownNames(e);
			if (unknown != null) {
				throw new InvalidLaneRulesException("有不认识的配置项：" + unknown + "。" + allowed);
			}
			throw new InvalidLaneRulesException("配置项 " + ConfigText.name(e.getName().toString()) + " 的值写得不对");
		}
	}

	/** 百分之几换成万分之几；没写返回 null */
	private static Integer basisPoints(String percent, int position) {
		if (percent == null || percent.isBlank()) {
			return null;
		}
		String written = percent.strip();
		if (!PERCENT.matcher(written).matches()) {
			throw new InvalidLaneRulesException("第 " + position
				+ " 个灰度标记的 percent 写得不对：写 0 到 100 之间的数字，最多两位小数，不带百分号，例如 5 或 0.5");
		}
		return new BigDecimal(written).movePointRight(2).intValueExact();
	}
}
