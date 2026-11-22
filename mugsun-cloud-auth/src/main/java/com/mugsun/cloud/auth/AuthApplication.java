package com.mugsun.cloud.auth;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 登录服务启动入口，对应 BladeX 最外层的 blade-auth。
 *
 * <p>这个服务管「你是谁」：账号密码、短信、第三方账号三种登录和两步验证，注册和找回密码，验证码，
 * 退出、切换租户、登录后取自己的信息和改资料改密码，在线用户，手机端的登录；
 * 还有把本平台的账号开放给别的系统用的 OAuth2（授权、换令牌、应用管理和调用记录）。
 * 这些类是从 mugsun-boot 的核心模块里分出来的，分哪些写在 {@code scripts/sync-rules.json} 的 {@code core.services} 里；
 * 登录怎么校验，代码和单体版是同一份，这里没有手写的业务代码。
 *
 * <p>别的服务认不认一个登录，看的是 Redis 里的登录态，不来问这个服务。所以它停了的时候，已经打开的页面照常用，
 * 但没法新登录，刷新页面后也取不到登录信息（{@code /auth/info}）。
 *
 * <p>用户、角色这些表它直接读写同一个库（公共层里带着这些类），不经别的服务；发短信经 {@code mugsun-cloud-resource-api}
 * 转给文件和短信服务。找回密码和两步验证的邮件由它自己发：发邮件的类 system 和这里各带一份，
 * 不经 system 转，免得「邮箱存在」和「邮箱不存在」两种情况的响应时间差出一次服务间调用，让人猜出哪些邮箱注册过。
 *
 * <p>扫描范围里的 {@code com.mugsun.cloud.module} 是判断 GIS、埋点、AI 三个可选模块在不在的类：登录信息里有这三个模块的开关
 * （{@code gisEnabled} 等），页面按它们决定显不显示对应的功能。不扫描这个包也能启动，但三个开关会一直是关的，见那个包的说明。
 *
 * <p>注解和 mugsun-boot 的 {@code MugsunApplication} 保持一致，区别有两处：扫描范围多了微服务版自己的包；
 * 没有 {@code @EnableFileStorage}，文件存储不在这个服务里。boot 的启动类改了注解时，这里要跟着改。
 */
@SpringBootApplication
@ComponentScan(
	basePackages = {"com.mugsun.boot", "com.mugsun.cloud.common", "com.mugsun.cloud.module", "com.mugsun.cloud.auth"},
	excludeFilters = {
		@ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.mugsun\\.boot\\.gis\\..*"),
		@ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.mugsun\\.boot\\.track\\..*"),
		@ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.mugsun\\.boot\\.ai\\..*")
	}
)
@EnableAsync
@EnableScheduling
@EnableMethodCache(basePackages = "com.mugsun.boot")
@MapperScan("com.mugsun.boot.**.mapper")
public class AuthApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuthApplication.class, args);
	}
}
