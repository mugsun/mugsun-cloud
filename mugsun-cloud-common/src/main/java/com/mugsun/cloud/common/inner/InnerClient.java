package com.mugsun.cloud.common.inner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.NullNode;
import com.mugsun.boot.common.constant.MonitorConstants;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.cloud.common.inner.InnerCallException.Kind;
import com.mugsun.core.tool.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * 调用别的服务的内部接口（{@link InnerEndpoint}）。
 *
 * <p>每次调用做这几件事：从注册中心取对方的一个实例；把当前生效的租户带过去；用共享密钥签名；收到响应后验对方的签名。
 * 只有状态码 200 且验签通过的响应才会被采信，其它一律算调用失败，响应里写了什么都不看。
 *
 * <p><b>带过去的身份只有租户，没有用户。</b>对方按这个租户查库，不知道是哪个用户在操作，也不做按用户的数据权限过滤。
 * 所以只有在单体版里本来就不看用户、只看租户的调用才能这样转；用户能不能做这件事，由调用方在发起调用之前判断。
 * 租户取 {@code TenantContext} 当前生效的那个；当前没有租户上下文时不发起调用。
 *
 * <p>连不上某个实例时换一个实例再试一次（比如对方正在滚动发布）；对方回「忙」时也换，那表示它这次没有执行。
 * 已经连上以后的其它失败不重试，因为对方可能已经执行过了。
 *
 * <p>同一个租户调同一个实例的同一个接口接连失败几次以后，一段时间内不再调它，直接报失败（熔断，见 {@link InnerBreakers}）：
 * 对方连得上却不回话的时候，用户不用每次都等到超时。各个租户分开记，一个租户自己的故障不连累别的租户。
 */
@Component
public class InnerClient {

	private static final Logger log = LoggerFactory.getLogger(InnerClient.class);

	/** 和 TraceIdFilter 认的格式一致 */
	private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

	/** 连不上时最多试几个实例 */
	private static final int MAX_INSTANCES = 2;

	/** 最多记多少个提示过的服务名，防止这个集合一直长大 */
	private static final int MAX_HINTED = 32;

	private final InnerProperties properties;

	private final DiscoveryClient discoveryClient;

	private final ObjectMapper objectMapper;

	/** 读响应时小数按 BigDecimal 读，末尾的 0 也留着：原样转交给前端时数字一位不差 */
	private final ObjectReader treeReader;

	private final String self;

	private final Transport transport;

	private final LongSupplier clock;

	private final InnerBreakers breakers;

	private final SecureRandom random = new SecureRandom();

	/** 已经提示过「对方在、但本服务没配密钥」的服务名 */
	private final Set<String> hinted = ConcurrentHashMap.newKeySet();

	@Autowired
	public InnerClient(InnerProperties properties, DiscoveryClient discoveryClient, ObjectMapper objectMapper,
					   @Value("${spring.application.name}") String self) {
		this(properties, discoveryClient, objectMapper, self, new HttpTransport(properties), System::currentTimeMillis);
	}

	InnerClient(InnerProperties properties, DiscoveryClient discoveryClient, ObjectMapper objectMapper, String self,
				Transport transport, LongSupplier clock) {
		this(properties, discoveryClient, objectMapper, self, transport, clock, () -> System.nanoTime() / 1_000_000);
	}

	/**
	 * @param clock  系统时间（毫秒），写进请求的时间戳
	 * @param ticker 只增不减的毫秒数，熔断计时用
	 */
	InnerClient(InnerProperties properties, DiscoveryClient discoveryClient, ObjectMapper objectMapper, String self,
				Transport transport, LongSupplier clock, LongSupplier ticker) {
		this.properties = properties;
		this.discoveryClient = discoveryClient;
		this.objectMapper = objectMapper;
		this.treeReader = objectMapper.reader()
			.with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
		this.self = self;
		this.transport = transport;
		this.clock = clock;
		this.breakers = new InnerBreakers(properties.getBreakerFailures(), properties.getBreakerPause().toMillis(), ticker);
	}

	/**
	 * 对方现在能不能调：配了密钥，注册中心里也有它的实例。用来在调用之前判断「那个模块装没装」，
	 * 注册中心问不到时按不能调处理。
	 *
	 * <p>对方在注册中心里、只是本服务没配密钥时，结果也是不能调，另外在日志里提示一次：
	 * 用到对方的功能这时会悄悄退回没有对方时的做法，不提示的话部署的人很难发现是少配了密钥。
	 */
	public boolean reachable(String serviceId) {
		boolean present;
		try {
			present = !discoveryClient.getInstances(serviceId).isEmpty();
		} catch (RuntimeException e) {
			log.warn("向注册中心查询服务 {} 失败，按没有实例处理：{}", serviceId, e.toString());
			return false;
		}
		if (present && !properties.configured()) {
			if (hinted.size() < MAX_HINTED && hinted.add(serviceId)) {
				log.warn("注册中心里有 {}，但本服务没有配置 mugsun.cloud.inner.secret（环境变量 MUGSUN_INNER_SECRET），调不了它，"
					+ "用到它的功能按它没有部署处理。这条提示对每个服务只打一次", serviceId);
			}
			return false;
		}
		return present;
	}

	/** 用默认的等待时间调用，见 {@link #call(String, String, Object, Duration)} */
	public JsonNode call(String serviceId, String path, Object body) {
		return call(serviceId, path, body, properties.getReadTimeout());
	}

	/** 对方要去调大模型时用这个，等得久一些（{@code mugsun.cloud.inner.long-read-timeout}） */
	public JsonNode callSlow(String serviceId, String path, Object body) {
		return call(serviceId, path, body, properties.getLongReadTimeout());
	}

	/**
	 * 调用一个内部接口。
	 *
	 * @param serviceId   对方的服务名
	 * @param path        接口路径，{@code /inner/} 开头
	 * @param body        请求体，转成 JSON 发过去；可以是 null
	 * @param readTimeout 等对方返回的时间上限
	 * @return 对方返回的数据；对方没有返回数据时是 JSON 的 null 节点，不是 Java 的 null
	 * @throws ServiceException   对方返回了业务失败，消息和对方抛出的一样
	 * @throws InnerCallException 调用没有成功，原因见 {@link InnerCallException#kind()}。熔断中（{@code BREAKER_OPEN}）
	 *                            和对方忙（{@code BUSY}）这两种，对方肯定没有执行
	 */
	public JsonNode call(String serviceId, String path, Object body, Duration readTimeout) {
		if (!InnerProtocol.SERVICE_NAME.matcher(serviceId).matches() || !InnerProtocol.PATH.matcher(path).matches()) {
			throw new IllegalArgumentException("服务名或内部接口路径不合规：" + serviceId + " " + path);
		}
		if (!properties.configured()) {
			throw new InnerCallException(Kind.NOT_CONFIGURED, "没有配置 mugsun.cloud.inner.secret，内部调用停用");
		}
		String tenant = currentTenant();
		byte[] payload = serialize(body);
		List<ServiceInstance> instances = instancesOf(serviceId);
		if (instances.isEmpty()) {
			throw new InnerCallException(Kind.NO_INSTANCE, "注册中心里没有 " + serviceId + " 的实例");
		}
		IOException lastConnectFailure = null;
		boolean busy = false;
		int sent = 0;
		for (ServiceInstance instance : instances) {
			if (sent == MAX_INSTANCES) {
				break;
			}
			URI uri = instance.getUri().resolve(path);
			InnerBreakers.Ticket ticket = breakers.enter(serviceId, uri.getAuthority(), path, tenant);
			if (ticket == null) {
				// 这个租户调这个实例的这个接口熔断中，看下一个实例
				continue;
			}
			sent++;
			// 凭单在进下面的 catch 之前就已经交回；exchange 里没标记过结果的都按失败记
			try (ticket) {
				return exchange(uri, serviceId, path, tenant, payload, readTimeout, ticket);
			} catch (ConnectException | HttpConnectTimeoutException e) {
				lastConnectFailure = e;
				log.warn("连不上 {} 的实例 {}：{}", serviceId, uri.getAuthority(), e.toString());
			} catch (IOException e) {
				log.warn("调用 {} 的 {} 失败：{}", serviceId, path, e.toString());
				throw new InnerCallException(Kind.FAILED, "调用 " + serviceId + " 的 " + path + " 失败：" + e, e);
			} catch (InnerCallException e) {
				if (e.kind() != Kind.BUSY) {
					throw e;
				}
				// 对方说忙，这次没有执行，可以放心换一个实例
				busy = true;
			}
		}
		if (sent == 0) {
			throw new InnerCallException(Kind.BREAKER_OPEN, "前几次调用 " + serviceId + " 的 " + path
				+ " 接连失败，暂时不调它，过一会儿自动再试");
		}
		if (busy) {
			throw new InnerCallException(Kind.BUSY, serviceId + " 的 " + path + " 正忙，这次没有执行");
		}
		throw new InnerCallException(Kind.FAILED, "连不上 " + serviceId, lastConnectFailure);
	}

	private JsonNode exchange(URI uri, String serviceId, String path, String tenant, byte[] payload,
							  Duration readTimeout, InnerBreakers.Ticket ticket) throws IOException {
		// 每次发送都用新的时间和随机串：换实例重试时不能沿用上一次的，否则会被当成重放
		String timestamp = Long.toString(clock.getAsLong());
		String nonce = newNonce();
		byte[] key = properties.key();
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "application/json");
		headers.put(InnerProtocol.HEADER_CALLER, self);
		headers.put(InnerProtocol.HEADER_TENANT, tenant);
		headers.put(InnerProtocol.HEADER_TIMESTAMP, timestamp);
		headers.put(InnerProtocol.HEADER_NONCE, nonce);
		headers.put(InnerProtocol.HEADER_SIGNATURE,
			InnerProtocol.signRequest(key, "POST", path, timestamp, nonce, self, serviceId, tenant, payload));
		// 告诉对方这边最多等多久：过了这个时间这边已经按失败处理，它的查询不必再跑
		headers.put(InnerProtocol.HEADER_WAIT, Long.toString(readTimeout.toMillis()));
		String traceId = MDC.get(MonitorConstants.TRACE_MDC_KEY);
		if (traceId != null && TRACE_ID.matcher(traceId).matches()) {
			headers.put(MonitorConstants.TRACE_HEADER, traceId);
		}

		Reply reply = transport.post(uri, headers, payload, readTimeout);

		if (reply.status() >= 400 && reply.status() < 500) {
			// 对方明确拒绝了：它在正常工作，这种失败不计入熔断
			ticket.answered();
		}
		if (reply.status() == 401) {
			log.warn("{} 拒绝了内部调用 {}：两边的 mugsun.cloud.inner.secret 不一致，或者两台机器的时间差得太多（具体原因在对方的日志里）",
				serviceId, path);
			throw new InnerCallException(Kind.REJECTED, serviceId + " 拒绝了内部调用 " + path);
		}
		if (reply.status() != 200) {
			log.warn("调用 {} 的 {} 失败：HTTP {}", serviceId, path, reply.status());
			throw new InnerCallException(Kind.FAILED, "调用 " + serviceId + " 的 " + path + " 失败：HTTP " + reply.status());
		}
		byte[] replyBody = reply.body();
		if (replyBody == null) {
			log.warn("{} 对 {} 的响应没有声明长度，或者超过了 mugsun.cloud.inner.max-response-size，不采信", serviceId, path);
			throw new InnerCallException(Kind.FAILED, serviceId + " 对 " + path + " 的响应超过上限");
		}
		String expected = InnerProtocol.signResponse(key, path, nonce, self, serviceId, replyBody);
		if (!InnerProtocol.sameSignature(expected, reply.signature())) {
			log.warn("{} 对 {} 的响应没有通过验签，不采信（回话的实例 {} 可能不是拿着密钥的服务）", serviceId, path,
				uri.getAuthority());
			throw new InnerCallException(Kind.FAILED, serviceId + " 对 " + path + " 的响应没有通过验签");
		}
		JsonNode root = treeReader.readTree(replyBody);
		if (root.path("ok").asBoolean(false)) {
			ticket.answered();
			JsonNode data = root.get("data");
			return data == null ? NullNode.getInstance() : data;
		}
		String code = root.path("code").asText();
		if (InnerProtocol.CODE_BUSINESS.equals(code)) {
			ticket.answered();
			throw new ServiceException(root.path("msg").asText("操作失败"));
		}
		if (InnerProtocol.CODE_BUSY.equals(code)) {
			ticket.busy();
			throw new InnerCallException(Kind.BUSY, serviceId + " 的 " + path + " 正忙，这次没有执行");
		}
		throw new InnerCallException(Kind.FAILED, "调用 " + serviceId + " 的 " + path + " 失败：" + root.path("msg").asText());
	}

	/** 当前生效的租户：按某个租户过滤就传它的编号；不过滤（平台管理员看全部租户）传 -1；没有上下文就不调用 */
	private static String currentTenant() {
		Object[] ids;
		try {
			ids = TenantContext.resolveTenantIds();
		} catch (RuntimeException e) {
			throw new InnerCallException(Kind.FAILED, "当前没有租户上下文，不能发起内部调用", e);
		}
		String tenant = ids == null ? TenantContext.ALL : String.valueOf(ids[0]);
		if (!InnerProtocol.TENANT.matcher(tenant).matches()) {
			throw new InnerCallException(Kind.FAILED, "租户编号里有内部调用带不了的字符（只支持不含空白的 ASCII 字符，最长 64 个）");
		}
		return tenant;
	}

	private byte[] serialize(Object body) {
		byte[] payload;
		try {
			payload = objectMapper.writeValueAsBytes(body == null ? Map.of() : body);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("内部调用的请求体转不成 JSON", e);
		}
		if (payload.length > properties.getMaxRequestSize().toBytes()) {
			throw new InnerCallException(Kind.FAILED, "内部调用的请求体有 " + payload.length + " 字节，超过上限");
		}
		return payload;
	}

	/** 取对方的实例并打乱顺序，让请求大致平均分到各实例 */
	private List<ServiceInstance> instancesOf(String serviceId) {
		List<ServiceInstance> instances;
		try {
			instances = new ArrayList<>(discoveryClient.getInstances(serviceId));
		} catch (RuntimeException e) {
			throw new InnerCallException(Kind.FAILED, "向注册中心查询服务 " + serviceId + " 失败：" + e, e);
		}
		Collections.shuffle(instances, random);
		return instances;
	}

	private String newNonce() {
		byte[] bytes = new byte[16];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** 发一个 POST 并取回响应。单独抽出来是为了测试时能换掉 */
	interface Transport {

		Reply post(URI uri, Map<String, String> headers, byte[] body, Duration readTimeout) throws IOException;
	}

	/**
	 * @param signature 响应头里的签名，没有就是 null
	 * @param body      响应体；响应没有声明长度或者超过上限时是 null
	 */
	record Reply(int status, String signature, byte[] body) {
	}

	/** 用 JDK 自带的 HttpClient 发请求：不跟随跳转，响应体有大小上限，整次调用有总时限 */
	static final class HttpTransport implements Transport {

		private final HttpClient client;

		private final Duration connectTimeout;

		private final long maxResponseBytes;

		HttpTransport(InnerProperties properties) {
			this.connectTimeout = properties.getConnectTimeout();
			this.maxResponseBytes = properties.getMaxResponseSize().toBytes();
			this.client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(connectTimeout)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		}

		@Override
		public Reply post(URI uri, Map<String, String> headers, byte[] body, Duration readTimeout) throws IOException {
			HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
				.timeout(readTimeout)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body));
			headers.forEach(builder::header);
			// 先看响应声明的长度：没声明或者超过上限的不读进内存。内部接口的响应都声明了长度
			HttpResponse.BodyHandler<byte[]> handler = info -> {
				long length = info.headers().firstValueAsLong("Content-Length").orElse(-1);
				return length < 0 || length > maxResponseBytes
					? HttpResponse.BodySubscribers.replacing(null)
					: HttpResponse.BodySubscribers.ofByteArray();
			};
			CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(builder.build(), handler);
			HttpResponse<byte[]> response;
			try {
				// HttpRequest.timeout 只管到收到响应头为止，这里再给整次调用（包括读响应体）设一个总时限
				response = future.get(connectTimeout.plus(readTimeout).plusSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				future.cancel(true);
				throw new HttpTimeoutException("等待响应超时");
			} catch (InterruptedException e) {
				future.cancel(true);
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("等待响应时被中断");
			} catch (ExecutionException e) {
				if (e.getCause() instanceof IOException io) {
					throw io;
				}
				throw new IOException(e.getCause());
			}
			return new Reply(response.statusCode(),
				response.headers().firstValue(InnerProtocol.HEADER_SIGNATURE).orElse(null), response.body());
		}
	}
}
