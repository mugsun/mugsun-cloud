package com.mugsun.cloud.common.inner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * {@code /inner} 下的请求全部由这个过滤器处理：验签通过就调用对应的 {@link InnerEndpoint}，不通过就拒绝，不再往后传。
 *
 * <p>单体版里各模块在一个进程里，互相调用是方法调用。拆开以后要走网络，这些接口不过登录拦截器，所以必须另有一道校验，
 * 证明请求来自自己人。网关不转发 {@code /inner}，那是第一道；这里是第二道，绕过网关直接连到服务端口的人也调不动。
 *
 * <p>校验按这个顺序做，任何一步不过就拒绝：
 * <ol>
 *   <li>配了密钥（没配就全部拒绝）；只接受 POST、JSON、不带查询参数。</li>
 *   <li>五个请求头都在，格式合规；时间戳和本机时间差得不多。</li>
 *   <li>请求体不超过上限（边读边限，不会把一个很大的请求体读进内存）。</li>
 *   <li>签名对。签名里有目标服务名，这边用自己的服务名去算，所以发给别的服务的请求拿到这里用不了。</li>
 *   <li>随机串没用过（记在 Redis 里，记不进去也拒绝）。签名对了才记，没有密钥的人没法往 Redis 里灌数据。</li>
 * </ol>
 * 没通过校验时不告诉对方具体错在哪，原因只记在日志里；日志不记签名，也不记没校验过格式的请求头。
 *
 * <p>接口在 {@code TenantContext.execute(调用方传来的租户, ...)} 里执行，这时没有登录用户。成功、业务失败和「忙」都返回 200，
 * 响应带签名；其它情况返回不带签名的错误状态码，调用方一律当作调用失败。
 *
 * <p>每个接口同时最多处理 {@code mugsun.cloud.inner.max-concurrent} 个请求。满了的时候新来的先等
 * {@code mugsun.cloud.inner.max-wait}，还轮不到就回「忙」，这次不执行，调用方可以换一个实例。这样一个接口的查询卡住时，
 * 它占住的数据库连接有上限，本服务别的接口还有连接可用。按接口分开限，一个满了不影响别的；验签通过的请求才来占名额。
 *
 * <p>调用方在请求头里说了最多等多久的，处理期间发出的查询到点就取消（{@link InnerQueryTimeout}）。
 */
@Component
@Order(InnerRequestFilter.ORDER)
public class InnerRequestFilter extends OncePerRequestFilter implements SmartInitializingSingleton {

	/**
	 * 排在 TraceIdFilter（-200）之后，日志里带链路号；排在演示只读（-150）、演示限流（-140）、请求体缓存（-100）、
	 * 访问日志（-95）这些过滤器之前：它们针对的是用户请求，用户请求在调用方那边已经过了一遍。
	 */
	public static final int ORDER = -160;

	static final String PREFIX = "/inner";

	/** 排队排了这么多毫秒以上的，在日志里写上排了多久 */
	private static final long QUEUED_WORTH_LOGGING = 100;

	private static final Logger log = LoggerFactory.getLogger(InnerRequestFilter.class);

	private final InnerProperties properties;

	private final InnerNonceStore nonceStore;

	private final ObjectMapper objectMapper;

	private final String self;

	private final Supplier<List<InnerEndpoint>> endpointSource;

	private final LongSupplier clock;

	private volatile Map<String, InnerEndpoint> endpoints = Map.of();

	/** 每个接口的名额，和 {@link #endpoints} 的路径一一对应 */
	private volatile Map<String, Gate> gates = Map.of();

	@Autowired
	public InnerRequestFilter(InnerProperties properties, InnerNonceStore nonceStore, ObjectMapper objectMapper,
							  @Value("${spring.application.name}") String self,
							  ObjectProvider<InnerEndpoint> endpointBeans) {
		this(properties, nonceStore, objectMapper, self, () -> endpointBeans.orderedStream().toList(),
			System::currentTimeMillis);
	}

	InnerRequestFilter(InnerProperties properties, InnerNonceStore nonceStore, ObjectMapper objectMapper, String self,
					   Supplier<List<InnerEndpoint>> endpointSource, LongSupplier clock) {
		if (!InnerProtocol.SERVICE_NAME.matcher(self).matches()) {
			throw new IllegalStateException("spring.application.name（" + self + "）只能用字母、数字、点、下划线和短横线，"
				+ "最长 64 个字符：服务之间互相调用时要把它写进请求头");
		}
		this.properties = properties;
		this.nonceStore = nonceStore;
		this.objectMapper = objectMapper;
		this.self = self;
		this.endpointSource = endpointSource;
		this.clock = clock;
	}

	/**
	 * 所有 Bean 都建好以后再收集接口（过滤器本身建得很早，那时候去取会把一串业务 Bean 提前建出来）。
	 * 路径不合规或者重复就拒绝启动。
	 */
	@Override
	public void afterSingletonsInstantiated() {
		Map<String, InnerEndpoint> indexed = index(endpointSource.get());
		Map<String, Gate> built = new HashMap<>();
		indexed.keySet().forEach(path -> built.put(path, new Gate(properties.getMaxConcurrent())));
		this.gates = Map.copyOf(built);
		this.endpoints = indexed;
		if (!properties.configured()) {
			log.info("没有配置 mugsun.cloud.inner.secret（环境变量 MUGSUN_INNER_SECRET），服务之间的内部调用停用："
				+ "本服务不接受 /inner 请求，也不向别的服务发起调用");
		} else if (!endpoints.isEmpty()) {
			log.info("内部接口 {} 个：{}，每个同时最多处理 {} 个请求", endpoints.size(), new TreeSet<>(endpoints.keySet()),
				properties.getMaxConcurrent());
		}
	}

	static Map<String, InnerEndpoint> index(List<InnerEndpoint> list) {
		Map<String, InnerEndpoint> map = new HashMap<>();
		for (InnerEndpoint endpoint : list) {
			String path = endpoint.path();
			if (path == null || !InnerProtocol.PATH.matcher(path).matches()) {
				throw new IllegalStateException("内部接口 " + endpoint.getClass().getName() + " 的路径不合规：" + path
					+ "。要以 /inner/ 开头，每段只用小写字母、数字和短横线");
			}
			InnerEndpoint other = map.putIfAbsent(path, endpoint);
			if (other != null) {
				throw new IllegalStateException("内部接口的路径重复：" + path + "（" + other.getClass().getName() + " 和 "
					+ endpoint.getClass().getName() + "）");
			}
		}
		return Map.copyOf(map);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String path = pathOf(request);
		if (!underInner(path)) {
			chain.doFilter(request, response);
			return;
		}
		serve(request, response, path);
	}

	/** 客户端发来的原始路径（没有解码，也没有去掉 ; 参数），去掉 context-path */
	static String pathOf(HttpServletRequest request) {
		String uri = request.getRequestURI();
		if (uri == null) {
			return "";
		}
		String contextPath = request.getContextPath();
		if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
			return uri.substring(contextPath.length());
		}
		return uri;
	}

	/**
	 * 只认原样写的 {@code /inner} 和 {@code /inner/...}。带编码、双斜杠、; 参数的写法不在这里处理，照常往后传：
	 * 后面没有任何 Controller 挂在 /inner 下（{@link InnerMappingGuard} 保证），它们只会得到 404 或 401。
	 * 内部接口只能从下面的 {@link #serve} 进去，而那里按原始路径逐字查找，所以没有绕过验签的写法。
	 */
	static boolean underInner(String path) {
		return path.equals(PREFIX) || path.startsWith(PREFIX + "/");
	}

	private void serve(HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
		long arrived = System.nanoTime();
		if (!properties.configured()) {
			refuse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "disabled", "这个服务没有启用内部调用");
			return;
		}
		if (!"POST".equals(request.getMethod())) {
			response.setHeader("Allow", "POST");
			refuse(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method", "内部接口只接受 POST");
			return;
		}
		if (request.getQueryString() != null) {
			refuse(response, HttpServletResponse.SC_BAD_REQUEST, "query", "内部接口不接受查询参数");
			return;
		}
		if (!isJson(request.getContentType())) {
			refuse(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "content-type", "内部接口只接受 JSON");
			return;
		}

		String caller = request.getHeader(InnerProtocol.HEADER_CALLER);
		String tenant = request.getHeader(InnerProtocol.HEADER_TENANT);
		String timestamp = request.getHeader(InnerProtocol.HEADER_TIMESTAMP);
		String nonce = request.getHeader(InnerProtocol.HEADER_NONCE);
		String signature = request.getHeader(InnerProtocol.HEADER_SIGNATURE);
		String malformed = malformedHeader(caller, tenant, timestamp, nonce, signature);
		if (malformed != null) {
			deny(request, response, path, "请求头 " + malformed + " 缺失或者格式不对");
			return;
		}
		long skewMillis = properties.getClockSkew().toMillis();
		long drift = clock.getAsLong() - Long.parseLong(timestamp);
		if (Math.abs(drift) > skewMillis) {
			deny(request, response, path, "调用方 " + caller + " 的时间和本机差了 " + drift / 1000 + " 秒，超过上限 "
				+ skewMillis / 1000 + " 秒（两台机器的时间没有对齐，或者是一个过期的请求）");
			return;
		}

		int maxBody = (int) properties.getMaxRequestSize().toBytes();
		if (request.getContentLengthLong() > maxBody) {
			tooLarge(response);
			return;
		}
		byte[] body = request.getInputStream().readNBytes(maxBody + 1);
		if (body.length > maxBody) {
			tooLarge(response);
			return;
		}

		String expected = InnerProtocol.signRequest(properties.key(), "POST", path, timestamp, nonce, caller, self,
			tenant, body);
		if (!InnerProtocol.sameSignature(expected, signature)) {
			deny(request, response, path, "签名不对（调用方自称 " + caller + "；两边的密钥不一致，或者请求被改过、不是发给本服务的）");
			return;
		}
		boolean firstUse;
		try {
			firstUse = nonceStore.firstUse(nonce, Duration.ofMillis(skewMillis * 2 + 5000));
		} catch (RuntimeException e) {
			log.error("内部调用 {} 的随机串记不进 Redis，无法防重放，拒绝这次调用：{}", path, e.toString());
			refuse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "nonce-store", "暂时无法校验内部调用");
			return;
		}
		if (!firstUse) {
			deny(request, response, path, "随机串已经用过（调用方自称 " + caller + "；同一个请求被发了第二次）");
			return;
		}

		// 到这里已经确认是自己人，可以告诉对方具体错在哪
		InnerEndpoint endpoint = endpoints.get(path);
		if (endpoint == null) {
			log.warn("调用方 {} 请求了不存在的内部接口 {}（两边的版本可能不一致）", caller, path);
			refuse(response, HttpServletResponse.SC_NOT_FOUND, "not-found", "没有这个内部接口");
			return;
		}
		JsonNode json;
		try {
			json = objectMapper.readTree(body);
		} catch (IOException e) {
			refuse(response, HttpServletResponse.SC_BAD_REQUEST, "body", "请求体不是合法的 JSON");
			return;
		}
		if (json == null || json.isNull() || json.isMissingNode()) {
			json = objectMapper.createObjectNode();
		}

		// 调用方最多等多久：排队不超过它，处理期间的查询也跑不过它
		long callerWait = callerWait(request);
		long queueLimit = properties.getMaxWait().toMillis();
		if (callerWait > 0) {
			queueLimit = Math.min(queueLimit, callerWait);
		}
		Gate gate = gates.get(path);
		long queued = System.nanoTime();
		if (!gate.enter(queueLimit)) {
			gate.refused(path, (System.nanoTime() - queued) / 1_000_000);
			answer(response, path, nonce, caller, failure(InnerProtocol.CODE_BUSY, "这个内部接口正忙，这次没有执行"));
			return;
		}
		InnerCall call = new InnerCall(caller, tenant, json);
		long started = System.nanoTime();
		byte[] payload;
		String outcome;
		try {
			if (callerWait > 0) {
				InnerDeadline.set(arrived + callerWait * 1_000_000L);
			}
			Object data = TenantContext.execute(tenant, () -> endpoint.handle(call));
			payload = success(data);
			outcome = "成功";
		} catch (ServiceException e) {
			payload = failure(InnerProtocol.CODE_BUSINESS, e.getMessage() == null ? "操作失败" : e.getMessage());
			outcome = "业务失败：" + e.getMessage();
		} catch (RuntimeException | JsonProcessingException e) {
			if (InnerDeadline.passed()) {
				// 多半是查询到点被取消了。调用方已经不等，这里留一行就够，不打堆栈
				log.warn("内部接口 {} 没能在调用方等待的 {} 秒内做完，不再继续（调用方 {}，租户 {}）：{}", path,
					callerWait / 1000, caller, tenant, e.toString());
			} else {
				log.error("内部接口 {} 执行出错（调用方 {}，租户 {}）", path, caller, tenant, e);
			}
			refuse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "error", "内部接口执行出错");
			return;
		} finally {
			InnerDeadline.clear();
			gate.leave();
		}
		answer(response, path, nonce, caller, payload);
		long waited = (started - queued) / 1_000_000;
		log.info("内部调用 {} 来自 {}，租户 {}，用时 {}ms{}，{}", path, caller, tenant,
			(System.nanoTime() - started) / 1_000_000,
			waited >= QUEUED_WORTH_LOGGING ? "（之前排队 " + waited + "ms）" : "", outcome);
	}

	/**
	 * 调用方说它最多等多少毫秒。没说或者格式不对返回 0，表示不设时限；说了就限制在 1 秒到 long-read-timeout 之间。
	 * 这个请求头不在签名里，所以这里对它的值不做别的用途。
	 */
	private long callerWait(HttpServletRequest request) {
		String value = request.getHeader(InnerProtocol.HEADER_WAIT);
		if (value == null || !InnerProtocol.WAIT.matcher(value).matches()) {
			return 0;
		}
		long millis = Long.parseLong(value);
		if (millis == 0) {
			return 0;
		}
		return Math.max(1000, Math.min(millis, properties.getLongReadTimeout().toMillis()));
	}

	/** 回一个带签名的 200 */
	private void answer(HttpServletResponse response, String path, String nonce, String caller, byte[] payload)
		throws IOException {
		response.setStatus(HttpServletResponse.SC_OK);
		response.setHeader(InnerProtocol.HEADER_SIGNATURE,
			InnerProtocol.signResponse(properties.key(), path, nonce, caller, self, payload));
		write(response, payload);
	}

	private static boolean isJson(String contentType) {
		return contentType != null && contentType.strip().toLowerCase(Locale.ROOT).startsWith("application/json");
	}

	/** 返回第一个缺失或格式不对的请求头的名字；都合规返回 null */
	private static String malformedHeader(String caller, String tenant, String timestamp, String nonce,
										  String signature) {
		if (caller == null || !InnerProtocol.SERVICE_NAME.matcher(caller).matches()) {
			return InnerProtocol.HEADER_CALLER;
		}
		if (tenant == null || !InnerProtocol.TENANT.matcher(tenant).matches()) {
			return InnerProtocol.HEADER_TENANT;
		}
		if (timestamp == null || !InnerProtocol.TIMESTAMP.matcher(timestamp).matches()) {
			return InnerProtocol.HEADER_TIMESTAMP;
		}
		if (nonce == null || !InnerProtocol.NONCE.matcher(nonce).matches()) {
			return InnerProtocol.HEADER_NONCE;
		}
		if (signature == null || !InnerProtocol.SIGNATURE.matcher(signature).matches()) {
			return InnerProtocol.HEADER_SIGNATURE;
		}
		return null;
	}

	/** 没通过身份校验：原因只记日志，对外统一返回 401 */
	private void deny(HttpServletRequest request, HttpServletResponse response, String path, String reason)
		throws IOException {
		log.warn("拒绝了一次内部调用 {}（来自 {}）：{}", path, request.getRemoteAddr(), reason);
		refuse(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", "内部调用校验没有通过");
	}

	private void tooLarge(HttpServletResponse response) throws IOException {
		// 请求体没读完，这条连接不再复用
		response.setHeader("Connection", "close");
		refuse(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "too-large", "请求体超过上限");
	}

	private void refuse(HttpServletResponse response, int status, String code, String msg) throws IOException {
		response.setStatus(status);
		write(response, failure(code, msg));
	}

	private byte[] success(Object data) throws JsonProcessingException {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("ok", true);
		node.set("data", objectMapper.valueToTree(data));
		return objectMapper.writeValueAsBytes(node);
	}

	private byte[] failure(String code, String msg) throws JsonProcessingException {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("ok", false);
		node.put("code", code);
		node.put("msg", msg);
		return objectMapper.writeValueAsBytes(node);
	}

	private static void write(HttpServletResponse response, byte[] payload) throws IOException {
		response.setContentType("application/json;charset=UTF-8");
		response.setHeader("Cache-Control", "no-store");
		response.setContentLength(payload.length);
		response.getOutputStream().write(payload);
	}

	/** 一个内部接口的名额：同时最多放这么多个请求进去处理 */
	private static final class Gate {

		/** 「忙」的日志最少隔这么久打一行 */
		private static final long LOG_EVERY_NANOS = 10_000_000_000L;

		private final int limit;

		/** 先到先得：等着的请求不会被后来的插队 */
		private final Semaphore permits;

		private final AtomicInteger refusedSinceLogged = new AtomicInteger();

		private final AtomicLong loggedAt = new AtomicLong(System.nanoTime() - LOG_EVERY_NANOS);

		private Gate(int limit) {
			this.limit = limit;
			this.permits = new Semaphore(limit, true);
		}

		/** 占一个名额，最多等这么多毫秒。没占到返回 false */
		private boolean enter(long waitMillis) {
			try {
				return permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		private void leave() {
			permits.release();
		}

		/** 回了一次「忙」。日志 10 秒最多一行，写上这一行代表几次：忙的时候不能每个请求刷一行 */
		private void refused(String path, long waitedMillis) {
			int count = refusedSinceLogged.incrementAndGet();
			long now = System.nanoTime();
			long last = loggedAt.get();
			if (now - last >= LOG_EVERY_NANOS && loggedAt.compareAndSet(last, now)) {
				refusedSinceLogged.addAndGet(-count);
				log.warn("内部接口 {} 同时在处理的请求到了上限 {} 个（mugsun.cloud.inner.max-concurrent），新来的等了 {}ms 还没轮到，"
					+ "回了「忙」。这样的日志 10 秒最多一行，这一行代表 {} 次", path, limit, waitedMillis, count);
			}
		}
	}
}
