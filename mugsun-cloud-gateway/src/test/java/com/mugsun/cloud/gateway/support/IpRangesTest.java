package com.mugsun.cloud.gateway.support;

import com.mugsun.boot.common.web.IpCidr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 把文字认成地址和地址段时只看写法：像地址、其实不是地址的写法按「不是地址」处理，不拿去查 DNS。
 *
 * <p>这个模块的测试里，像 IPv4 的名字都解析得出地址（见 {@link FakeIpDns}），拿去查了就会认成别的地址，所以测得出来。
 */
class IpRangesTest {

	/**
	 * 像地址、其实不是地址的写法。里面的主机名是这个测试独有的：查没查过 DNS 是整个测试进程一起记的，
	 * 用 localhost 这种别的测试也会去查的名字，结果就跟测试的先后顺序有关了
	 */
	private static final List<String> NOT_ADDRESSES = List.of("10.0.0.300", "999.1.1.1", "256.256.256.256", "0.0.0.256",
		"1.2.3", "1.2.3.4.5", "1.2.3.", ".1.2.3", "1..2.3", "1.2.3.1000", "a.b.c.d", "ip-ranges-test.invalid", "*",
		".::1", "g::1", "1:2", ":::", "12345::", "::ffff:1.2.3.400", "[::1", "::1]", "[[::1]]", "10.0.0.1 ", " ", "");

	@Test
	void inTheseTestsAnythingShapedLikeIpv4Resolves() throws UnknownHostException {
		// 测的是测试环境本身：假的 DNS 没生效的话，下面「不查 DNS」的检查就什么也没检查
		assertThat(InetAddress.getByName("10.0.0.999").getHostAddress()).isEqualTo(FakeIpDns.ANSWER);
		assertThat(FakeIpDns.lookups()).contains("10.0.0.999");
	}

	@Test
	void literalAddressesAreRecognised() {
		assertThat(IpRanges.bytes("203.0.113.7")).containsExactly(203, 0, 113, 7);
		assertThat(IpRanges.bytes("0.0.0.0")).containsExactly(0, 0, 0, 0);
		assertThat(IpRanges.bytes("255.255.255.255")).containsExactly(255, 255, 255, 255);
		// 前面补零的按十进制读
		assertThat(IpRanges.bytes("010.0.0.1")).containsExactly(10, 0, 0, 1);
		assertThat(IpRanges.bytes("::1")).hasSize(16).endsWith(0, 1);
		assertThat(IpRanges.bytes("2001:db8::1")).hasSize(16).startsWith(0x20, 0x01, 0x0d, 0xb8);
		assertThat(IpRanges.bytes("[2001:db8::1]")).isEqualTo(IpRanges.bytes("2001:db8::1"));
		assertThat(IpRanges.bytes("fe80::1%en0")).isEqualTo(IpRanges.bytes("fe80::1"));
		assertThat(IpRanges.bytes("::ffff:192.0.2.1")).containsExactly(192, 0, 2, 1);
	}

	@Test
	void whatOnlyLooksLikeAnAddressIsNotOne() {
		for (String text : NOT_ADDRESSES) {
			assertThat(IpRanges.bytes(text)).as("[%s]", text).isNull();
		}
		// 主机名不是地址，解析得出来也不算
		assertThat(IpRanges.bytes("localhost")).isNull();
		assertThat(IpRanges.bytes(null)).isNull();
	}

	@Test
	void whatIsNotAnAddressIsNeverLookedUp() {
		for (String text : NOT_ADDRESSES) {
			IpRanges.bytes(text);
			ClientView.canonicalIp(text);
			IpRanges.range(text);
			IpRanges.range(text + "/24");
		}

		assertThat(FakeIpDns.lookups()).doesNotContainAnyElementsOf(NOT_ADDRESSES);
	}

	@Test
	void listIsSplitOnCommasSemicolonsAndBlanks() {
		List<IpCidr> ranges = IpRanges.parse(" 203.0.113.7, 10.0.0.0/8;2001:db8::/32\n192.168.* ", "mugsun.gateway.deny-ips");

		assertThat(ranges).hasSize(4);
		for (String inside : List.of("203.0.113.7", "10.9.9.9", "2001:db8:1::5", "192.168.3.4")) {
			assertThat(IpCidr.anyContains(ranges, inside)).as(inside).isTrue();
		}
		for (String outside : List.of("203.0.113.8", "11.0.0.1", "2001:db9::1", "192.169.0.1")) {
			assertThat(IpCidr.anyContains(ranges, outside)).as(outside).isFalse();
		}
	}

	@Test
	void emptyListHasNoRanges() {
		assertThat(IpRanges.parse(null, "x")).isEmpty();
		assertThat(IpRanges.parse("", "x")).isEmpty();
		assertThat(IpRanges.parse(" , ;", "x")).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"10.0.0.300", "10.0.0.300/24", "999.1.1.1", "10.0.0.0/33", "10.0.0.0/x", "10.0.0.0/",
		"10.0.0", "example.com", "10.*.1.*", "300.*", "*", "2001:db8::/129", "g::1", "1:2"})
	void oneWrongEntryRefusesTheWholeListAndNamesTheSetting(String wrong) {
		assertThatThrownBy(() -> IpRanges.parse("203.0.113.7, " + wrong + ", 10.0.0.0/8", "mugsun.gateway.deny-ips"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("mugsun.gateway.deny-ips 里的地址段不合法：" + wrong + "。");
	}
}
