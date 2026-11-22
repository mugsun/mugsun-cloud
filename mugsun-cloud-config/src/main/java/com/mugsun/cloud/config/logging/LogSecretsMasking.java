package com.mugsun.cloud.config.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import ch.qos.logback.core.spi.AppenderAttachable;
import ch.qos.logback.core.spi.ConfigurationEventListener;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.SpringApplicationEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.event.SmartApplicationListener;

import java.util.Iterator;

/**
 * 把 {@link LogSecrets} 接到日志的出口上：给每个写日志的出口（控制台、日志文件）的格式外面包一层 {@link MaskedLayout}。
 * 在 {@code META-INF/spring.factories} 里登记，带了 mugsun-cloud-config 的应用都生效，没有开关。
 * <p>
 * 日志的出口在启动过程中会换好几次，每换一次都要重新包，所以有两个入口：
 * <ul>
 * <li>应用启动的每个阶段做一遍。最早的一遍包的是 logback 默认的控制台出口：Spring Boot 初始化日志之前有大约一秒，
 * 应用已经在从 Nacos 读配置，启动失败的报告也从这个出口出去。Spring Boot 初始化日志（{@link LoggingApplicationListener}）
 * 会把出口全部重建，所以排在它后面，等它建完再包；</li>
 * <li>logback 每按一份配置文件配完一遍做一遍。Nacos 的客户端第一次被用到时，会按它自带的配置文件给自己的日志另加几个文件出口
 * （{@code ~/logs/nacos/remote.log} 等），这件事不在应用启动的哪个阶段上，只能跟着 logback 的通知走。</li>
 * </ul>
 * 只认按格式排版的出口（{@link LayoutWrappingEncoder}）。输出成 JSON 的那种编码器不经过格式，这里管不到；
 * 网关和各服务现在都没有用那一种，要用的时候先在这里补上。启动完成以后用代码加进来的出口也管不到。
 */
public class LogSecretsMasking implements SmartApplicationListener {

	/**
	 * logback 的通知不分开始和结束，来一次包一次：已经包过的会跳过，多做一遍没有副作用。
	 * 不是 private：测试要先把它从日志系统上拿掉，才能确认应用启动时会自己装上
	 */
	static final ConfigurationEventListener AFTER_CONFIGURED = event -> wrapAll();

	/** 启动的每个阶段都做，启动失败也做：阶段之间新加进来的出口只能在下一个阶段补上 */
	@Override
	public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
		return SpringApplicationEvent.class.isAssignableFrom(eventType);
	}

	@Override
	public int getOrder() {
		return LoggingApplicationListener.DEFAULT_ORDER + 1;
	}

	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		install();
	}

	/**
	 * 给现有的每个出口包上打码的一层，并让 logback 以后每配完一遍都再包一遍。返回这一次新包了几个。
	 * 日志用的不是 logback 时什么都不做。
	 */
	public static int install() {
		if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
			return 0;
		}
		// logback 重置日志配置时不会把它清掉。先去掉再加，调多少次都只留一个
		context.removeConfigurationEventListener(AFTER_CONFIGURED);
		context.addConfigurationEventListener(AFTER_CONFIGURED);
		return wrapAll();
	}

	private static synchronized int wrapAll() {
		if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
			return 0;
		}
		int wrapped = 0;
		for (Logger logger : context.getLoggerList()) {
			wrapped += wrap(logger.iteratorForAppenders());
		}
		return wrapped;
	}

	@SuppressWarnings("unchecked")
	private static int wrap(Iterator<Appender<ILoggingEvent>> appenders) {
		int wrapped = 0;
		while (appenders.hasNext()) {
			Appender<ILoggingEvent> appender = appenders.next();
			// 异步的出口自己不写，真正写的那个挂在它后面
			if (appender instanceof AppenderAttachable<?> attachable) {
				wrapped += wrap(((AppenderAttachable<ILoggingEvent>) attachable).iteratorForAppenders());
			}
			if (appender instanceof OutputStreamAppender<ILoggingEvent> out
					&& out.getEncoder() instanceof LayoutWrappingEncoder<ILoggingEvent> encoder) {
				Layout<ILoggingEvent> layout = encoder.getLayout();
				if (layout != null && !(layout instanceof MaskedLayout)) {
					out.setEncoder(masking(encoder, layout, out));
					wrapped++;
				}
			}
		}
		return wrapped;
	}

	/**
	 * 另做一个编码器换到出口上，而不是给原来的编码器换格式：最常见的那种（PatternLayoutEncoder）不让换，一换就抛异常。
	 * 新的编码器和原来的只差打码这一步：格式、字符集都用原来的，文件头和文件尾由 {@link MaskedLayout} 转给原来的格式。
	 */
	private static LayoutWrappingEncoder<ILoggingEvent> masking(LayoutWrappingEncoder<ILoggingEvent> original,
			Layout<ILoggingEvent> layout, Appender<ILoggingEvent> appender) {
		LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
		encoder.setContext(original.getContext());
		encoder.setParent(appender);
		encoder.setCharset(original.getCharset());
		encoder.setLayout(new MaskedLayout(layout));
		encoder.start();
		return encoder;
	}
}
