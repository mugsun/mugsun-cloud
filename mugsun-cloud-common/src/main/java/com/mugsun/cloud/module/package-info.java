/**
 * 可选模块（GIS、埋点、AI）在不在、开没开：给不带这些模块、又要知道它们状态的服务用。
 *
 * <p>单体版里模块状态由模块自己回答，模块没打进包时用 {@code OptionalModuleConfiguration} 给的默认实现，回答「不在」。
 * 微服务版里模块是另外的服务，这里的三个类改成看注册中心里有没有那个服务的实例。
 *
 * <p>这个包不在各服务默认扫描的范围里，要用的服务在启动类的 {@code @ComponentScan} 里写上 {@code com.mugsun.cloud.module}。
 * 代码里用到模块状态（{@code GisModuleStatus} 这三个接口，或者按它们过滤菜单的 {@code ModuleMenuFilter}）的服务都要写：
 * 漏了不报错，那个服务会拿到默认实现，一直回答「不在」，页面上对应的菜单和功能会悄悄消失。
 * {@code ModuleStatusScanTest}（在 mugsun-cloud-config 的测试里）按源码查这一条。
 *
 * <p>gis、track、ai 三个服务不能扫描这个包：它们各有自己的实现，再加上这里的就有两个，启动会失败。
 * 所以这几个类没有放在 {@code com.mugsun.cloud.common} 下面（那个包每个服务都扫描）。
 */
package com.mugsun.cloud.module;
