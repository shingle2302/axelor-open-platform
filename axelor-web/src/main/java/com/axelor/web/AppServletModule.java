/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2023 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.web;

import com.axelor.app.AppModule;
import com.axelor.app.internal.AppFilter;
import com.axelor.auth.AuthModule;
import com.axelor.db.JpaModule;
import com.axelor.db.tenants.PostSessionTenantFilter;
import com.axelor.db.tenants.PreSessionTenantFilter;
import com.axelor.meta.MetaScanner;
import com.axelor.quartz.SchedulerModule;
import com.axelor.rpc.ObjectMapperProvider;
import com.axelor.rpc.Request;
import com.axelor.rpc.RequestFilter;
import com.axelor.rpc.Response;
import com.axelor.rpc.ResponseInterceptor;
import com.axelor.web.servlet.CorsFilter;
import com.axelor.web.servlet.I18nServlet;
import com.axelor.web.servlet.NoCacheFilter;
import com.axelor.web.servlet.ProxyFilter;
import com.axelor.web.socket.inject.WebSocketModule;
import com.axelor.web.socket.inject.WebSocketSecurity;
import com.axelor.web.socket.inject.WebSocketSecurityInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Module;
import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.matcher.Matchers;
import com.google.inject.persist.PersistFilter;
import com.google.inject.servlet.ServletModule;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import javax.ws.rs.Path;
import javax.ws.rs.ext.Provider;
import org.apache.shiro.guice.web.GuiceShiroFilter;

/** The main application module. */
public class AppServletModule extends ServletModule {

  private static final String DEFAULT_PERSISTANCE_UNIT = "persistenceUnit";

  private String jpaUnit;

  public AppServletModule() {
    this(DEFAULT_PERSISTANCE_UNIT);
  }

  public AppServletModule(String jpaUnit) {
    this.jpaUnit = jpaUnit;
  }

  protected List<? extends Module> getModules() {
    //创建AuthModule
    final AuthModule authModule = new AuthModule(getServletContext());
    //创建AppModule
    final AppModule appModule = new AppModule();
    //创建SchedulerModule
    final SchedulerModule schedulerModule = new SchedulerModule();
    return Arrays.asList(authModule, appModule, schedulerModule);
  }

  protected void afterConfigureServlets() {
    // register initialization servlet
    serve("__init__").with(AppStartup.class);
  }


  /**
   * 配置Servlets
   */
  @Override
  protected void configureServlets() {
    // 绑定ObjectMapper
    // some common bindings
    bind(ObjectMapper.class).toProvider(ObjectMapperProvider.class);

    // 初始化JPA模块
    // initialize JPA
    install(new JpaModule(jpaUnit, true, false));

    // 初始化WebSocket模块
    // WebSocket
    install(new WebSocketModule());

    // 初始化过滤器
    // trick to ensure PersistFilter is registered before anything else
    install(
        new ServletModule() {

          @Override
          protected void configureServlets() {
            // check for CORS requests earlier
            filter("*").through(ProxyFilter.class);
            filter("*").through(CorsFilter.class);
            // pre-session tenant filter should be come before PersistFilter
            filter("*").through(PreSessionTenantFilter.class);
            // order is important, PersistFilter must come first
            filter("*").through(PersistFilter.class);
            filter("*").through(AppFilter.class);
            filter("*").through(GuiceShiroFilter.class);
            // pre-session tenant filter should be come after shiro filter
            filter("*").through(PostSessionTenantFilter.class);
          }
        });

    // 初始化AuthModule、AppModule、SchedulerModule
    // install additional modules
    for (Module module : getModules()) {
      install(module);
    }

    // no-cache filter
    filter("/js/*", NoCacheFilter.STATIC_URL_PATTERNS).through(NoCacheFilter.class);

    // i18n bundle
    serve("/js/messages.js").with(I18nServlet.class);

    // 绑定拦截器
    // intercept all response methods
    bindInterceptor(
        Matchers.any(),
        Matchers.returns(Matchers.subclassesOf(Response.class)),
        new ResponseInterceptor());

    // intercept request accepting methods
    bindInterceptor(
        Matchers.annotatedWith(Path.class),
        new AbstractMatcher<Method>() {
          @Override
          public boolean matches(Method t) {
            for (Class<?> c : t.getParameterTypes()) {
              if (Request.class.isAssignableFrom(c)) {
                return true;
              }
            }
            return false;
          }
        },
        new RequestFilter());

    // intercept WebSocket endpoint
    bindInterceptor(
        Matchers.annotatedWith(WebSocketSecurity.class),
        Matchers.any(),
        new WebSocketSecurityInterceptor());

    // bind all the web service resources
    for (Class<?> type :
        MetaScanner.findSubTypesOf(Object.class)
            .having(Path.class)
            .having(Provider.class)
            .any()
            .find()) {
      bind(type);
    }

    // register the session listener
    getServletContext().addListener(new AppSessionListener());

    // run additional configuration tasks
    this.afterConfigureServlets();
  }
}
