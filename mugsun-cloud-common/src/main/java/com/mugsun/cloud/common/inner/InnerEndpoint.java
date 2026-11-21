package com.mugsun.cloud.common.inner;

/**
 * 一个只给别的服务调用的接口。实现类注册成 Bean 就生效，由 {@link InnerRequestFilter} 验签通过后调用。
 *
 * <p>内部接口不写成 Controller：Controller 要过登录拦截器，而内部调用不带用户的登录令牌。{@code /inner} 下的请求也从不交给
 * Controller 处理（启动时由 {@link InnerMappingGuard} 检查）。
 *
 * <p>调用时没有登录用户，只有租户：{@link #handle} 在 {@code TenantContext.execute(调用方传来的租户, ...)} 里执行，
 * 查库时按这个租户过滤。所以这里只能调不依赖登录用户的代码，用到 {@code StpUtil} 或 {@code @DataScope} 的不行：
 * 没有登录态时它们会报错，调用失败。
 */
public interface InnerEndpoint {

	/** 路径，以 {@code /inner/} 开头，每段只用小写字母、数字和短横线，比如 {@code /inner/gis/insight/snapshot} */
	String path();

	/**
	 * 处理一次调用。返回值转成 JSON 交还调用方，可以是 null。
	 *
	 * <p>抛 {@code ServiceException} 表示业务上的失败（比如「模块没有启用」），消息原样带回去，调用方那边会重新抛出同样消息的
	 * {@code ServiceException}。抛其它异常按故障处理：这边记日志，调用方只知道调用失败，不知道细节。
	 */
	Object handle(InnerCall call);
}
