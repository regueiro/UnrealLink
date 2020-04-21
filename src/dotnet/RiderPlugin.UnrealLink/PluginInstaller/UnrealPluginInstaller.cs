﻿using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using JetBrains.Application.Settings;
using JetBrains.Application.Threading;
using JetBrains.Collections.Viewable;
using JetBrains.DataFlow;
using JetBrains.Diagnostics;
using JetBrains.Lifetimes;
using JetBrains.ProjectModel;
using JetBrains.ProjectModel.DataContext;
using JetBrains.ReSharper.Feature.Services.Cpp.UE4;
using JetBrains.Rider.Model.Notifications;
using JetBrains.Util;
using JetBrains.Util.Interop;
using Newtonsoft.Json.Linq;
using RiderPlugin.UnrealLink.Settings;

namespace RiderPlugin.UnrealLink.PluginInstaller
{
    [SolutionComponent]
    public class UnrealPluginInstaller
    {
        private readonly Lifetime myLifetime;
        private readonly ILogger myLogger;
        private readonly PluginPathsProvider myPathsProvider;
        private readonly ISolution mySolution;
        private readonly IShellLocks myShellLocks;
        private readonly UnrealHost myUnrealHost;
        private readonly NotificationsModel myNotificationsModel;
        private IContextBoundSettingsStoreLive myBoundSettingsStore;
        private UnrealPluginDetector myPluginDetector;
        private const string TMP_PREFIX = "UnrealLink_";

        public UnrealPluginInstaller(Lifetime lifetime, ILogger logger, UnrealPluginDetector pluginDetector,
            PluginPathsProvider pathsProvider, ISolution solution, ISettingsStore settingsStore,
            IShellLocks shellLocks, UnrealHost unrealHost, NotificationsModel notificationsModel)
        {
            myLifetime = lifetime;
            myLogger = logger;
            myPathsProvider = pathsProvider;
            mySolution = solution;
            myShellLocks = shellLocks;
            myUnrealHost = unrealHost;
            myNotificationsModel = notificationsModel;
            myBoundSettingsStore =
                settingsStore.BindToContextLive(myLifetime, ContextRange.Smart(solution.ToDataContext()));
            myPluginDetector = pluginDetector;

            myPluginDetector.InstallInfoProperty.Change.Advise_NewNotNull(myLifetime, installInfo =>
            {
                myShellLocks.ExecuteOrQueueReadLockEx(myLifetime,
                    "UnrealPluginInstaller.CheckAllProjectsIfAutoInstallEnabled",
                    () =>
                    {
                        var unrealPluginInstallInfo = installInfo.New;
                        if (unrealPluginInstallInfo.EnginePlugin.IsPluginAvailable)
                        {
                            // TODO: add install plugin to Engine
                            myLogger.Info("Plugin is already installed in Engine");
                            return;
                        }

                        if (!myBoundSettingsStore.GetValue((UnrealLinkSettings s) => s.InstallRiderLinkPlugin))
                        {
                            foreach (var installDescription in unrealPluginInstallInfo.ProjectPlugins)
                            {
                                if (installDescription.IsPluginAvailable == false ||
                                    installDescription.PluginVersion != myPathsProvider.CurrentPluginVersion)
                                {
                                    myUnrealHost.PerformModelAction(model => model.OnEditorModelOutOfSync());
                                }
                            }

                            return;
                        }

                        InstallPluginIfRequired(unrealPluginInstallInfo);
                    });
            });
            BindToInstallationSettingChange();
            BindToNotificationFixAction();
        }

        private void InstallPluginIfRequired(UnrealPluginInstallInfo unrealPluginInstallInfo)
        {
            bool needToRegenerateProjectFiles = false;

            foreach (var installDescription in unrealPluginInstallInfo.ProjectPlugins)
            {
                if (installDescription.PluginVersion == myPathsProvider.CurrentPluginVersion) continue;

                var pluginDir = installDescription.UnrealPluginRootFolder;
                var backupDir = FileSystemDefinition.CreateTemporaryDirectory(null, TMP_PREFIX);
                try
                {
                    if (pluginDir.ExistsDirectory)
                        pluginDir.Move(backupDir);
                }
                catch (Exception exception)
                {
                    myLogger.Error(exception, ExceptionOrigin.Algorithmic,
                        "Couldn't backup original RiderLink plugin folder");
                    backupDir.Delete();
                    continue;
                }

                var editorPluginPathFile = myPathsProvider.PathToPackedPlugin;
                var pluginTmpDir = FileSystemDefinition.CreateTemporaryDirectory(null, TMP_PREFIX);
                try
                {
                    ZipFile.ExtractToDirectory(editorPluginPathFile.FullPath,
                        pluginTmpDir.FullPath);
                }
                catch (Exception exception)
                {
                    myLogger.Error(exception, ExceptionOrigin.Algorithmic,
                        $"Couldn't extract {editorPluginPathFile} to {pluginTmpDir}");
                    if (backupDir.ExistsDirectory)
                        backupDir.Move(pluginDir);
                    pluginTmpDir.Delete();
                    backupDir.Delete();
                    continue;
                }

                var upluginFile = UnrealPluginDetector.GetPathToUpluginFile(pluginTmpDir);
                if (!PatchTypeOfUpluginFile(upluginFile, myLogger, myPluginDetector.UnrealVersion))
                {
                    if (backupDir.ExistsDirectory)
                        backupDir.Move(pluginDir);
                    backupDir.Delete();
                    pluginTmpDir.Delete();
                    continue;
                }

                // TODO: On UE 4.20 (at least) building plugin from cmd is broken.
                // if (!BuildPlugin(upluginFile,
                //     pluginDir.Directory,
                //     installDescription.UprojectFilePath))
                // {
                //     myLogger.Warn($"Failed to build RiderLink for {installDescription.UprojectFilePath.NameWithoutExtension}. Copying source files instead");
                //     pluginTmpDir.Move(pluginDir);
                // }
                
                pluginTmpDir.Move(pluginDir);

                backupDir.Delete();
                pluginTmpDir.Delete();

                needToRegenerateProjectFiles = true;
            }

            if (needToRegenerateProjectFiles)
                RegenerateProjectFiles(unrealPluginInstallInfo.ProjectPlugins.FirstNotNull()?.UprojectFilePath);
        }

        private static bool PatchTypeOfUpluginFile(FileSystemPath upluginFile, ILogger logger, Version pluginVersion)
        {
            var jsonText = File.ReadAllText(upluginFile.FullPath);
            try
            {
                var jsonObject = Newtonsoft.Json.JsonConvert.DeserializeObject(jsonText) as JObject;
                var modules = jsonObject["Modules"];
                var pluginType = pluginVersion.Minor >= 24 ? "UncookedOnly" : "Developer";
                if (modules is JArray array)
                {
                    foreach (var item in array)
                    {
                        item["Type"].Replace(pluginType);
                    }
                }

                File.WriteAllText(upluginFile.FullPath, jsonObject.ToString());
            }
            catch (Exception e)
            {
                logger.Error($"Couldn't patch 'Type' field of {upluginFile}", e);
                return false;
            }

            return true;
        }

        private void BindToInstallationSettingChange()
        {
            var entry = myBoundSettingsStore.Schema.GetScalarEntry((UnrealLinkSettings s) => s.InstallRiderLinkPlugin);
            myBoundSettingsStore.GetValueProperty<bool>(myLifetime, entry, null).Change.Advise_When(myLifetime,
                newValue => newValue, args =>
                {
                    myShellLocks.ExecuteOrQueueReadLockEx(myLifetime,
                        "UnrealPluginInstaller.CheckAllProjectsIfAutoInstallEnabled",
                        InstallPluginIfInfoAvailable);
                });
        }

        private void InstallPluginIfInfoAvailable()
        {
            var unrealPluginInstallInfo = myPluginDetector.InstallInfoProperty.Value;
            if (unrealPluginInstallInfo != null)
            {
                InstallPluginIfRequired(unrealPluginInstallInfo);
            }
        }

        private void BindToNotificationFixAction()
        {
            myUnrealHost.PerformModelAction(model =>
            {
                model.InstallEditorPlugin.AdviseNotNull(myLifetime, unit =>
                {
                    myShellLocks.ExecuteOrQueueReadLockEx(myLifetime,
                        "UnrealPluginInstaller.CheckAllProjectsIfAutoInstallEnabled",
                        InstallPluginIfInfoAvailable);
                });
            });
        }

        private void RegenerateProjectFiles(FileSystemPath uprojectFilePath)
        {
            if (uprojectFilePath.IsNullOrEmpty())
            {
                myLogger.Error($"Failed refresh project files, couldn't find uproject path: {uprojectFilePath}");
                return;
            }

            var engineRoot = UnrealEngineFolderFinder.FindUnrealEngineRoot(uprojectFilePath);
            var pathToUnrealBuildToolBin = UnrealEngineFolderFinder.GetAbsolutePathToUnrealBuildToolBin(engineRoot);

            // 1. If project is under engine root, use GenerateProjectFiles.bat first
            if (GenerateProjectFilesUsingBat(engineRoot)) return;
            // 2. If it's a standalone project, use UnrealVersionSelector
            //    The same way "Generate project files" from context menu of .uproject works
            if (RegenerateProjectUsingUVS(uprojectFilePath, engineRoot)) return;
            // 3. If UVS is missing or have failed, fallback to UnrealBuildTool
            if (RegenerateProjectUsingUBT(uprojectFilePath, pathToUnrealBuildToolBin, engineRoot)) return;

            myLogger.Error("Couldn't refresh project files");
            var notification = new NotificationModel($"Failed to refresh project files",
                "<html>RiderLink has been successfully installed to the project:<br>" +
                $"<b>{uprojectFilePath.NameWithoutExtension}<b>" +
                "but refresh project action has failed.<br>" +
                "</html>", true, RdNotificationEntryType.WARN);

            myShellLocks.ExecuteOrQueue(myLifetime, "UnrealLink.RefreshProject",
                () => { myNotificationsModel.Notification(notification); });
        }

        private bool GenerateProjectFilesUsingBat(FileSystemPath engineRoot)
        {
            var isProjectUnderEngine = mySolution.SolutionFilePath.Directory == engineRoot;
            if (!isProjectUnderEngine) return false;

            var generateProjectFilesBat = engineRoot / "GenerateProjectFiles.bat";
            if (!generateProjectFilesBat.ExistsFile) return false;

            try
            {
                ErrorLevelException.ThrowIfNonZero(InvokeChildProcess.InvokeChildProcessIntoLogger(
                    generateProjectFilesBat,
                    null,
                    LoggingLevel.INFO,
                    TimeSpan.FromMinutes(1),
                    InvokeChildProcess.TreatStderr.AsOutput,
                    generateProjectFilesBat.Directory
                ));
            }
            catch (ErrorLevelException errorLevelException)
            {
                myLogger.Error(errorLevelException,
                    $"Failed refresh project files, calling {generateProjectFilesBat} went wrong");
                return false;
            }

            return true;
        }

        private bool RegenerateProjectUsingUVS(FileSystemPath uprojectFilePath, FileSystemPath engineRoot)
        {
            var pathToUnrealVersionSelector =
                engineRoot / "Engine" / "Binaries" / "Win64" / "UnrealVersionSelector.exe";
            if (!pathToUnrealVersionSelector.ExistsFile) return false;

            var commandLine = new CommandLineBuilderJet()
                .AppendSwitch("/projectFiles")
                .AppendFileName(uprojectFilePath);

            try
            {
                ErrorLevelException.ThrowIfNonZero(InvokeChildProcess.InvokeChildProcessIntoLogger(
                    pathToUnrealVersionSelector,
                    commandLine,
                    LoggingLevel.INFO,
                    TimeSpan.FromMinutes(1),
                    InvokeChildProcess.TreatStderr.AsOutput,
                    pathToUnrealVersionSelector.Directory
                ));
            }
            catch (ErrorLevelException errorLevelException)
            {
                myLogger.Error(errorLevelException, "Failed refresh project files, calling UVS went wrong");
                return false;
            }

            return true;
        }

        private bool RegenerateProjectUsingUBT(FileSystemPath uprojectFilePath, FileSystemPath pathToUnrealBuildToolBin,
            FileSystemPath engineRoot)
        {
            bool isInstalledBuild = IsInstalledBuild(engineRoot);

            var commandLine = new CommandLineBuilderJet()
                .AppendSwitch("-ProjectFiles")
                .AppendFileName(uprojectFilePath)
                .AppendSwitch("-game")
                .AppendSwitch("-engine");
            if (isInstalledBuild)
                commandLine.AppendSwitch("-rocket");

            try
            {
                ErrorLevelException.ThrowIfNonZero(InvokeChildProcess.InvokeChildProcessIntoLogger(
                    pathToUnrealBuildToolBin,
                    commandLine,
                    LoggingLevel.INFO,
                    TimeSpan.FromMinutes(1),
                    InvokeChildProcess.TreatStderr.AsOutput,
                    pathToUnrealBuildToolBin.Directory
                ));
            }
            catch (ErrorLevelException errorLevelException)
            {
                myLogger.Error(errorLevelException, "Failed refresh project files, calling UBT went wrong");
                return false;
            }

            return true;
        }

        private static bool IsInstalledBuild(FileSystemPath engineRoot)
        {
            var installedbuildTxt = engineRoot / "Engine" / "Build" / "InstalledBuild.txt";
            var isInstalledBuild = installedbuildTxt.ExistsFile;
            return isInstalledBuild;
        }

        private bool BuildPlugin(FileSystemPath upluginPath, FileSystemPath outputDir, FileSystemPath uprojectFile)
        {
            //engineRoot\Engine\Build\BatchFiles\RunUAT.bat" BuildPlugin -Plugin="D:\tmp\RiderLink\RiderLink.uplugin" -Package="D:\PROJECTS\UE\FPS_D_TEST\Plugins\Developer\RiderLink" -Rocket
            var engineRoot = UnrealEngineFolderFinder.FindUnrealEngineRoot(uprojectFile);
            var isInstalledBuild = IsInstalledBuild(engineRoot);
            var commandLine = new CommandLineBuilderJet()
                .AppendSwitch("BuildPlugin")
                .AppendSwitch($"-Plugin=\"{upluginPath.FullPath}\"")
                .AppendSwitch($"-Package=\"{outputDir.FullPath}\"");
            if (isInstalledBuild)
                commandLine.AppendSwitch("-Rocket");

            var pathToUat = engineRoot / "Engine" / "Build" / "BatchFiles" / "RunUAT.bat";
            if (!pathToUat.ExistsFile)
            {
                myLogger.Error("Failed build plugin: RunUAT.bat is not available");
                return false;
            }

            try
            {
                var processStartInfo = new ProcessStartInfo()
                {
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    FileName = pathToUat.FullPath,
                    Arguments = commandLine.ToString()
                };
                var process = new Process
                {
                    EnableRaisingEvents = true,
                    StartInfo = processStartInfo,
                    
                };
                process.Start();
                process.WaitForExit(1000*60);
                if (process.ExitCode != 0)
                {
                    myLogger.Error("Failed build plugin: calling RunUAT.bat went wrong");
                    return false;
                }
            }
            catch (Exception exception)
            {
                myLogger.Error(exception, "Failed build plugin: calling RunUAT.bat went wrong");
                return false;
            }

            return true;
        }
    }
}