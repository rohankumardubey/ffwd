/*-
 * -\-\-
 * FastForward Core
 * --
 * Copyright (C) 2016 - 2019 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.ffwd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.spotify.ffwd.debug.DebugServer;
import com.spotify.ffwd.debug.NettyDebugServer;
import com.spotify.ffwd.debug.NoopDebugServer;
import com.spotify.ffwd.filter.AndFilter;
import com.spotify.ffwd.filter.FalseFilter;
import com.spotify.ffwd.filter.Filter;
import com.spotify.ffwd.filter.FilterDeserializer;
import com.spotify.ffwd.filter.MatchKey;
import com.spotify.ffwd.filter.MatchTag;
import com.spotify.ffwd.filter.NotFilter;
import com.spotify.ffwd.filter.OrFilter;
import com.spotify.ffwd.filter.TrueFilter;
import com.spotify.ffwd.filter.TypeFilter;
import com.spotify.ffwd.input.InputManager;
import com.spotify.ffwd.module.FastForwardModule;
import com.spotify.ffwd.module.PluginContext;
import com.spotify.ffwd.module.PluginContextImpl;
import com.spotify.ffwd.output.OutputManager;
import com.spotify.ffwd.protocol.ProtocolClients;
import com.spotify.ffwd.protocol.ProtocolClientsImpl;
import com.spotify.ffwd.protocol.ProtocolServers;
import com.spotify.ffwd.protocol.ProtocolServersImpl;
import com.spotify.ffwd.serializer.Serializer;
import com.spotify.ffwd.serializer.ToStringSerializer;
import com.spotify.ffwd.statistics.CoreStatistics;
import com.spotify.ffwd.statistics.NoopCoreStatistics;
import com.uchuhimo.konf.Config;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentCore {

  private static final Path DEFAULT_CONFIG_PATH = Paths.get("ffwd.yaml");
  private static final Logger log = LoggerFactory.getLogger(AgentCore.class);

  private final List<Class<? extends FastForwardModule>> modules;
  private final Optional<Path> configPath;
  private final CoreStatistics statistics;

  private final Injector primaryInjector;

  private AgentCore(
      final List<Class<? extends FastForwardModule>> modules,
      Optional<Path> configPath, CoreStatistics statistics
  ) {
    this.modules = modules;
    this.configPath = configPath;
    this.statistics = statistics;

    try {
      final Injector early = setupEarlyInjector();
      final AgentConfig config = readConfig(early);

      primaryInjector = setupPrimaryInjector(early, config);
    } catch (Exception e) {
      throw new RuntimeException("Failed during initialization", e);
    }
  }

  public void run() throws Exception {
    start(primaryInjector);
    log.info("Started!");

    waitUntilStopped(primaryInjector);
    log.info("Stopped, Bye Bye!");
  }

  private Optional<String> lookupSearchDomain(final AgentConfig config) {
    log.info("Looking up domain using {}", config.getSearchDomain());
    final Optional<String> domain = config.getSearchDomain().discover();

    domain.ifPresent(d -> {
      log.info("Found domain: {}", d);
    });

    return domain;
  }

  private void waitUntilStopped(final Injector primary) throws InterruptedException {
    final CountDownLatch shutdown = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(setupShutdownHook(primary, shutdown));
    shutdown.await();
  }

  private Thread setupShutdownHook(final Injector primary, final CountDownLatch shutdown) {
    final Thread thread = new Thread(() -> {
      try {
        AgentCore.this.stop(primary);
      } catch (Exception e) {
        log.error("AgentCore#stop(Injector) failed", e);
      }

      shutdown.countDown();
    });

    thread.setName("ffwd-agent-core-shutdown-hook");

    return thread;
  }

  private void start(final Injector primary) throws Exception {
    final InputManager input = primary.getInstance(InputManager.class);
    final OutputManager output = primary.getInstance(OutputManager.class);
    final DebugServer debug = primary.getInstance(DebugServer.class);

    log.info("Waiting for all components to start...");

    CompletableFuture<Void> startup = CompletableFuture.allOf(
        debug.start(), output.start(), input.start());

    startup.get(10, TimeUnit.SECONDS);

    input.init();
    output.init();
  }

  private void stop(final Injector primary) throws Exception {
    final InputManager input = primary.getInstance(InputManager.class);
    final OutputManager output = primary.getInstance(OutputManager.class);
    final DebugServer debug = primary.getInstance(DebugServer.class);


    log.info("Waiting for all components to stop...");

    CompletableFuture<Void> shutdown = CompletableFuture.allOf(
        input.stop(), output.stop(), debug.stop());

    try {
      shutdown.get(10, TimeUnit.SECONDS);
    } catch (final Exception e) {
      log.error("All components did not stop in a timely fashion", e);
      shutdown.cancel(true);
    }
  }

  /**
   * Setup early application Injector.
   * <p>
   * The early injector is used by modules to configure the system.
   *
   * @throws Exception If something could not be set up.
   */
  private Injector setupEarlyInjector() throws Exception {
    final List<Module> modules = Lists.newArrayList();

    modules.add(new AbstractModule() {
      @Singleton
      @Provides
      public Map<String, FilterDeserializer.PartialDeserializer> filters() {
        final Map<String, FilterDeserializer.PartialDeserializer> filters = new HashMap<>();
        filters.put("key", new MatchKey.Deserializer());
        filters.put("=", new MatchTag.Deserializer());
        filters.put("true", new TrueFilter.Deserializer());
        filters.put("false", new FalseFilter.Deserializer());
        filters.put("and", new AndFilter.Deserializer());
        filters.put("or", new OrFilter.Deserializer());
        filters.put("not", new NotFilter.Deserializer());
        filters.put("type", new TypeFilter.Deserializer());
        return filters;
      }

      @Singleton
      @Provides
      @Named("config")
      public SimpleModule configModule(
          Map<String, FilterDeserializer.PartialDeserializer> filters
      ) {
        final SimpleModule module = new SimpleModule();
        module.addDeserializer(Filter.class, new FilterDeserializer(filters));
        return module;
      }

      @Override
      protected void configure() {
        bind(PluginContext.class).toInstance(new PluginContextImpl());
      }
    });

    final Injector injector = Guice.createInjector(modules);

    for (final FastForwardModule m : loadModules(injector)) {
      log.info("Setting up {}", m);

      try {
        m.setup();
      } catch (Exception e) {
        throw new Exception("Failed to call #setup() for module: " + m, e);
      }
    }

    return injector;
  }

  /**
   * Setup primaryInjector Injector.
   *
   * @return The primaryInjector injector.
   */
  private Injector setupPrimaryInjector(
      final Injector early, final AgentConfig config
  ) {
    final Optional<String> searchDomain = lookupSearchDomain(config);

    final List<Module> modules = Lists.newArrayList();

    modules.add(new AbstractModule() {
      @Override
      protected void configure() {
        if (config.hasDebug()) {
          bind(DebugServer.class).toInstance(
              new NettyDebugServer(config.getDebugLocalAddress()));
        } else {
          bind(DebugServer.class).toInstance(new NoopDebugServer());
        }
      }
    });

    modules.add(new AbstractModule() {
      @Singleton
      @Provides
      private CoreStatistics statistics() {
        return statistics;
      }

      @Singleton
      @Provides
      private ExecutorService executor() {
        final ThreadFactory factory =
            new ThreadFactoryBuilder().setNameFormat("ffwd-async-%d").build();
        return Executors.newFixedThreadPool(config.getAsyncThreads(), factory);
      }

      @Singleton
      @Provides
      private ScheduledExecutorService scheduledExecutor() {
        final ThreadFactory factory =
            new ThreadFactoryBuilder().setNameFormat("ffwd-scheduler-%d").build();
        return Executors.newScheduledThreadPool(config.getSchedulerThreads(), factory);
      }

      @Singleton
      @Provides
      @Named("boss")
      public EventLoopGroup bosses() {
        final ThreadFactory factory =
            new ThreadFactoryBuilder().setNameFormat("ffwd-boss-%d").build();
        return new NioEventLoopGroup(config.getBossThreads(), factory);
      }

      @Singleton
      @Provides
      @Named("worker")
      public EventLoopGroup workers() {
        final ThreadFactory factory =
            new ThreadFactoryBuilder().setNameFormat("ffwd-worker-%d").build();
        return new NioEventLoopGroup(config.getWorkerThreads(), factory);
      }

      @Singleton
      @Provides
      @Named("application/json")
      public ObjectMapper jsonMapper() {
        return Mappers.setupApplicationJson();
      }

      @Singleton
      @Provides
      @Named("searchDomain")
      public Optional<String> searchDomain() {
        return searchDomain;
      }

      @Singleton
      @Provides
      public AgentConfig config() {
        return config;
      }

      @Override
      protected void configure() {
        bind(Key.get(Serializer.class, Names.named("default")))
            .to(ToStringSerializer.class)
            .in(Scopes.SINGLETON);
        bind(Timer.class).to(HashedWheelTimer.class).in(Scopes.SINGLETON);
        bind(ProtocolServers.class).to(ProtocolServersImpl.class).in(Scopes.SINGLETON);
        bind(ProtocolClients.class).to(ProtocolClientsImpl.class).in(Scopes.SINGLETON);
      }
    });

    modules.add(config.getInput().module());
    modules.add(config.getOutput().module());

    return early.createChildInjector(modules);
  }

  /**
   * Reads the configuration of the agent from multiple possible sources. In order of precedence,
   * the following sources are loaded:
   * - System properties
   * - Environment variables
   * - YAML file in specified location
   * - YAML file bundled with JAR
   *
   * Values are merged, those higher on this list override those that are lower.
   *
   * @return Data class of parsed config.
   *
   * @throws IOException when the passed config path is not valid YAML.
   */
  private AgentConfig readConfig(Injector early) {
    final SimpleModule module =
        early.getInstance(Key.get(SimpleModule.class, Names.named("config")));

    final Config config = AgentConfig.load(configPath.orElse(DEFAULT_CONFIG_PATH), module);
    return new AgentConfig(config);
  }

  private List<FastForwardModule> loadModules(Injector injector) throws Exception {
    final List<FastForwardModule> modules = Lists.newArrayList();

    for (final Class<? extends FastForwardModule> module : this.modules) {
      final Constructor<? extends FastForwardModule> constructor;

      try {
        constructor = module.getConstructor();
      } catch (NoSuchMethodException e) {
        throw new Exception("Expected empty constructor for class: " + module, e);
      }

      final FastForwardModule m;

      try {
        m = constructor.newInstance();
      } catch (ReflectiveOperationException e) {
        throw new Exception("Failed to call constructor for class: " + module, e);
      }

      injector.injectMembers(m);
      modules.add(m);
    }

    return modules;
  }

  public static Builder builder() {
    return new Builder();
  }

  public Injector getPrimaryInjector() {
    return this.primaryInjector;
  }

  public static final class Builder {

    private List<Class<? extends FastForwardModule>> modules = Lists.newArrayList();
    private Optional<Path> configPath = Optional.empty();
    private CoreStatistics statistics = NoopCoreStatistics.get();

    public Builder configPath(final Path configPath) {
      if (configPath == null) {
        throw new NullPointerException("'configPath' must not be null");
      }

      this.configPath = Optional.of(configPath);
      return this;
    }

    public Builder modules(List<Class<? extends FastForwardModule>> modules) {
      if (modules == null) {
        throw new NullPointerException("'modules' must not be null");
      }

      this.modules = modules;
      return this;
    }

    public Builder statistics(CoreStatistics statistics) {
      if (statistics == null) {
        throw new NullPointerException("'statistics' most not be null");
      }

      this.statistics = statistics;
      return this;
    }

    public AgentCore build() {
      return new AgentCore(modules, configPath, statistics);
    }
  }
}
