// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.gradle.service.project;

import com.intellij.openapi.externalSystem.model.ExternalSystemException;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.internal.time.Clock;
import org.gradle.internal.time.Time;
import org.gradle.tooling.BuildCancelledException;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.internal.consumer.Distribution;
import org.gradle.tooling.internal.consumer.DistributionFactory;
import org.gradle.tooling.internal.consumer.DistributionInstaller;
import org.gradle.tooling.internal.protocol.InternalBuildProgressListener;
import org.gradle.util.DistributionLocator;
import org.gradle.util.GradleVersion;
import org.gradle.wrapper.GradleUserHomeLookup;
import org.gradle.wrapper.WrapperConfiguration;
import org.gradle.wrapper.WrapperExecutor;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

import static org.gradle.internal.FileUtils.hasExtension;

/**
 * @author Vladislav.Soroka
 */
public class DistributionFactoryExt extends DistributionFactory {

  private DistributionFactoryExt() {
    super(Time.clock());
  }

  public static void setWrappedDistribution(GradleConnector connector, String wrapperPropertyFile, File gradleUserHome) {
    File propertiesFile = new File(wrapperPropertyFile);
    if (propertiesFile.exists()) {
      WrapperExecutor wrapper = WrapperExecutor.forWrapperPropertiesFile(propertiesFile);
      if (wrapper.getDistribution() != null) {
        Distribution distribution = new DistributionFactoryExt().getWrappedDistribution(propertiesFile, gradleUserHome);
        try {
          setDistributionField(connector, distribution);
        }
        catch (Exception e) {
          throw new ExternalSystemException(e);
        }
      }
    }
  }

  /**
   * Returns the default distribution to use for the specified project.
   */
  private Distribution getWrappedDistribution(File propertiesFile, final File userHomeDir) {
    WrapperExecutor wrapper = WrapperExecutor.forWrapperPropertiesFile(propertiesFile);
    if (wrapper.getDistribution() != null) {
      return new ZippedDistribution(wrapper.getConfiguration(), determineRealUserHomeDir(userHomeDir), Time.clock());
    }
    return getDownloadedDistribution(GradleVersion.current().getVersion());
  }

  private static File determineRealUserHomeDir(final File userHomeDir) {
    return userHomeDir != null ? userHomeDir : GradleUserHomeLookup.gradleUserHome();
  }

  private Distribution getDownloadedDistribution(String gradleVersion) {
    URI distUri = new DistributionLocator().getDistributionFor(GradleVersion.version(gradleVersion));
    return getDistribution(distUri);
  }

  private static class InstalledDistribution implements Distribution {
    private final File gradleHomeDir;
    private final String displayName;
    private final String locationDisplayName;

    InstalledDistribution(File gradleHomeDir, String displayName, String locationDisplayName) {
      this.gradleHomeDir = gradleHomeDir;
      this.displayName = displayName;
      this.locationDisplayName = locationDisplayName;
    }

    @Override
    public String getDisplayName() {
      return displayName;
    }

    @Override
    public ClassPath getToolingImplementationClasspath(ProgressLoggerFactory progressLoggerFactory,
                                                       InternalBuildProgressListener progressListener,
                                                       File userHomeDir,
                                                       BuildCancellationToken cancellationToken) {
      if (!gradleHomeDir.exists()) {
        throw new IllegalArgumentException(String.format("The specified %s does not exist.", locationDisplayName));
      }
      if (!gradleHomeDir.isDirectory()) {
        throw new IllegalArgumentException(String.format("The specified %s is not a directory.", locationDisplayName));
      }
      File libDir = new File(gradleHomeDir, "lib");
      if (!libDir.isDirectory()) {
        throw new IllegalArgumentException(
          String.format("The specified %s does not appear to contain a Gradle distribution.", locationDisplayName));
      }
      File[] files = libDir.listFiles(file -> hasExtension(file, ".jar"));
      // Make sure file order is always consistent
      Arrays.sort(files);
      return new DefaultClassPath(files);
    }
  }

  private static class ZippedDistribution implements Distribution {
    private InstalledDistribution installedDistribution;
    private final WrapperConfiguration wrapperConfiguration;
    private final File distributionBaseDir;
    private final Clock clock;

    private ZippedDistribution(WrapperConfiguration wrapperConfiguration, File distributionBaseDir, Clock clock) {
      this.wrapperConfiguration = wrapperConfiguration;
      this.distributionBaseDir = distributionBaseDir;
      this.clock = clock;
    }

    @Override
    public String getDisplayName() {
      return "Gradle distribution '" + wrapperConfiguration.getDistribution() + "'";
    }

    @Override
    public ClassPath getToolingImplementationClasspath(ProgressLoggerFactory progressLoggerFactory,
                                                       final InternalBuildProgressListener progressListener,
                                                       final File userHomeDir,
                                                       BuildCancellationToken cancellationToken) {
      if (installedDistribution == null) {
        final DistributionInstaller installer = new DistributionInstaller(progressLoggerFactory, progressListener, clock);
        File installDir;
        try {
          cancellationToken.addCallback(() -> installer.cancel());
          installDir = installer.install(determineRealUserHomeDir(userHomeDir), wrapperConfiguration);
        }
        catch (CancellationException e) {
          throw new BuildCancelledException(
            String.format("Distribution download cancelled. Using distribution from '%s'.", wrapperConfiguration.getDistribution()), e);
        }
        catch (FileNotFoundException e) {
          throw new IllegalArgumentException(String.format("The specified %s does not exist.", getDisplayName()), e);
        }
        catch (Exception e) {
          throw new GradleConnectionException(
            String.format("Could not install Gradle distribution from '%s'.", wrapperConfiguration.getDistribution()), e);
        }
        installedDistribution = new InstalledDistribution(installDir, getDisplayName(), getDisplayName());
      }
      return installedDistribution
        .getToolingImplementationClasspath(progressLoggerFactory, progressListener, userHomeDir, cancellationToken);
    }

    private File determineRealUserHomeDir(final File userHomeDir) {
      if (distributionBaseDir != null) {
        return distributionBaseDir;
      }

      return userHomeDir != null ? userHomeDir : GradleUserHomeLookup.gradleUserHome();
    }
  }

  private static void setDistributionField(GradleConnector connector, Object fieldValue)
    throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
    final Field field = connector.getClass().getDeclaredField("distribution");
    final boolean isAccessible = field.isAccessible();
    field.setAccessible(true);
    field.set(connector, fieldValue);
    field.setAccessible(isAccessible);
  }
}
