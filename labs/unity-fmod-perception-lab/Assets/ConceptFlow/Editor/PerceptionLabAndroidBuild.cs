// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.IO;
using System.Linq;
using UnityEditor;
using UnityEditor.Build.Reporting;

namespace ConceptFlow.Mpl.PerceptionLab.Editor
{
    /** Deterministic Android development build entry point for headless validation. */
    public static class PerceptionLabAndroidBuild
    {
        public static void Build()
        {
            string projectRoot=Directory.GetParent(UnityEngine.Application.dataPath)?.FullName
                ?? throw new InvalidOperationException("Unable to resolve Unity project root.");
            string output=Environment.GetEnvironmentVariable("MPL_UNITY_ANDROID_APK");
            if(string.IsNullOrWhiteSpace(output))
                output=Path.Combine(projectRoot,"Builds","Android","MachinePerceptionLab-Development.apk");
            output=Path.GetFullPath(output);
            Directory.CreateDirectory(Path.GetDirectoryName(output)
                ?? throw new InvalidOperationException("Android output has no parent directory."));

            string[] scenes=EditorBuildSettings.scenes.Where(value=>value.enabled)
                .Select(value=>value.path).ToArray();
            if(scenes.Length==0) throw new InvalidOperationException("No enabled Android scenes.");

            PlayerSettings.companyName="CONCEPTFlow";
            PlayerSettings.productName="Machine Perception Layer";
            PlayerSettings.SetApplicationIdentifier(
                BuildTargetGroup.Android,"org.conceptflow.mpl.unitylab");
            PlayerSettings.Android.minSdkVersion=AndroidSdkVersions.AndroidApiLevel29;
            PlayerSettings.Android.targetArchitectures=AndroidArchitecture.ARM64;
            PlayerSettings.SetScriptingBackend(
                BuildTargetGroup.Android,ScriptingImplementation.IL2CPP);

            BuildReport report=BuildPipeline.BuildPlayer(new BuildPlayerOptions
            {
                scenes=scenes,
                locationPathName=output,
                target=BuildTarget.Android,
                targetGroup=BuildTargetGroup.Android,
                options=BuildOptions.Development,
            });
            if(report.summary.result!=BuildResult.Succeeded)
                throw new InvalidOperationException("Android development build failed: "+report.summary.result);
            UnityEngine.Debug.Log(
                $"[MPL_UNITY_ANDROID_BUILD] status=Pass bytes={report.summary.totalSize} " +
                $"durationMs={report.summary.totalTime.TotalMilliseconds:F0}");
        }
    }
}
