/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.logging.plugins;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.util.StatusPrinter;
import co.cask.cdap.api.metrics.MetricsCollectionService;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.guice.ConfigModule;
import co.cask.cdap.common.guice.NonCustomLocationUnitTestModule;
import co.cask.cdap.common.logging.ApplicationLoggingContext;
import co.cask.cdap.common.logging.NamespaceLoggingContext;
import co.cask.cdap.common.metrics.NoOpMetricsCollectionService;
import co.cask.cdap.common.namespace.NamespaceQueryAdmin;
import co.cask.cdap.common.namespace.SimpleNamespaceQueryAdmin;
import co.cask.cdap.common.security.UGIProvider;
import co.cask.cdap.common.security.UnsupportedUGIProvider;
import co.cask.cdap.data.runtime.DataSetsModules;
import co.cask.cdap.data.runtime.SystemDatasetRuntimeModule;
import co.cask.cdap.logging.LoggingConfiguration;
import co.cask.cdap.logging.context.FlowletLoggingContext;
import co.cask.cdap.logging.context.MapReduceLoggingContext;
import co.cask.cdap.logging.guice.LoggingModules;
import co.cask.cdap.security.auth.context.AuthenticationContextModules;
import co.cask.cdap.security.authorization.AuthorizationEnforcementModule;
import co.cask.cdap.security.authorization.AuthorizationTestModule;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.tephra.TransactionManager;
import org.apache.tephra.runtime.TransactionModules;
import org.apache.twill.filesystem.Location;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;

/**
 *
 */
public class RollingLocationLogAppenderTest {

  @ClassRule
  public static final TemporaryFolder TMP_FOLDER = new TemporaryFolder();

  private static Injector injector;
  private static TransactionManager txManager;

  @BeforeClass
  public static void setUpContext() throws Exception {
    Configuration hConf = HBaseConfiguration.create();
    final CConfiguration cConf = CConfiguration.create();
    cConf.set(Constants.CFG_LOCAL_DATA_DIR, TMP_FOLDER.newFolder().getAbsolutePath());
    String logBaseDir = cConf.get(LoggingConfiguration.LOG_BASE_DIR) + "/" +
      RollingLocationLogAppender.class.getSimpleName();
    cConf.set(LoggingConfiguration.LOG_BASE_DIR, logBaseDir);

    injector = Guice.createInjector(
      new ConfigModule(cConf, hConf),
      new NonCustomLocationUnitTestModule().getModule(),
      new TransactionModules().getInMemoryModules(),
      new LoggingModules().getInMemoryModules(),
      new DataSetsModules().getInMemoryModules(),
      new SystemDatasetRuntimeModule().getInMemoryModules(),
      new AuthorizationTestModule(),
      new AuthorizationEnforcementModule().getInMemoryModules(),
      new AuthenticationContextModules().getNoOpModule(),
      new AbstractModule() {
        @Override
        protected void configure() {
          bind(MetricsCollectionService.class).to(NoOpMetricsCollectionService.class);
          bind(UGIProvider.class).to(UnsupportedUGIProvider.class);
          bind(NamespaceQueryAdmin.class).to(SimpleNamespaceQueryAdmin.class);
        }
      }
    );

    txManager = injector.getInstance(TransactionManager.class);
    txManager.startAndWait();
  }

  @AfterClass
  public static void cleanUp() throws Exception {
    txManager.stopAndWait();
  }

  @Test
  public void testRollingLocationLogAppender() throws Exception {
    // assume SLF4J is bound to logback in the current environment
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

    JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(context);
    // Call context.reset() to clear any previous configuration, e.g. default
    // configuration. For multi-step configuration, omit calling context.reset().
    context.reset();

    configurator.doConfigure(getClass().getResourceAsStream("/rolling-appender-logback-test.xml"));
    StatusPrinter.printInCaseOfErrorsOrWarnings(context);

    RollingLocationLogAppender rollingAppender =
      (RollingLocationLogAppender) context.getLogger(RollingLocationLogAppenderTest.class)
        .getAppender("rollingAppender");
    injector.injectMembers(rollingAppender);

    addTagsToMdc("test Ns", "testApp");
    Logger logger = LoggerFactory.getLogger(RollingLocationLogAppenderTest.class);
    ingestLogs(logger, 5);
    Map<LocationIdentifier, Location> activeFilesToLocation = rollingAppender.getLocationManager()
      .getActiveFilesLocations();
    Assert.assertEquals(1, activeFilesToLocation.size());
    verifyFileOutput(activeFilesToLocation, 5);

    // different program should go to different directory
    addTagsToMdc("testNs", "testApp1");
    ingestLogs(logger, 5);
    activeFilesToLocation = rollingAppender.getLocationManager().getActiveFilesLocations();
    Assert.assertEquals(2, activeFilesToLocation.size());
    verifyFileOutput(activeFilesToLocation, 5);

    // different program should go to different directory because namespace is different
    addTagsToMdc("testNs1", "testApp1");
    ingestLogs(logger, 5);
    activeFilesToLocation = rollingAppender.getLocationManager().getActiveFilesLocations();
    Assert.assertEquals(3, activeFilesToLocation.size());
    verifyFileOutput(activeFilesToLocation, 5);
  }

  @Test
  public void testRollOver() throws Exception {
    // assume SLF4J is bound to logback in the current environment
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

    JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(context);
    // Call context.reset() to clear any previous configuration, e.g. default
    // configuration. For multi-step configuration, omit calling context.reset().
    context.reset();

    configurator.doConfigure(getClass().getResourceAsStream("/rolling-appender-logback-test.xml"));
    StatusPrinter.printInCaseOfErrorsOrWarnings(context);

    RollingLocationLogAppender rollingAppender =
      (RollingLocationLogAppender) context.getLogger(RollingLocationLogAppenderTest.class)
        .getAppender("rollingAppender");
    injector.injectMembers(rollingAppender);

    addTagsToMdc("testNs", "testApp");
    Logger logger = LoggerFactory.getLogger(RollingLocationLogAppenderTest.class);
    ingestLogs(logger, 10000);
    Map<LocationIdentifier, Location> activeFilesToLocation = rollingAppender.getLocationManager()
      .getActiveFilesLocations();
    Assert.assertEquals(1, activeFilesToLocation.size());
    Location parentDir = rollingAppender.getLocationManager().getLogDirLocation().append("testNs").append("testApp");
    Assert.assertEquals(10, parentDir.list().size());

    // different program should go to different directory
    addTagsToMdc("testNs", "testApp1");
    ingestLogs(logger, 10000);
    activeFilesToLocation = rollingAppender.getLocationManager().getActiveFilesLocations();
    Assert.assertEquals(2, activeFilesToLocation.size());
    parentDir = rollingAppender.getLocationManager().getLogDirLocation().append("testNs").append("testApp1");
    Assert.assertEquals(10, parentDir.list().size());

    // different program should go to different directory because namespace is different
    addTagsToMdc("testNs1", "testApp1");
    ingestLogs(logger, 10000);
    activeFilesToLocation = rollingAppender.getLocationManager().getActiveFilesLocations();
    Assert.assertEquals(3, activeFilesToLocation.size());
    parentDir = rollingAppender.getLocationManager().getLogDirLocation().append("testNs1").append("testApp1");
    Assert.assertEquals(10, parentDir.list().size());
  }

  private void ingestLogs(Logger logger, int entries) {
    for (int i = 0; i < entries; i++) {
      logger.info("Testing Application log: " + i);
    }
  }

  private void verifyFileOutput(Map<LocationIdentifier, Location> activeFilesToLocation, int entries) throws
    IOException {
    for (Location location : activeFilesToLocation.values()) {
      BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(location.getInputStream(), "UTF-8"));

      int count = 0;
      while ((bufferedReader.readLine()) != null) {
        count++;
      }
      Assert.assertEquals(entries, count);
    }
  }

  private void addTagsToMdc(String namespace, String applicationName) {
    MDC.put(NamespaceLoggingContext.TAG_NAMESPACE_ID, namespace);
    MDC.put(ApplicationLoggingContext.TAG_APPLICATION_ID, applicationName);
    MDC.put(FlowletLoggingContext.TAG_FLOW_ID, "testFlow");
    MDC.put(FlowletLoggingContext.TAG_FLOWLET_ID, "testFlowet");
    MDC.put(MapReduceLoggingContext.TAG_MAP_REDUCE_JOB_ID, "testMapRed1");
    MDC.put(MapReduceLoggingContext.TAG_INSTANCE_ID, "testMapRedInst1");
    MDC.put(MapReduceLoggingContext.TAG_RUN_ID, "testMapRedRunId1");
  }
}