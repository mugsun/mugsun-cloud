package com.mugsun.cloud.gateway;

import com.mugsun.cloud.gateway.route.DynamicRouteLoader;
import com.mugsun.cloud.gateway.route.DynamicRouteLoader.Outcome;
import com.mugsun.cloud.gateway.route.DynamicRouteLoader.Status;
import com.mugsun.cloud.gateway.route.NacosRouteSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.event.FilterArgsEvent;
import org.springframework.cloud.gateway.event.PredicateArgsEvent;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 动态路由：网关运行着的时候换一份路由配置，加的、改的、删的马上生效；不合格的整份不用，原来的不动；
 * 内部接口拦截和地址名单对动态路由改写过的路径照样生效。
 *
 * <p>这里直接把配置文本交给 {@link DynamicRouteLoader}，不连 Nacos；假后端用写死的实例列表登记成
 * report、billing 两个服务。没有动态路由时，请求走内置的兜底路由，路径原样到假后端。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"spring.cloud.nacos.discovery.enabled=false",
	"spring.cloud.service-registry.auto-registration.enabled=false",
	"mugsun.gateway.dynamic-routes.enabled=true",
	"mugsun.gateway.trusted-proxies=127.0.0.1/32",
	"mugsun.gateway.restricted-paths=/actuator",
	"mugsun.gateway.restricted-allow-ips=198.51.100.0/24"})
class GatewayDynamicRoutesTest extends GatewayTestBase {

	private static final Set<String> BUILT_IN = Set.of("dead", "unregistered", "backend");

	/** /report/** 转给 report 服务，去掉路径的第一段 */
	private static final String REPORT = """
		routes:
		  - id: report
		    uri: lb://report
		    predicates:
		      - Path=/report/**
		    filters:
		      - StripPrefix=1
		""";

	/** 接在 REPORT 后面的第二条 */
	private static final String BILLING = """
		  - id: billing
		    uri: lb://billing
		    predicates:
		      - Path=/billing/**
		    filters:
		      - StripPrefix=1
		""";

	/** 一条写得对的路由，后面用来接一条有问题的 */
	private static final String EXTRA = """
		routes:
		  - id: extra
		    uri: lb://report
		    predicates:
		      - Path=/extra/**
		    filters:
		      - StripPrefix=1
		""";

	@Autowired
	DynamicRouteLoader loader;

	@Autowired
	RouteLocator routeLocator;

	@Autowired
	ApplicationContext context;

	@DynamicPropertySource
	static void services(DynamicPropertyRegistry registry) {
		String prefix = "spring.cloud.discovery.client.simple.instances.";
		registry.add(prefix + "report[0].uri", () -> "http://127.0.0.1:" + backendPort());
		registry.add(prefix + "billing[0].uri", () -> "http://127.0.0.1:" + backendPort());
	}

	@BeforeEach
	void startWithoutDynamicRoutes() {
		loader.load("");
		awaitDynamicRoutes();
	}

	@Test
	void routeAddedAtRuntimeIsUsedAtOnce() {
		assertThat(forwarded("/report/daily?day=1")).isEqualTo("/report/daily?day=1");

		use(REPORT, "report");

		// 路径去掉了第一段：走的是新加的这条，而且它排在内置的兜底路由前面
		assertThat(forwarded("/report/daily?day=1")).isEqualTo("/daily?day=1");
		assertThat(forwarded("/other/daily")).isEqualTo("/other/daily");
	}

	@Test
	void changedRouteReplacesTheOldOne() {
		use(REPORT, "report");

		use(REPORT.replace("StripPrefix=1", "PrefixPath=/v2"), "report");

		assertThat(forwarded("/report/daily")).isEqualTo("/v2/report/daily");
	}

	@Test
	void routeTakenOutOfTheContentIsGone() {
		use(REPORT + BILLING, "report", "billing");
		assertThat(forwarded("/billing/list")).isEqualTo("/list");

		use(REPORT, "report");

		assertThat(forwarded("/billing/list")).isEqualTo("/billing/list");
		assertThat(forwarded("/report/daily")).isEqualTo("/daily");
	}

	/** 配置被清空或者整份删掉：动态路由一条不剩 */
	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "routes:\n", "# 先全部停用\n"})
	void emptyContentTakesAllDynamicRoutesAway(String content) {
		use(REPORT, "report");

		Outcome outcome = loader.load(content);

		assertThat(outcome.status()).isEqualTo(Status.UPDATED);
		assertThat(outcome.routes()).isZero();
		awaitDynamicRoutes();
		assertThat(forwarded("/report/daily")).isEqualTo("/report/daily");
	}

	@Test
	void sameRoutesAgainDoNothing() {
		use(REPORT, "report");

		assertThat(loader.load(REPORT).status()).isEqualTo(Status.UNCHANGED);
		assertThat(loader.load("# 只是加了一行注释\n" + REPORT).status()).isEqualTo(Status.UNCHANGED);

		assertThat(forwarded("/report/daily")).isEqualTo("/daily");
	}

	static Stream<Arguments> unacceptable() {
		String[] condition = {"predicates:", "  - Path=/bad/**"};
		return Stream.of(
			arguments("最外层不是 routes", EXTRA.replace("routes:", "route:"), "最外层只能有 routes"),
			arguments("不是合法的 YAML", EXTRA + "  - id: [bad\n", "不是合法的 YAML"),
			arguments("没写 id", after("uri: lb://report", condition), "第 2 条路由的 id"),
			arguments("id 里有空格", after("id: \"bad id\"", "uri: lb://report", condition), "第 2 条路由的 id"),
			arguments("id 重复", after("id: extra", "uri: lb://report", condition), "路由 id extra 出现了不止一次"),
			arguments("和内置路由重名", after("id: backend", "uri: lb://report", condition),
				"路由 id backend 和 application.yml 里的路由重名"),
			arguments("没写 uri", after("id: bad", condition), "路由 bad 没写 uri"),
			arguments("转到写死的地址", after("id: bad", "uri: http://127.0.0.1:1", condition), "只能写成 lb://服务名"),
			arguments("uri 带路径", after("id: bad", "uri: lb://report/api", condition), "只能写成 lb://服务名"),
			arguments("uri 带端口", after("id: bad", "uri: lb://report:8080", condition), "只能写成 lb://服务名"),
			arguments("服务名带下划线", after("id: bad", "uri: lb://my_report", condition), "只能写成 lb://服务名"),
			arguments("转给网关自己", after("id: bad", "uri: lb://mugsun-gateway", condition), "指向网关自己"),
			arguments("没写条件", after("id: bad", "uri: lb://report"), "路由 bad 没写 predicates"),
			arguments("按直接连上来的地址判断", after("id: bad", "uri: lb://report", "predicates:", "  - RemoteAddr=10.0.0.0/8"),
				"不能用的断言 RemoteAddr"),
			arguments("按比例分流", after("id: bad", "uri: lb://report", "predicates:", "  - Weight=group1, 8"),
				"不能用的断言 Weight"),
			arguments("没有这种断言", after("id: bad", "uri: lb://report", "predicates:", "  - Nope=1"), "不能用的断言 Nope"),
			arguments("加请求头", withFilter("AddRequestHeader=X-Forwarded-For, 203.0.113.9"),
				"不能用的过滤器 AddRequestHeader"),
			arguments("改请求头", withFilter("SetRequestHeader=X-Forwarded-For, 203.0.113.9"),
				"不能用的过滤器 SetRequestHeader"),
			arguments("删请求头", withFilter("RemoveRequestHeader=X-Forwarded-For"), "不能用的过滤器 RemoveRequestHeader"),
			arguments("按请求头决定转到哪", withFilter("RequestHeaderToRequestUri=X-Target"),
				"不能用的过滤器 RequestHeaderToRequestUri"),
			arguments("让浏览器跳转", withFilter("RedirectTo=302, https://example.org"), "不能用的过滤器 RedirectTo"),
			arguments("改响应头", withFilter("AddResponseHeader=Access-Control-Allow-Origin, *"),
				"不能用的过滤器 AddResponseHeader"),
			arguments("没有这种过滤器", withFilter("Nope=1"), "不能用的过滤器 Nope"),
			arguments("过滤器的参数不对", withFilter("StripPrefix=many"), "路由 bad 的第 1 个过滤器（StripPrefix）参数写得不对"),
			arguments("路径的写法不对", after("id: bad", "uri: lb://report", "predicates:", "  - Method=GET",
				"  - Path=/bad/{*rest}/more"), "路由 bad 的第 2 个断言（Path）参数写得不对"),
			arguments("写了 metadata", after("id: bad", "uri: lb://report", "predicates:", "  - Path=/bad/**", "metadata:",
				"  response-timeout: 1000"), "路由 bad 写了 metadata"),
			arguments("配置项的名字写错", after("id: bad", "uri: lb://report", "predicate:", "  - Path=/bad/**"),
				"不认识的配置项：routes[1].predicate[0]"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("unacceptable")
	void unacceptableContentIsRefusedAsAWholeAndTheOldRoutesStay(String what, String content, String problem) {
		use(REPORT, "report");

		Outcome outcome = loader.load(content);

		assertThat(outcome.status()).isEqualTo(Status.REJECTED);
		assertThat(outcome.problem()).contains(problem);
		assertThat(outcome.routes()).isEqualTo(1);
		assertThat(dynamicRoutesInUse()).containsExactly("report");
		assertThat(forwarded("/report/daily")).isEqualTo("/daily");
		// 这份里写得对的那一条也没有用上
		assertThat(forwarded("/extra/x")).isEqualTo("/extra/x");
	}

	/** 路由里可能写着只有自己人知道的值（比如要求带某个请求头），报错会进日志，不能把值带出去 */
	@Test
	void problemNeverQuotesValuesFromTheContent() {
		Outcome outcome = loader.load(after("id: bad", "uri: lb://report", "predicates:", "  - Path=/bad/**",
			"  - Header=X-Key, s3cr3t-value("));

		assertThat(outcome.status()).isEqualTo(Status.REJECTED);
		assertThat(outcome.problem()).contains("路由 bad 的第 2 个断言（Header）").doesNotContain("s3cr3t");
	}

	@ParameterizedTest
	@ValueSource(strings = {"StripPrefix=1", "SetPath=/inner/jobs", "PrefixPath=/inner",
		"RewritePath=/open/(?<rest>.*), /$\\{rest}"})
	void pathRewrittenIntoAnInnerInterfaceIsStillBlocked(String filter) {
		assertThat(forwarded("/open/inner/jobs")).isEqualTo("/open/inner/jobs");
		use(open(filter), "open");
		RECEIVED.clear();

		client().post().uri(raw("/open/inner/jobs")).exchange()
			.expectStatus().isNotFound()
			.expectBody()
			.jsonPath("$.code").isEqualTo(404)
			.jsonPath("$.msg").isEqualTo("资源不存在");

		assertThat(RECEIVED).isEmpty();
	}

	/** application.yml 里 ${rest} 会被当成占位符，所以习惯写成 $\{rest}。这份配置不解析占位符，两种写法都认 */
	@ParameterizedTest
	@ValueSource(strings = {"/to/${rest}", "/to/$\\{rest}"})
	void rewritePathTakesBothWaysOfWritingAGroupReference(String replacement) {
		use(open("RewritePath=/open/(?<rest>.*), " + replacement), "open");

		assertThat(forwarded("/open/a/b?x=1")).isEqualTo("/to/a/b?x=1");
	}

	@Test
	void pathRewrittenIntoARestrictedOneStillNeedsAnAllowedAddress() {
		use(open("StripPrefix=1"), "open");
		RECEIVED.clear();

		client().get().uri(raw("/open/actuator/health")).header("X-Forwarded-For", "192.0.2.1").exchange()
			.expectStatus().isForbidden()
			.expectBody().jsonPath("$.msg").isEqualTo("这个地址不对外开放");
		assertThat(RECEIVED).isEmpty();

		client().get().uri(raw("/open/actuator/health")).header("X-Forwarded-For", "198.51.100.7").exchange()
			.expectStatus().isOk();
		assertThat(RECEIVED).hasSize(1);
		assertThat(RECEIVED.get(0).uri()).isEqualTo("/actuator/health");
		assertThat(RECEIVED.get(0).header("X-Forwarded-For")).containsExactly("198.51.100.7");
	}

	@Test
	void orderDecidesWhichRouteComesFirst() {
		use("""
			routes:
			  - id: later
			    uri: lb://report
			    order: 5
			    predicates:
			      - Path=/same/**
			    filters:
			      - PrefixPath=/later
			  - id: earlier
			    uri: lb://report
			    order: -5
			    predicates:
			      - Path=/same/**
			    filters:
			      - PrefixPath=/earlier
			""", "later", "earlier");

		assertThat(forwarded("/same/x")).isEqualTo("/earlier/same/x");
	}

	@Test
	void serviceThatIsNotRegisteredGives503LikeABuiltInRoute() {
		use(REPORT.replace("lb://report", "lb://nobody-home"), "report");

		client().get().uri(raw("/report/daily")).exchange()
			.expectStatus().isEqualTo(503)
			.expectBody()
			.jsonPath("$.code").isEqualTo(503)
			.jsonPath("$.msg").isEqualTo("服务暂时不可用，请稍后再试");
	}

	@Test
	void websocketGoesThroughADynamicRoute() {
		use(REPORT, "report");
		RECEIVED.clear();
		AtomicReference<String> reply = new AtomicReference<>();

		new ReactorNettyWebSocketClient()
			.execute(URI.create("ws://127.0.0.1:" + gatewayPort + "/report/ws/message"),
				session -> session.send(Mono.just(session.textMessage("ping")))
					.thenMany(session.receive().take(1).map(WebSocketMessage::getPayloadAsText).doOnNext(reply::set))
					.then())
			.block(Duration.ofSeconds(5));

		assertThat(reply.get()).isEqualTo("echo:ping");
		assertThat(RECEIVED).hasSize(1);
		assertThat(RECEIVED.get(0).uri()).isEqualTo("/ws/message");
	}

	/** 转换动态路由不发框架的参数事件：一份配置不管收不收，都不会在别处留下东西 */
	@Test
	void loadingDoesNotPublishArgumentEvents() {
		ArgumentEvents.ROUTE_IDS.clear();

		use(REPORT, "report");
		loader.load(withFilter("StripPrefix=many"));

		// 刷新路由表时内置路由会重新转换一遍，它们的事件是有的
		assertThat(ArgumentEvents.ROUTE_IDS).contains("backend").doesNotContain("report", "extra", "bad");
	}

	@Test
	void routesAreReadBeforeTheGatewayStartsTakingRequests() {
		int webServerPhase = context.getBeansOfType(SmartLifecycle.class).values().stream()
			.filter(bean -> bean.getClass().getSimpleName().equals("WebServerStartStopLifecycle"))
			.mapToInt(SmartLifecycle::getPhase)
			.findFirst().orElseThrow();
		NacosRouteSource source = new NacosRouteSource("routes.yaml", "DEFAULT_GROUP", () -> null, loader);

		assertThat(source.getPhase()).isLessThan(webServerPhase);
	}

	/** 没有启用 Nacos 时不去连它 */
	@Test
	void nothingReadsFromNacosWhenNacosIsOff() {
		assertThat(context.getBeansOfType(NacosRouteSource.class)).isEmpty();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ArgumentEvents {

		static final List<String> ROUTE_IDS = new CopyOnWriteArrayList<>();

		@EventListener
		public void onPredicateArgs(PredicateArgsEvent event) {
			ROUTE_IDS.add(event.getRouteId());
		}

		@EventListener
		public void onFilterArgs(FilterArgsEvent event) {
			ROUTE_IDS.add(event.getRouteId());
		}
	}

	/** 换上一份配置，确认它被收下，并等到网关的路由表里正好是这几条动态路由 */
	private void use(String content, String... ids) {
		Outcome outcome = loader.load(content);
		assertThat(outcome.status()).as("%s", outcome.problem()).isEqualTo(Status.UPDATED);
		assertThat(outcome.routes()).isEqualTo(ids.length);
		awaitDynamicRoutes(ids);
	}

	private void awaitDynamicRoutes(String... ids) {
		await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(dynamicRoutesInUse()).containsExactlyInAnyOrder(ids));
	}

	/** 网关正在用的路由表里，除了内置的还有哪几条 */
	private List<String> dynamicRoutesInUse() {
		List<String> all = routeLocator.getRoutes().map(Route::getId).collectList().block(Duration.ofSeconds(5));
		return all.stream().filter(id -> !BUILT_IN.contains(id)).toList();
	}

	/** 发一个 GET 请求，返回假后端收到的地址 */
	private String forwarded(String pathAndQuery) {
		RECEIVED.clear();
		client().get().uri(raw(pathAndQuery)).exchange().expectStatus().isOk();
		assertThat(RECEIVED).hasSize(1);
		return RECEIVED.get(0).uri();
	}

	/** /open/** 转给 report 服务，路上用给定的过滤器改路径 */
	private static String open(String filter) {
		return "routes:\n  - id: open\n    uri: lb://report\n    predicates:\n      - Path=/open/**\n    filters:\n      - "
			+ filter + "\n";
	}

	/** 在一条写得对的路由后面再接一条，内容是给定的这几行 */
	private static String after(Object... lines) {
		StringBuilder second = new StringBuilder();
		for (Object line : lines) {
			for (String each : line instanceof String[] many ? many : new String[] {(String) line}) {
				second.append(second.isEmpty() ? "  - " : "    ").append(each).append('\n');
			}
		}
		return EXTRA + second;
	}

	private static String withFilter(String filter) {
		return after("id: bad", "uri: lb://report", "predicates:", "  - Path=/bad/**", "filters:", "  - " + filter);
	}
}
