package com.atsebak.embeddedlinuxjvm.commandline;

import com.atsebak.embeddedlinuxjvm.console.EmbeddedLinuxJVMOutputForwarder;
import com.atsebak.embeddedlinuxjvm.deploy.DeploymentTarget;
import com.atsebak.embeddedlinuxjvm.runner.data.EmbeddedLinuxJVMRunConfigurationRunnerParameters;
import com.atsebak.embeddedlinuxjvm.console.EmbeddedLinuxJVMConsoleView;
import com.atsebak.embeddedlinuxjvm.localization.EmbeddedLinuxJVMBundle;
import com.atsebak.embeddedlinuxjvm.protocol.ssh.SSH;
import com.atsebak.embeddedlinuxjvm.protocol.ssh.SSHHandlerTarget;
import com.atsebak.embeddedlinuxjvm.runner.conf.EmbeddedLinuxJVMRunConfiguration;
import com.atsebak.embeddedlinuxjvm.utils.FileUtilities;
import com.atsebak.embeddedlinuxjvm.utils.RemoteCommandLineBuilder;
import com.intellij.execution.*;
import com.intellij.execution.configurations.*;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.*;
import com.intellij.execution.remote.RemoteConfiguration;
import com.intellij.execution.remote.RemoteConfigurationType;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.util.NotNullFunction;
import com.intellij.util.PathsList;
import lombok.SneakyThrows;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.TransportException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class AppCommandLineState extends JavaCommandLineState {
    @NonNls
    private static final String RUN_CONFIGURATION_NAME_PATTERN = "PI Debugger (%s)";
    @NonNls
    private static final String DEBUG_TCP_MESSAGE = "Listening for transport dt_socket at address: %s";
    private final EmbeddedLinuxJVMRunConfiguration configuration;
    private final ExecutionEnvironment environment;
    private final RunnerSettings runnerSettings;
    private final EmbeddedLinuxJVMOutputForwarder outputForwarder;
    private boolean isDebugMode;

    /**
     * Command line state when runner is launch
     *
     * @param environment
     * @param configuration
     */
    public AppCommandLineState(@NotNull ExecutionEnvironment environment, @NotNull EmbeddedLinuxJVMRunConfiguration configuration) {
        super(environment);
        this.configuration = configuration;
        this.environment = environment;
        this.runnerSettings = environment.getRunnerSettings();
        isDebugMode = runnerSettings instanceof DebuggingRunnerData;
        outputForwarder = new EmbeddedLinuxJVMOutputForwarder(EmbeddedLinuxJVMConsoleView.getInstance(environment.getProject()));
        outputForwarder.attachTo(null);
    }

    /**
     * Gets the debug runner
     *
     * @param debugPort
     * @return
     */
    @NotNull
    public static String getRunConfigurationName(String debugPort) {
        return String.format(RUN_CONFIGURATION_NAME_PATTERN, debugPort);
    }

    /**
     * Creates the console view
     * @param executor
     * @return
     * @throws ExecutionException
     */
    @Nullable
    @Override
    protected ConsoleView createConsole(@NotNull Executor executor) throws ExecutionException {
        return EmbeddedLinuxJVMConsoleView.getInstance(getEnvironment().getProject()).getConsoleView(true);
    }

    /**
     * Creates the command line view
     * @return
     * @throws ExecutionException
     */
    @Override
    protected GeneralCommandLine createCommandLine() throws ExecutionException {
        return RemoteCommandLineBuilder.createFromJavaParameters(getJavaParameters(), CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext()), true);
    }

    /**
     * Starts console process
     * @return
     * @throws ExecutionException
     */
    @NotNull
    @Override
    protected OSProcessHandler startProcess() throws ExecutionException {
        final OSProcessHandler handler = JavaCommandLineStateUtil.startProcess(createCommandLine());
        ProcessTerminatedListener.attach(handler, configuration.getProject(), EmbeddedLinuxJVMBundle.message("pi.console.exited"));
        handler.addProcessListener(new ProcessAdapter() {
            private void closeSSHConnection() {
                try {
                    if(isDebugMode) {
                        //todo fix tcp connection closing issue
                    }
                    Session.Command command = EmbeddedLinuxJVMConsoleView.getInstance(getEnvironment().getProject()).getCommand();
                    if(command != null) {
                        command.close();
                    }
                } catch (ConnectionException e) {
                } catch (TransportException e) {
                }
            }

            @Override
            public void processWillTerminate(ProcessEvent event, boolean willBeDestroyed) {
                ProgressManager.getInstance().run(new Task.Backgroundable(environment.getProject(), EmbeddedLinuxJVMBundle.message("pi.closingsession"), true) {
                    @Override
                    public void run(@NotNull ProgressIndicator progressIndicator) {
                        closeSSHConnection();
                    }
                });
                super.processWillTerminate(event, willBeDestroyed);
            }

        });
        return handler;
    }

    /**
     * Creates the necessary Java parameters for the application.
     *
     * @return
     * @throws ExecutionException
     */
    @Override
    protected JavaParameters createJavaParameters() throws ExecutionException {
        EmbeddedLinuxJVMConsoleView.getInstance(environment.getProject()).clear();
        JavaParameters javaParams = new JavaParameters();
        final Project project = environment.getProject();
        final ProjectRootManager manager = ProjectRootManager.getInstance(project);
        javaParams.setJdk(manager.getProjectSdk());

        // All modules to use the same things
        Module[] modules = ModuleManager.getInstance(project).getModules();
        if (modules != null && modules.length > 0) {
            for (Module module : modules) {
                javaParams.configureByModule(module, JavaParameters.JDK_AND_CLASSES);
            }
        }
        javaParams.setMainClass(configuration.getRunnerParameters().getMainclass());
        String basePath = project.getBasePath();
        javaParams.setWorkingDirectory(basePath);
        String classes = configuration.getOutputFilePath();
        javaParams.getProgramParametersList().addParametersString(classes);
        final PathsList classPath = javaParams.getClassPath();

        final CommandLineTarget build = CommandLineTarget.builder()
                .embeddedLinuxJVMRunConfiguration(configuration)
                .isDebugging(isDebugMode)
                .parameters(javaParams).build();

        final Application app = ApplicationManager.getApplication();

        //deploy on Non-read thread so can execute right away
        app.executeOnPooledThread(new Runnable() {
            @Override
            public void run() {
                ApplicationManager.getApplication().runReadAction(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            List<File> files = invokeClassPathResolver(classPath.getPathList(), manager.getProjectSdk());
                            File classpathArchive = FileUtilities.createClasspathArchive(files, project);
                            invokeDeployment(classpathArchive.getPath(), build);
                        } catch (Exception e) {
                            e.printStackTrace();
                            EmbeddedLinuxJVMConsoleView.getInstance(environment.getProject()).print(EmbeddedLinuxJVMBundle.message("pi.connection.failed", e.getLocalizedMessage()),
                                    ConsoleViewContentType.ERROR_OUTPUT);
                        }
                    }
                });
            }
        });

        //invoke later because it reads from other threads(debugging executor)
        ProgressManager.getInstance().run(new Task.Backgroundable(environment.getProject(), EmbeddedLinuxJVMBundle.message("pi.deploy"), true) {
            @Override
            public void run(@NotNull ProgressIndicator progressIndicator) {
                if (isDebugMode) {
                    progressIndicator.setIndeterminate(true);
                    final String initializeMsg = String.format(DEBUG_TCP_MESSAGE, configuration.getRunnerParameters().getPort());
                    //this should wait until the deployment states that it's listening to the port
                    while (!outputForwarder.toString().contains(initializeMsg)) {
                    }
                    app.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            closeOldSessionAndDebug(project, configuration.getRunnerParameters());
                        }
                    });
                }
            }
        });

        return javaParams;
    }

    private List<File> invokeClassPathResolver(List<String> librariesNeeded, final Sdk sdk) {
        List<File> classPaths = new ArrayList<File>();
        VirtualFile homeDirectory = sdk.getHomeDirectory();
        for (String library : librariesNeeded) {
            //filter sdk libraries from classpath because it's too big
            if (!library.contains(homeDirectory.getPath())) {
                classPaths.add(new File(library));
            }
        }
        return classPaths;
    }

    /**
     * Executes Deploys and Runs App on remote target
     * @param projectOutput
     * @param commandLineTarget
     */
    private void invokeDeployment(String projectOutput, CommandLineTarget commandLineTarget) throws RuntimeConfigurationException, IOException, ClassNotFoundException {
        EmbeddedLinuxJVMConsoleView.getInstance(environment.getProject()).print(EmbeddedLinuxJVMBundle.getString("pi.deployment.start"), ConsoleViewContentType.SYSTEM_OUTPUT);
        EmbeddedLinuxJVMRunConfigurationRunnerParameters runnerParameters = configuration.getRunnerParameters();

        DeploymentTarget target = DeploymentTarget.builder()
                .sshHandlerTarget(SSHHandlerTarget.builder()
                        .piRunnerParameters(runnerParameters)
                        .consoleView(EmbeddedLinuxJVMConsoleView.getInstance(getEnvironment().getProject()))
                        .ssh(SSH.builder()
                                .connectionTimeout(30000)
                                .timeout(30000)
                                .build()).build()).build();
        target.upload(new File(projectOutput), commandLineTarget.toString());
    }

    /**
     * Creates debugging settings for server
     *
     * @param project
     * @param debugPort
     * @param hostname
     * @return
     */
    private RunnerAndConfigurationSettings createRunConfiguration(Project project, String debugPort, String hostname) {
        final RemoteConfigurationType remoteConfigurationType = RemoteConfigurationType.getInstance();

        final ConfigurationFactory factory = remoteConfigurationType.getFactory();
        final RunnerAndConfigurationSettings runSettings =
                RunManager.getInstance(project).createRunConfiguration(getRunConfigurationName(debugPort), factory);
        final RemoteConfiguration configuration = (RemoteConfiguration) runSettings.getConfiguration();

        configuration.HOST = hostname;
        configuration.PORT = debugPort;
        configuration.USE_SOCKET_TRANSPORT = true;
        configuration.SERVER_MODE = false;

        return runSettings;
    }


    /**
     * Closes old session only
     *
     * @param project
     * @param parameters
     */
    private void closeOldSession(final Project project, EmbeddedLinuxJVMRunConfigurationRunnerParameters parameters) {
        final String configurationName = getRunConfigurationName(parameters.getPort());
        final Collection<RunContentDescriptor> descriptors =
                ExecutionHelper.findRunningConsoleByTitle(project, new NotNullFunction<String, Boolean>() {
                    @NotNull
                    @Override
                    public Boolean fun(String title) {
                        return configurationName.equals(title);
                    }
                });

        if (descriptors.size() > 0) {
            final RunContentDescriptor descriptor = descriptors.iterator().next();
            final ProcessHandler processHandler = descriptor.getProcessHandler();
            final Content content = descriptor.getAttachedContent();

            if (processHandler != null && content != null) {
                final Executor executor = DefaultDebugExecutor.getDebugExecutorInstance();

                if (processHandler.isProcessTerminated()) {
                    ExecutionManager.getInstance(project).getContentManager()
                            .removeRunContent(executor, descriptor);
                } else {
                    content.getManager().setSelectedContent(content);
                    ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(executor.getToolWindowId());
                    window.activate(null, false, true);
                    return;
                }
            }
        }
    }
    /**
     * Closes an old descriptor and creates a new one in debug mode connecting to remote target
     *
     * @param project
     * @param parameters
     */
    private void closeOldSessionAndDebug(final Project project, EmbeddedLinuxJVMRunConfigurationRunnerParameters parameters) {
        closeOldSession(project, parameters);
        runSession(project, parameters);
    }

    /**
     * Runs in remote debug mode using that executioner
     *
     * @param project
     * @param parameters
     */
    private void runSession(final Project project, EmbeddedLinuxJVMRunConfigurationRunnerParameters parameters) {
        final RunnerAndConfigurationSettings settings = createRunConfiguration(project, parameters.getPort(), parameters.getHostname());
        ProgramRunnerUtil.executeConfiguration(project, settings, DefaultDebugExecutor.getDebugExecutorInstance());
    }


}