package com.mugsun.cloud.ai;

import com.mugsun.boot.tenant.TenantCredentialPurger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 删租户时要清的凭据，system 是不是都照顾到了。
 *
 * <p>单体版删租户时，{@code TenantService} 在事务里调用容器里所有的 {@link TenantCredentialPurger}，可选模块靠实现这个接口
 * 来清自己的凭据。微服务版里删租户在 system，它看不到这个服务里的实现，所以这里的每一个实现都要在 system 里有对应的清理：
 * AI 密钥对应 system 的 {@code CloudAiSecretPurger}。
 *
 * <p>从 mugsun-boot 同步后这个测试失败，说明 AI 模块新增或改掉了实现：先在 system 里补上（或去掉）对应的清理，再改下面的名单。
 */
class TenantPurgerGuardTest {

	@Test
	void everyPurgerInThisServiceHasACounterpartInSystem() {
		assertThat(purgersOnClasspath())
			.as("这个服务里的 TenantCredentialPurger：system 删租户时调用不到它们，每一个都要在 system 里有对应的清理")
			.containsExactly("com.mugsun.boot.ai.tenant.AiSecretTenantPurger");
	}

	private static List<String> purgersOnClasspath() {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
				// 默认只认顶层的具体类；这里连内部类、匿名类、抽象类也算上，只要不是接口
				return !definition.getMetadata().isInterface();
			}
		};
		scanner.addIncludeFilter(new AssignableTypeFilter(TenantCredentialPurger.class));
		return scanner.findCandidateComponents("com.mugsun.boot").stream()
			.map(BeanDefinition::getBeanClassName)
			.sorted()
			.toList();
	}
}
