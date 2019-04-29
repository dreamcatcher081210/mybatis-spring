/**
 *    Copyright 2010-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.mybatis.spring;

import org.apache.ibatis.builder.xml.XMLConfigBuilder;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.TypeHandler;
import org.mybatis.logging.Logger;
import org.mybatis.logging.LoggerFactory;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.NestedIOException;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

import static org.springframework.util.Assert.notNull;
import static org.springframework.util.Assert.state;
import static org.springframework.util.ObjectUtils.isEmpty;
import static org.springframework.util.StringUtils.hasLength;
import static org.springframework.util.StringUtils.tokenizeToStringArray;

/**
 * {@code FactoryBean} that creates an MyBatis {@code SqlSessionFactory}.
 * This is the usual way to set up a shared MyBatis {@code SqlSessionFactory} in a Spring application context;
 * the SqlSessionFactory can then be passed to MyBatis-based DAOs via dependency injection.
 *
 * Either {@code DataSourceTransactionManager} or {@code JtaTransactionManager} can be used for transaction
 * demarcation in combination with a {@code SqlSessionFactory}. JTA should be used for transactions
 * which span multiple databases or when container managed transactions (CMT) are being used.
 *
 * @author Putthiphong Boonphong
 * @author Hunter Presnall
 * @author Eduardo Macarron
 * @author Eddú Meléndez
 * @author Kazuki Shimizu
 *
 * @see #setConfigLocation
 * @see #setDataSource
 *
 *
 * 真正的SqlSessionFactory 是由Spring最终创建的bean,由FactoryBean getObject()返回的SqlSessionFactory，不是SqlSessionFactoryBean本身
 * 实现ApplicationListener  ApplicationContext事件机制是观察者设计模式的实现，通过ApplicationEvent类和ApplicationListener接口，可以实现ApplicationContext事件处理
 * 实现InitializingBean spring初始化bean的时候，如果bean实现了InitializingBean接口，会自动调用afterPropertiesSet方法 此处是为了创建sqlSessionFactory
 * 先有InitializingBean，后有ApplicationListener<ContextRefreshedEvent>
 */
public class SqlSessionFactoryBean implements FactoryBean<SqlSessionFactory>, InitializingBean, ApplicationListener<ApplicationEvent> {

  private static final Logger LOGGER = LoggerFactory.getLogger(SqlSessionFactoryBean.class);

  /**
   * sql xml文件 类
   * 单个
   */
  private Resource configLocation;

  /**
   * 保存了mybatis的构建细节
   */
  private Configuration configuration;

  /**
   * sql xml文件 类
   * 集合
   */
  private Resource[] mapperLocations;

  /**
   * 数据源
   */
  private DataSource dataSource;

  /**
   * 事务工厂
   */
  private TransactionFactory transactionFactory;

  /**
   * 配置文件信息
   */
  private Properties configurationProperties;

  /**
   * 见名知意
   */
  private SqlSessionFactoryBuilder sqlSessionFactoryBuilder = new SqlSessionFactoryBuilder();

  /**
   * 见名知意
   */
  private SqlSessionFactory sqlSessionFactory;

  //EnvironmentAware requires spring 3.1
  private String environment = SqlSessionFactoryBean.class.getSimpleName();

  /**
   * 快速检查报错
   * 开启后将在启动时检查设定的parameterMap,resultMap是否存在，是否合法。
   * 设置为true,可以尽快定位解决问题。不然在调用过程中发现错误，会影响问题定位。
   */
  private boolean failFast;

  /**
   * mybatis 接口插件
   */
  private Interceptor[] plugins;

  /**
   * 类型处理句柄
   * 在预处理语句PreparedStatement中设置一个参数，或是在执行完SQL语句后从结果集中取出数据。而这两个过程都需要合适的数据类型处理器来帮我们对数据进行正确的类型转换
   */
  private TypeHandler<?>[] typeHandlers;

  /**
   * 句柄包路径
   * 见上
   */
  private String typeHandlersPackage;

  /**
   * 别名
   * 设置这个以后在Mapper配置文件中在parameterType 的值就不用写成全路径名了
   */
  private Class<?>[] typeAliases;

  /**
   * 别名包路径
   * 用于搜索包下面的类并自动生成类与别名之间的映射关系
   * 也就是xml传参类型和返回结果类型如：cn.asae.oms.xx.xx 配置之后就不用这么写了
   */
  private String typeAliasesPackage;

  /**
   * 通过父类（或实现接口）的方式来限定
   */
  private Class<?> typeAliasesSuperType;

  /**
   * 指定不同数据库
   * 可以根据不同的数据库厂商执行不同的语句，这种多厂商的支持是基于映射语句中的 databaseId 属性
   */
  //issue #19. No default provider.
  private DatabaseIdProvider databaseIdProvider;

  /**
   * 虚拟文件系统(VFS),用来读取服务器里的资源
   */
  private Class<? extends VFS> vfs;

  /**
   * 缓存
   */
  private Cache cache;

  /**
   * MyBatis 每次创建结果对象的新实例时，它都会使用一个对象工厂（ObjectFactory）实例来完成
   * 默认的对象工厂需要做的仅仅是实例化目标类，要么通过默认构造方法，要么在参数映射存在的时候通过参数构造方法来实例化。
   * 如果想覆盖对象工厂的默认行为，则可以通过创建自己的对象工厂来实现
   */
  private ObjectFactory objectFactory;

  /**
   * 默认实现类DefaultObjectWrapperFactory，包装Object实例，很少使用
   * 与上面的没有关系
   */
  private ObjectWrapperFactory objectWrapperFactory;

  /**
   * Sets the ObjectFactory.
   *
   * @since 1.1.2
   * @param objectFactory a custom ObjectFactory
   */
  public void setObjectFactory(ObjectFactory objectFactory) {
    this.objectFactory = objectFactory;
  }

  /**
   * Sets the ObjectWrapperFactory.
   *
   * @since 1.1.2
   * @param objectWrapperFactory a specified ObjectWrapperFactory
   */
  public void setObjectWrapperFactory(ObjectWrapperFactory objectWrapperFactory) {
    this.objectWrapperFactory = objectWrapperFactory;
  }

  /**
   * Gets the DatabaseIdProvider
   *
   * @since 1.1.0
   * @return a specified DatabaseIdProvider
   */
  public DatabaseIdProvider getDatabaseIdProvider() {
    return databaseIdProvider;
  }

  /**
   * Sets the DatabaseIdProvider.
   * As of version 1.2.2 this variable is not initialized by default.
   *
   * @since 1.1.0
   * @param databaseIdProvider a DatabaseIdProvider
   */
  public void setDatabaseIdProvider(DatabaseIdProvider databaseIdProvider) {
    this.databaseIdProvider = databaseIdProvider;
  }

  /**
   * Gets the VFS.
   * @return a specified VFS
   */
  public Class<? extends VFS> getVfs() {
    return this.vfs;
  }

  /**
   * Sets the VFS.
   * @param vfs a VFS
   */
  public void setVfs(Class<? extends VFS> vfs) {
    this.vfs = vfs;
  }

  /**
   * Gets the Cache.
   * @return a specified Cache
   */
  public Cache getCache() {
    return this.cache;
  }

  /**
   * Sets the Cache.
   * @param cache a Cache
   */
  public void setCache(Cache cache) {
    this.cache = cache;
  }

  /**
   * Mybatis plugin list.
   *
   * @since 1.0.1
   *
   * @param plugins list of plugins
   *
   */
  public void setPlugins(Interceptor[] plugins) {
    this.plugins = plugins;
  }

  /**
   * Packages to search for type aliases.
   *
   * @since 1.0.1
   *
   * @param typeAliasesPackage package to scan for domain objects
   *
   */
  public void setTypeAliasesPackage(String typeAliasesPackage) {
    this.typeAliasesPackage = typeAliasesPackage;
  }

  /**
   * Super class which domain objects have to extend to have a type alias created.
   * No effect if there is no package to scan configured.
   *
   * @since 1.1.2
   *
   * @param typeAliasesSuperType super class for domain objects
   *
   */
  public void setTypeAliasesSuperType(Class<?> typeAliasesSuperType) {
    this.typeAliasesSuperType = typeAliasesSuperType;
  }

  /**
   * Packages to search for type handlers.
   *
   * @since 1.0.1
   *
   * @param typeHandlersPackage package to scan for type handlers
   *
   */
  public void setTypeHandlersPackage(String typeHandlersPackage) {
    this.typeHandlersPackage = typeHandlersPackage;
  }

  /**
   * Set type handlers. They must be annotated with {@code MappedTypes} and optionally with {@code MappedJdbcTypes}
   *
   * @since 1.0.1
   *
   * @param typeHandlers Type handler list
   */
  public void setTypeHandlers(TypeHandler<?>[] typeHandlers) {
    this.typeHandlers = typeHandlers;
  }

  /**
   * List of type aliases to register. They can be annotated with {@code Alias}
   *
   * @since 1.0.1
   *
   * @param typeAliases Type aliases list
   */
  public void setTypeAliases(Class<?>[] typeAliases) {
    this.typeAliases = typeAliases;
  }

  /**
   * If true, a final check is done on Configuration to assure that all mapped
   * statements are fully loaded and there is no one still pending to resolve
   * includes. Defaults to false.
   *
   * @since 1.0.1
   *
   * @param failFast enable failFast
   */
  public void setFailFast(boolean failFast) {
    this.failFast = failFast;
  }

  /**
   * Set the location of the MyBatis {@code SqlSessionFactory} config file. A typical value is
   * "WEB-INF/mybatis-configuration.xml".
   *
   * @param configLocation a location the MyBatis config file
   */
  public void setConfigLocation(Resource configLocation) {
    this.configLocation = configLocation;
  }

  /**
   * Set a customized MyBatis configuration.
   * @param configuration MyBatis configuration
   * @since 1.3.0
   */
  public void setConfiguration(Configuration configuration) {
    this.configuration = configuration;
  }

  /**
   * Set locations of MyBatis mapper files that are going to be merged into the {@code SqlSessionFactory}
   * configuration at runtime.
   *
   * This is an alternative to specifying "&lt;sqlmapper&gt;" entries in an MyBatis config file.
   * This property being based on Spring's resource abstraction also allows for specifying
   * resource patterns here: e.g. "classpath*:sqlmap/*-mapper.xml".
   *
   * @param mapperLocations location of MyBatis mapper files
   */
  public void setMapperLocations(Resource[] mapperLocations) {
    this.mapperLocations = mapperLocations;
  }

  /**
   * Set optional properties to be passed into the SqlSession configuration, as alternative to a
   * {@code &lt;properties&gt;} tag in the configuration xml file. This will be used to
   * resolve placeholders in the config file.
   *
   * @param  sqlSessionFactoryProperties optional properties for the SqlSessionFactory
   */
  public void setConfigurationProperties(Properties sqlSessionFactoryProperties) {
    this.configurationProperties = sqlSessionFactoryProperties;
  }

  /**
   * Set the JDBC {@code DataSource} that this instance should manage transactions for. The {@code DataSource}
   * should match the one used by the {@code SqlSessionFactory}: for example, you could specify the same
   * JNDI DataSource for both.
   *
   * A transactional JDBC {@code Connection} for this {@code DataSource} will be provided to application code
   * accessing this {@code DataSource} directly via {@code DataSourceUtils} or {@code DataSourceTransactionManager}.
   *
   * The {@code DataSource} specified here should be the target {@code DataSource} to manage transactions for, not
   * a {@code TransactionAwareDataSourceProxy}. Only data access code may work with
   * {@code TransactionAwareDataSourceProxy}, while the transaction manager needs to work on the
   * underlying target {@code DataSource}. If there's nevertheless a {@code TransactionAwareDataSourceProxy}
   * passed in, it will be unwrapped to extract its target {@code DataSource}.
   *
   * @param dataSource a JDBC {@code DataSource}
   *
   */
  public void setDataSource(DataSource dataSource) {
    /*
    如果dataSource是TransactionAwareDataSourceProxy 类型的，
    需要为其基础目标数据源执行事务，否则数据访问代码将看不到目标数据源的正确公开事务
     */
    if (dataSource instanceof TransactionAwareDataSourceProxy) {
      // If we got a TransactionAwareDataSourceProxy, we need to perform
      // transactions for its underlying target DataSource, else data
      // access code won't see properly exposed transactions (i.e.
      // transactions for the target DataSource).
      this.dataSource = ((TransactionAwareDataSourceProxy) dataSource).getTargetDataSource();
    } else {
      this.dataSource = dataSource;
    }
  }

  /**
   * Sets the {@code SqlSessionFactoryBuilder} to use when creating the {@code SqlSessionFactory}.
   *
   * This is mainly meant for testing so that mock SqlSessionFactory classes can be injected. By
   * default, {@code SqlSessionFactoryBuilder} creates {@code DefaultSqlSessionFactory} instances.
   *
   * @param sqlSessionFactoryBuilder a SqlSessionFactoryBuilder
   *
   */
  public void setSqlSessionFactoryBuilder(SqlSessionFactoryBuilder sqlSessionFactoryBuilder) {
    this.sqlSessionFactoryBuilder = sqlSessionFactoryBuilder;
  }

  /**
   * Set the MyBatis TransactionFactory to use. Default is {@code SpringManagedTransactionFactory}
   *
   * The default {@code SpringManagedTransactionFactory} should be appropriate for all cases:
   * be it Spring transaction management, EJB CMT or plain JTA. If there is no active transaction,
   * SqlSession operations will execute SQL statements non-transactionally.
   *
   * <b>It is strongly recommended to use the default {@code TransactionFactory}.</b> If not used, any
   * attempt at getting an SqlSession through Spring's MyBatis framework will throw an exception if
   * a transaction is active.
   *
   * @see SpringManagedTransactionFactory
   * @param transactionFactory the MyBatis TransactionFactory
   */
  public void setTransactionFactory(TransactionFactory transactionFactory) {
    this.transactionFactory = transactionFactory;
  }

  /**
   * <b>NOTE:</b> This class <em>overrides</em> any {@code Environment} you have set in the MyBatis
   * config file. This is used only as a placeholder name. The default value is
   * {@code SqlSessionFactoryBean.class.getSimpleName()}.
   *
   * @param environment the environment name
   */
  public void setEnvironment(String environment) {
    this.environment = environment;
  }

  /**
   * {@inheritDoc}
   *
   * 初始化bean的时候执行
   * 做一些数据验证或者初始化
   */
  @Override
  public void afterPropertiesSet() throws Exception {
    notNull(dataSource, "Property 'dataSource' is required");
    notNull(sqlSessionFactoryBuilder, "Property 'sqlSessionFactoryBuilder' is required");
    state((configuration == null && configLocation == null) || !(configuration != null && configLocation != null),
              "Property 'configuration' and 'configLocation' can not specified with together");

    // 创建sqlSessionFactory
    this.sqlSessionFactory = buildSqlSessionFactory();
  }

  /**
   * Build a {@code SqlSessionFactory} instance.
   *
   * The default implementation uses the standard MyBatis {@code XMLConfigBuilder} API to build a
   * {@code SqlSessionFactory} instance based on an Reader.
   * Since 1.3.0, it can be specified a {@link Configuration} instance directly(without config file).
   *
   * @return SqlSessionFactory
   * @throws IOException if loading the config file failed
   *
   * 默认实现使用标准mybatis xmlconfigbuilder API构建
   * 基于Reader的sqlSessionFactory实例。
   * 从1.3.0开始，可以直接指定一个Configuration实例（没有配置文件）。
   */
  protected SqlSessionFactory buildSqlSessionFactory() throws IOException {

    final Configuration targetConfiguration;

    XMLConfigBuilder xmlConfigBuilder = null;
    if (this.configuration != null) {
      targetConfiguration = this.configuration;

      if (targetConfiguration.getVariables() == null) {
        // 配置信息如果为空 则将configurationProperties纳入
        targetConfiguration.setVariables(this.configurationProperties);
      } else if (this.configurationProperties != null) {
        // 不为空则做合并
        targetConfiguration.getVariables().putAll(this.configurationProperties);
      }
    } else if (this.configLocation != null) {
      xmlConfigBuilder = new XMLConfigBuilder(this.configLocation.getInputStream(), null, this.configurationProperties);
      targetConfiguration = xmlConfigBuilder.getConfiguration();
    } else {
      LOGGER.debug(() -> "Property 'configuration' or 'configLocation' not specified, using default MyBatis Configuration");
      targetConfiguration = new Configuration();
      Optional.ofNullable(this.configurationProperties).ifPresent(targetConfiguration::setVariables);
    }

    Optional.ofNullable(this.objectFactory)
            .ifPresent(targetConfiguration::setObjectFactory);

    Optional.ofNullable(this.objectWrapperFactory)
            .ifPresent(targetConfiguration::setObjectWrapperFactory);

    Optional.ofNullable(this.vfs)
            .ifPresent(targetConfiguration::setVfsImpl);

    /*
    别名包路径
     */
    if (hasLength(this.typeAliasesPackage)) {
      String[] typeAliasPackageArray = tokenizeToStringArray(this.typeAliasesPackage,
          ConfigurableApplicationContext.CONFIG_LOCATION_DELIMITERS);
      Stream.of(typeAliasPackageArray).forEach(packageToScan -> {
        targetConfiguration.getTypeAliasRegistry().registerAliases(packageToScan,
            typeAliasesSuperType == null ? Object.class : typeAliasesSuperType);
        LOGGER.debug(() -> "Scanned package: '" + packageToScan + "' for aliases");
      });
    }

    /*
    别名
     */
    if (!isEmpty(this.typeAliases)) {
      Stream.of(this.typeAliases).forEach(typeAlias -> {
        targetConfiguration.getTypeAliasRegistry().registerAlias(typeAlias);
        LOGGER.debug(() -> "Registered type alias: '" + typeAlias + "'");
      });
    }

    /*
    插件
     */
    if (!isEmpty(this.plugins)) {
      Stream.of(this.plugins).forEach(plugin -> {
        targetConfiguration.addInterceptor(plugin);
        LOGGER.debug(() -> "Registered plugin: '" + plugin + "'");
      });
    }

    /*
    句柄类型包路径
     */
    if (hasLength(this.typeHandlersPackage)) {
      String[] typeHandlersPackageArray = tokenizeToStringArray(this.typeHandlersPackage,
          ConfigurableApplicationContext.CONFIG_LOCATION_DELIMITERS);
      Stream.of(typeHandlersPackageArray).forEach(packageToScan -> {
        targetConfiguration.getTypeHandlerRegistry().register(packageToScan);
        LOGGER.debug(() -> "Scanned package: '" + packageToScan + "' for type handlers");
      });
    }

    /*
    句柄
     */
    if (!isEmpty(this.typeHandlers)) {
      Stream.of(this.typeHandlers).forEach(typeHandler -> {
        targetConfiguration.getTypeHandlerRegistry().register(typeHandler);
        LOGGER.debug(() -> "Registered type handler: '" + typeHandler + "'");
      });
    }

    /*
    可以根据不同的数据库厂商执行不同的语句，这种多厂商的支持是基于映射语句中的 databaseId 属性
     */
    if (this.databaseIdProvider != null) {//fix #64 set databaseId before parse mapper xmls
      try {
        targetConfiguration.setDatabaseId(this.databaseIdProvider.getDatabaseId(this.dataSource));
      } catch (SQLException e) {
        throw new NestedIOException("Failed getting a databaseId", e);
      }
    }

    /*
    缓存
     */
    Optional.ofNullable(this.cache).ifPresent(targetConfiguration::addCache);

    /*
    配置文件解析
     */
    if (xmlConfigBuilder != null) {
      try {
        xmlConfigBuilder.parse();
        LOGGER.debug(() -> "Parsed configuration file: '" + this.configLocation + "'");
      } catch (Exception ex) {
        throw new NestedIOException("Failed to parse config resource: " + this.configLocation, ex);
      } finally {
        ErrorContext.instance().reset();
      }
    }

    // 默认使用 SpringManagedTransactionFactory
    targetConfiguration.setEnvironment(new Environment(this.environment,
        this.transactionFactory == null ? new SpringManagedTransactionFactory() : this.transactionFactory,
        this.dataSource));

    if (!isEmpty(this.mapperLocations)) {
      for (Resource mapperLocation : this.mapperLocations) {
        if (mapperLocation == null) {
          continue;
        }

        try {
          XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(mapperLocation.getInputStream(),
              targetConfiguration, mapperLocation.toString(), targetConfiguration.getSqlFragments());
          xmlMapperBuilder.parse();
        } catch (Exception e) {
          throw new NestedIOException("Failed to parse mapping resource: '" + mapperLocation + "'", e);
        } finally {
          ErrorContext.instance().reset();
        }
        LOGGER.debug(() -> "Parsed mapper file: '" + mapperLocation + "'");
      }
    } else {
      LOGGER.debug(() -> "Property 'mapperLocations' was not specified or no matching resources found");
    }

    // 和mybatis 单独使用时，build相同的DefaultSqlSessionFactory
    return this.sqlSessionFactoryBuilder.build(targetConfiguration);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public SqlSessionFactory getObject() throws Exception {
    if (this.sqlSessionFactory == null) {
      afterPropertiesSet();
    }

    return this.sqlSessionFactory;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Class<? extends SqlSessionFactory> getObjectType() {
    return this.sqlSessionFactory == null ? SqlSessionFactory.class : this.sqlSessionFactory.getClass();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isSingleton() {
    return true;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void onApplicationEvent(ApplicationEvent event) {
    // 当ApplicationContext被初始化或刷新时引发的事件，当Spring容器完全启动后执行
    if (failFast && event instanceof ContextRefreshedEvent) {
      // fail-fast -> check all statements are completed
      // 检测MyBatis所有配置文件语句是否完成
      this.sqlSessionFactory.getConfiguration().getMappedStatementNames();
    }
  }

}
