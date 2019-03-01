// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.debugger.memory.agent;

import com.intellij.debugger.DebuggerBundle;
import com.intellij.debugger.engine.DebugProcessAdapterImpl;
import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.engine.SuspendContextImpl;
import com.intellij.debugger.engine.evaluation.EvaluateException;
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl;
import com.intellij.debugger.jdi.StackFrameProxyImpl;
import com.intellij.debugger.memory.agent.extractor.AgentExtractor;
import com.intellij.debugger.memory.ui.JavaReferenceInfo;
import com.intellij.debugger.memory.ui.SizedReferenceInfo;
import com.intellij.debugger.settings.DebuggerSettings;
import com.intellij.debugger.ui.JavaDebuggerSupport;
import com.intellij.execution.CantRunException;
import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.JavaExecutionUtil;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Attachment;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.diagnostic.RuntimeExceptionWithAttachments;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JdkUtil;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.Bitness;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.containers.ContainerUtil;
import one.util.streamex.IntStreamEx;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.java.JdkVersionDetector;

import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.jar.Attributes;

public class MemoryAgentUtil {
  private static final Logger LOG = Logger.getInstance(MemoryAgentUtil.class);
  private static final String MEMORY_AGENT_EXTRACT_DIRECTORY = "memory.agent.extract.dir";
  private static final Key<Boolean> LISTEN_MEMORY_AGENT_STARTUP_FAILED = Key.create("LISTEN_MEMORY_AGENT_STARTUP_FAILED");
  private static final int ESTIMATE_OBJECTS_SIZE_LIMIT = 2000;

  public static void addMemoryAgent(@NotNull JavaParameters parameters) {
    if (!DebuggerSettings.getInstance().ENABLE_MEMORY_AGENT) {
      return;
    }

    if (isIbmJdk(parameters)) {
      LOG.info("Do not attach memory agent for IBM jdk");
      return;
    }

    ParametersList parametersList = parameters.getVMParametersList();
    if (parametersList.getParameters().stream().anyMatch(x -> x.contains("memory_agent"))) return;
    boolean isInDebugMode = Registry.is("debugger.memory.agent.debug");
    File agentFile = null;
    String errorMessage = null;
    long start = System.currentTimeMillis();
    try {
      agentFile = getAgentFile(isInDebugMode, parameters.getJdkPath());
    }
    catch (InterruptedException e) {
      errorMessage = "Interrupted";
    }
    catch (ExecutionException e) {
      LOG.warn(e.getCause());
      errorMessage = "Exception thrown (see logs for details)";
    }
    catch (TimeoutException e) {
      errorMessage = "Timeout";
    }
    catch (CantRunException e) {
      errorMessage = "JDK home not found";
    }
    if (errorMessage != null || agentFile == null) {
      LOG.warn("Could not extract agent: " + errorMessage);
      return;
    }

    LOG.info("Memory agent extracting took " + (System.currentTimeMillis() - start) + " ms");
    String agentFileName = agentFile.getName();
    String path = JavaExecutionUtil.handleSpacesInAgentPath(agentFile.getAbsolutePath(), "debugger-memory-agent",
                                                            MEMORY_AGENT_EXTRACT_DIRECTORY, f -> agentFileName.equals(f.getName()));
    if (path == null) {
      return;
    }

    String args = "";
    if (isInDebugMode) {
      args = "5";// Enable debug messages
    }
    path += "=" + args;
    parametersList.add("-agentpath:" + path);
    listenIfStartupFailed();
  }

  public static List<JavaReferenceInfo> tryCalculateSizes(@NotNull List<JavaReferenceInfo> objects, @Nullable MemoryAgent agent) {
    if (agent == null || !agent.canEvaluateObjectsSizes()) return objects;
    if (objects.size() > ESTIMATE_OBJECTS_SIZE_LIMIT) {
      LOG.info("Too many objects to estimate their sizes");
      return objects;
    }
    try {
      long[] sizes = agent.evaluateObjectsSizes(ContainerUtil.map(objects, x -> x.getObjectReference()));
      return IntStreamEx.range(0, objects.size())
        .mapToObj(i -> new SizedReferenceInfo(objects.get(i).getObjectReference(), sizes[i]))
        .reverseSorted(Comparator.comparing(x -> x.size()))
        .map(x -> (JavaReferenceInfo)x)
        .toList();
    }
    catch (EvaluateException e) {
      LOG.error("Could not estimate objects sizes", e);
    }

    return objects;
  }

  public static void loadAgentProxy(@NotNull DebugProcessImpl debugProcess, @NotNull Consumer<MemoryAgent> agentLoaded) {
    debugProcess.addDebugProcessListener(new DebugProcessAdapterImpl() {
      private final AtomicBoolean isInitializing = new AtomicBoolean(false);

      @Override
      public void paused(SuspendContextImpl suspendContext) {
        if (isInitializing.compareAndSet(false, true)) {
          try {
            MemoryAgent memoryAgent = initMemoryAgent(suspendContext);
            if (memoryAgent == null) {
              LOG.warn("Could not initialize memory agent.");
              return;
            }

            agentLoaded.accept(memoryAgent);
            debugProcess.removeDebugProcessListener(this);
          }
          finally {
            isInitializing.set(false);
          }
        }
      }

      @Nullable
      private MemoryAgent initMemoryAgent(@NotNull SuspendContextImpl suspendContext) {
        if (!DebuggerSettings.getInstance().ENABLE_MEMORY_AGENT) {
          LOG.info("Memory agent disabled");
          return AgentLoader.DEFAULT_PROXY;
        }

        StackFrameProxyImpl frameProxy = suspendContext.getFrameProxy();
        if (frameProxy == null) {
          LOG.warn("frame proxy is not available");
          return null;
        }

        long start = System.currentTimeMillis();
        EvaluationContextImpl evaluationContext = new EvaluationContextImpl(suspendContext, frameProxy);
        MemoryAgent agent = new AgentLoader().load(evaluationContext, debugProcess.getVirtualMachineProxy());
        LOG.info("Memory agent loading took " + (System.currentTimeMillis() - start) + " ms");
        return agent;
      }
    });
  }

  private static boolean isIbmJdk(@NotNull JavaParameters parameters) {
    Sdk jdk = parameters.getJdk();
    String vendor = jdk == null ? null : JdkUtil.getJdkMainAttribute(jdk, Attributes.Name.IMPLEMENTATION_VENDOR);
    return vendor != null && StringUtil.containsIgnoreCase(vendor, "ibm");
  }

  private static File getAgentFile(boolean isInDebugMode, String jdkPath)
    throws InterruptedException, ExecutionException, TimeoutException {
    if (isInDebugMode) {
      String debugAgentPath = Registry.get("debugger.memory.agent.debug.path").asString();
      if (!debugAgentPath.isEmpty()) {
        LOG.info("Local memory agent will be used: " + debugAgentPath);
        return new File(debugAgentPath);
      }
    }

    return ApplicationManager.getApplication()
      .executeOnPooledThread(() -> new AgentExtractor().extract(detectAgentKind(jdkPath), getAgentDirectory()))
      .get(1, TimeUnit.SECONDS);
  }

  private static AgentExtractor.AgentLibraryType detectAgentKind(String jdkPath) {
    if (SystemInfo.isLinux) return AgentExtractor.AgentLibraryType.LINUX;
    if (SystemInfo.isMac) return AgentExtractor.AgentLibraryType.MACOS;
    JdkVersionDetector.JdkVersionInfo versionInfo = JdkVersionDetector.getInstance().detectJdkVersionInfo(jdkPath);
    if (versionInfo == null) {
      LOG.warn("Could not detect jdk bitness. x64 will be used.");
      return AgentExtractor.AgentLibraryType.WINDOWS64;
    }

    return Bitness.x32.equals(versionInfo.bitness) ? AgentExtractor.AgentLibraryType.WINDOWS32 : AgentExtractor.AgentLibraryType.WINDOWS64;
  }

  @NotNull
  private static File getAgentDirectory() {
    String agentDirectory = System.getProperty(MEMORY_AGENT_EXTRACT_DIRECTORY);
    if (agentDirectory != null) {
      File file = new File(agentDirectory);
      if (file.exists() || file.mkdirs()) {
        return file;
      }

      LOG.info("Directory specified in property \"" + MEMORY_AGENT_EXTRACT_DIRECTORY +
               "\" not found. Default tmp directory will be used");
    }

    return new File(FileUtil.getTempDirectory());
  }

  private static void listenIfStartupFailed() {
    Project project = JavaDebuggerSupport.getContextProjectForEditorFieldsInDebuggerConfigurables();
    if (Boolean.TRUE.equals(project.getUserData(LISTEN_MEMORY_AGENT_STARTUP_FAILED))) return;

    project.getMessageBus().connect().subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
      @Override
      public void processTerminated(@NotNull String executorId,
                                    @NotNull ExecutionEnvironment env,
                                    @NotNull ProcessHandler handler,
                                    int exitCode) {
        if (executorId != DefaultDebugExecutor.EXECUTOR_ID || exitCode == 0) return;
        RunContentDescriptor content = env.getContentToReuse();
        if (content == null) return;

        ExecutionConsole console = content.getExecutionConsole();
        if (!(console instanceof ConsoleViewImpl)) return;

        ConsoleViewImpl consoleView = (ConsoleViewImpl)console;
        ApplicationManager.getApplication().invokeLater(() -> {
          if (consoleView.hasDeferredOutput()) {
            consoleView.flushDeferredText();
          }
          Editor editor = consoleView.getEditor();
          if (editor == null) return;
          String[] outputLines = StringUtil.splitByLines(editor.getDocument().getText());
          List<String> mentions = StreamEx.of(outputLines).skip(1).filter(x -> x.contains("memory_agent")).limit(10).toList();
          if (outputLines.length >= 1 && outputLines[0].contains("memory_agent") && !mentions.isEmpty()) {
            Project project = env.getProject();
            String name = env.getRunProfile().getName();
            String windowId = ExecutionManager.getInstance(project).getContentManager().getToolWindowIdByEnvironment(env);

            Attachment[] mentionsInOutput = StreamEx.of(mentions).map(x -> new Attachment("agent_mention.txt", x))
              .toArray(Attachment.EMPTY_ARRAY);
            RuntimeExceptionWithAttachments exception =
              new RuntimeExceptionWithAttachments("Could not start debug process with memory agent", mentionsInOutput);
            String checkboxName = DebuggerBundle.message("label.debugger.general.configurable.enable.memory.agent");
            String description =
              "Memory agent could not be loaded. <a href=\"Disable\">Disable</a> the agent. To enable it back use \"" +
              checkboxName + "\" option in File | Settings | Build, Execution, Deployment | Debugger";
            ExecutionUtil.handleExecutionError(project, windowId, name, exception, description, new DisablingMemoryAgentListener());
            LOG.error(exception);
          }
        }, project.getDisposed());
      }
    });

    project.putUserData(LISTEN_MEMORY_AGENT_STARTUP_FAILED, true);
  }

  private static class DisablingMemoryAgentListener implements HyperlinkListener {
    @Override
    public void hyperlinkUpdate(HyperlinkEvent e) {
      if (HyperlinkEvent.EventType.ACTIVATED.equals(e.getEventType())) {
        DebuggerSettings.getInstance().ENABLE_MEMORY_AGENT = false;
      }
    }
  }
}
