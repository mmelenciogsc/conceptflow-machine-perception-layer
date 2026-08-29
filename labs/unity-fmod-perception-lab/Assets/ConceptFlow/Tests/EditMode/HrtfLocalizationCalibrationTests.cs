// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.IO;
using NUnit.Framework;
using UnityEngine;

namespace ConceptFlow.Mpl.PerceptionLab.Tests
{
    public sealed class HrtfLocalizationCalibrationTests
    {
        private string temporaryDirectory;

        [SetUp]
        public void SetUp()
        {
            temporaryDirectory=Path.Combine(Path.GetTempPath(),"conceptflow-hrtf-"+Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(temporaryDirectory);
        }

        [TearDown]
        public void TearDown()
        {
            if(Directory.Exists(temporaryDirectory)) Directory.Delete(temporaryDirectory,true);
        }

        [Test]
        public void FixedManifestPresentsThreeNeutralTwoMetrePulsesAndRecordsText()
        {
            var backend=new InspectableFmodBackend();
            var calibration=Calibration(backend);
            calibration.AcceptListenerPose(Pose(100_000_000L));
            Assert.IsTrue(calibration.Start("session-1",200_000_000L));
            Assert.IsTrue(calibration.Next(200_000_000L));

            calibration.Tick(200_000_000L);
            Assert.AreEqual(1,backend.ActiveFocusedIconCount);
            FocusedIconCommand first=backend.LastFocusedIconCommand.Value;
            Assert.AreEqual(AuditoryIconRegistry.FocusedObjectEvent,first.EventPath);
            Assert.AreEqual("procedural/neutral_presence",first.AssetKey);
            Assert.That(Vector3.Distance(Vector3.zero,first.Position),Is.EqualTo(2f).Within(.0001f));
            Assert.That(first.Position.y,Is.EqualTo(2f).Within(.0001f));
            Assert.AreEqual(0f,Parameter(first,"IconConcept"));
            Assert.AreEqual(2f,Parameter(first,"DistanceMeters"));

            AdvancePulse(calibration,420_000_000L,700_000_000L);
            AdvancePulse(calibration,920_000_000L,1_200_000_000L);
            calibration.AcceptListenerPose(Pose(1_400_000_000L));
            calibration.Tick(1_420_000_000L);
            Assert.AreEqual(HrtfCalibrationState.AwaitingResponse,calibration.State);
            Assert.AreEqual(3,backend.FocusedCommandCount);
            Assert.AreEqual(0,backend.ActiveFocusedIconCount);
            Assert.IsTrue(calibration.Respond("above",1_500_000_000L));
            Assert.AreEqual(HrtfCalibrationState.ReadyForTrial,calibration.State);
            string response=File.ReadAllText(calibration.ResultPath);
            StringAssert.Contains("\"schema\":\"conceptflow.hrtf-localization-response/v1\"",response);
            StringAssert.Contains("\"trial_id\":\"hrtf-01\"",response);
            StringAssert.Contains("\"target_direction\":\"above\"",response);
            StringAssert.DoesNotContain("audio_data",response.ToLowerInvariant());
            StringAssert.DoesNotContain("image",response.ToLowerInvariant());
        }

        [Test]
        public void PresentationFailsClosedOnStalePoseAndRejectsInvalidTransitions()
        {
            var backend=new InspectableFmodBackend();
            var calibration=Calibration(backend);
            Assert.IsFalse(calibration.Start("../escape",1L));
            Assert.IsTrue(calibration.Start("session-2",100L));
            Assert.IsFalse(calibration.Respond("front",101L));
            calibration.AcceptListenerPose(Pose(100L));
            Assert.IsTrue(calibration.Next(100L));
            calibration.Tick(100L);
            calibration.Tick(HrtfLocalizationCalibration.MaximumPoseAgeNs+101L);
            Assert.AreEqual(HrtfCalibrationState.Aborted,calibration.State);
            Assert.AreEqual(0,backend.ActiveFocusedIconCount);
            Assert.AreEqual("listener-pose-unavailable-or-stale",calibration.LastError);
        }

        [Test]
        public void TrialRequiresCompatibleRouteAndAbortsWhenRouteIsLost()
        {
            bool routeReady=false;
            var calibration=Calibration(new InspectableFmodBackend(),()=>routeReady);
            Assert.IsTrue(calibration.Start("route-session",100L));
            calibration.AcceptListenerPose(Pose(100L));
            Assert.IsFalse(calibration.Next(100L));
            Assert.AreEqual("hrtf-audio-route-unavailable",calibration.LastError);

            routeReady=true;
            Assert.IsTrue(calibration.Next(101L));
            calibration.AcceptListenerPose(Pose(101L));
            calibration.Tick(101L);
            routeReady=false;
            calibration.Tick(102L);
            Assert.AreEqual(HrtfCalibrationState.Aborted,calibration.State);
            Assert.AreEqual("hrtf-audio-route-lost",calibration.LastError);
        }

        [Test]
        public void ActiveRouteClassificationRejectsConnectedOnlyAndOldApiAssumptions()
        {
            Assert.IsFalse(HrtfAudioRouteReadiness.HasCompatibleActiveRoute(32,new[] { 8 }));
            Assert.IsFalse(HrtfAudioRouteReadiness.HasCompatibleActiveRoute(36,new[] { 2 }));
            Assert.IsTrue(HrtfAudioRouteReadiness.HasCompatibleActiveRoute(36,new[] { 8 }));
            Assert.IsTrue(HrtfAudioRouteReadiness.HasCompatibleActiveRoute(36,new[] { 22 }));
        }

        [Test]
        public void TrialDeadlineIsHardAcrossPresentationAndResponse()
        {
            var calibration=Calibration(new InspectableFmodBackend());
            const long startedAtNs=1_000_000_000L;
            Assert.IsTrue(calibration.Start("deadline-session",startedAtNs));
            calibration.AcceptListenerPose(Pose(startedAtNs));
            Assert.IsTrue(calibration.Next(startedAtNs));
            calibration.Tick(startedAtNs);
            AdvancePulse(calibration,startedAtNs+220_000_000L,startedAtNs+500_000_000L);
            AdvancePulse(calibration,startedAtNs+720_000_000L,startedAtNs+1_000_000_000L);
            calibration.AcceptListenerPose(Pose(startedAtNs+1_220_000_000L));
            calibration.Tick(startedAtNs+1_220_000_000L);
            Assert.AreEqual(HrtfCalibrationState.AwaitingResponse,calibration.State);
            calibration.Tick(startedAtNs+HrtfLocalizationCalibration.MaximumTrialDurationNs);
            Assert.AreEqual(HrtfCalibrationState.Aborted,calibration.State);
            Assert.AreEqual("trial-deadline-exceeded",calibration.LastError);
        }

        [Test]
        public void FreshAmbientProfileRaisesCalibrationOnlyAndControlsPulseSpacing()
        {
            var backend=new InspectableFmodBackend();
            var calibration=Calibration(backend);
            const long startedAtNs=1_000_000_000L;
            calibration.AcceptAmbientSoundProfile(new PerceptionAmbientSoundProfileSnapshot
            {
                Revision=1,SessionGeneration=2,Prior=AmbientEnvironmentPrior.Outdoor,
                CaptureStartTimestampNs=100, CaptureEndTimestampNs=900_000_000L,
                ValidUntilTimestampNs=10_000_000_000L, SampleRateHz=16_000,
                ChannelCount=1,SampleCount=48_000,RmsDbFs=-32f,PeakDbFs=-12f,
                NoiseFloorDbFs=-41f,LowBandRatio=.4f,MidBandRatio=.4f,HighBandRatio=.2f,
                TransientDensity=.1f,RecommendedCalibrationGain=.86f,
                RecommendedPulseIntervalMs=700,
            },startedAtNs);
            calibration.AcceptListenerPose(Pose(startedAtNs));
            Assert.IsTrue(calibration.Start("ambient-session",startedAtNs));
            Assert.IsTrue(calibration.Next(startedAtNs));
            calibration.Tick(startedAtNs);
            Assert.That(backend.LastFocusedIconCommand.Value.Gain,Is.EqualTo(.86f).Within(.0001f));
            Assert.AreEqual(1f,Parameter(backend.LastFocusedIconCommand.Value,"IconSalience"));

            calibration.AcceptListenerPose(Pose(startedAtNs+220_000_000L));
            calibration.Tick(startedAtNs+220_000_000L);
            calibration.AcceptListenerPose(Pose(startedAtNs+500_000_000L));
            calibration.Tick(startedAtNs+500_000_000L);
            Assert.AreEqual(1,backend.FocusedCommandCount);
            calibration.AcceptListenerPose(Pose(startedAtNs+700_000_000L));
            calibration.Tick(startedAtNs+700_000_000L);
            Assert.AreEqual(2,backend.FocusedCommandCount);
        }

        [Test]
        public void BackendDispatchFailureAbortsWithoutEscapingTick()
        {
            var calibration=Calibration(new ThrowingAudioBackend());
            Assert.IsTrue(calibration.Start("dispatch-session",100L));
            calibration.AcceptListenerPose(Pose(100L));
            Assert.IsTrue(calibration.Next(100L));
            Assert.DoesNotThrow(()=>calibration.Tick(100L));
            Assert.AreEqual(HrtfCalibrationState.Aborted,calibration.State);
            Assert.AreEqual("audio-dispatch-failed",calibration.LastError);
        }

        [Test]
        public void CommandSpoolConsumesStrictIncreasingNoncesWithoutAnExportedReceiver()
        {
            var backend=new InspectableFmodBackend();
            var calibration=Calibration(backend);
            var spool=new HrtfCalibrationCommandSpool(temporaryDirectory);
            WriteCommand("v1\t1\tstart\tspool-session\n");
            Assert.IsTrue(spool.ProcessOnce(calibration,100L));
            Assert.AreEqual(HrtfCalibrationState.ReadyForTrial,calibration.State);
            Assert.AreEqual(1L,spool.LastNonce);

            WriteCommand("v1\t1\tabort\n");
            Assert.IsTrue(spool.ProcessOnce(calibration,101L));
            Assert.AreEqual(HrtfCalibrationState.ReadyForTrial,calibration.State);
            StringAssert.Contains("malformed-or-replayed-command",File.ReadAllText(spool.StatusPath));

            calibration.AcceptListenerPose(Pose(100L));
            WriteCommand("v1\t2\tnext\n");
            Assert.IsTrue(spool.ProcessOnce(calibration,150L));
            Assert.AreEqual(HrtfCalibrationState.Presenting,calibration.State);
            string status=File.ReadAllText(spool.StatusPath);
            StringAssert.Contains("\"current_trial_id\":\"hrtf-01\"",status);
            StringAssert.DoesNotContain("above",status);
            StringAssert.DoesNotContain("\"target_",status);
            Assert.IsFalse(File.Exists(Path.Combine(temporaryDirectory,HrtfCalibrationCommandSpool.CommandFileName)));

            WriteCommand("v1\t3\tabort\n");
            Assert.IsTrue(spool.ProcessOnce(calibration,151L));
            Assert.AreEqual(HrtfCalibrationState.Aborted,calibration.State);
            StringAssert.Contains("\"error\":\"\"",File.ReadAllText(spool.StatusPath));
        }

        [Test]
        public void InspectableFallbackCannotStartAResonanceLabelledSession()
        {
            var calibration=new HrtfLocalizationCalibration(
                new InspectableFmodBackend(),ManifestText(),temporaryDirectory,()=>true);
            Assert.IsFalse(calibration.Start("fallback-session",100L));
            Assert.AreEqual("resonance-audio-unavailable",calibration.LastError);
            Assert.IsFalse(File.Exists(Path.Combine(temporaryDirectory,"fallback-session.responses.ndjson")));
        }

        [Test]
        public void SpoolStorageFailureDisablesOnceAndAbortsActiveAudio()
        {
            var backend=new InspectableFmodBackend();
            var calibration=Calibration(backend);
            var spool=new HrtfCalibrationCommandSpool(temporaryDirectory);
            calibration.AcceptListenerPose(Pose(100L));
            Assert.IsTrue(calibration.Start("storage-session",100L));
            Assert.IsTrue(calibration.Next(100L));
            calibration.Tick(100L);
            File.Delete(spool.StatusPath);
            Directory.CreateDirectory(spool.StatusPath);

            Assert.DoesNotThrow(()=>spool.RefreshStatus(calibration,true));
            Assert.IsFalse(spool.IsOperational);
            Assert.AreEqual(HrtfCalibrationState.Aborted,calibration.State);
            Assert.AreEqual("spool-io-failed",calibration.LastError);
            Assert.AreEqual(0,backend.ActiveFocusedIconCount);
            Assert.DoesNotThrow(()=>spool.RefreshStatus(calibration,true));
        }

        [Test]
        public void ManifestValidationRejectsProfileOrShapeDrift()
        {
            string manifest=ManifestText().Replace("resonance_audio","fmod_standard");
            Assert.Throws<ArgumentException>(() =>
                new HrtfLocalizationCalibration(new InspectableFmodBackend(),manifest,temporaryDirectory));
        }

        [Test]
        public void StorageUsesAndroidInternalFilesDirectoryOnlyOnAndroid()
        {
            string persistent=Path.Combine(temporaryDirectory,"persistent");
            string internalFiles=Path.Combine(temporaryDirectory,"internal-files");
            bool resolverCalled=false;
            string android=HrtfCalibrationStorage.ResolveForPlatform(persistent,true,()=>
            {
                resolverCalled=true;
                return internalFiles;
            });
            Assert.IsTrue(resolverCalled);
            Assert.AreEqual(Path.Combine(internalFiles,"hrtf-calibration"),android);

            resolverCalled=false;
            string desktop=HrtfCalibrationStorage.ResolveForPlatform(persistent,false,()=>
            {
                resolverCalled=true;
                return internalFiles;
            });
            Assert.IsFalse(resolverCalled);
            Assert.AreEqual(Path.Combine(persistent,"hrtf-calibration"),desktop);
            Assert.Throws<InvalidOperationException>(()=>
                HrtfCalibrationStorage.ResolveForPlatform(persistent,true,()=>"relative/path"));
        }

        private HrtfLocalizationCalibration Calibration(IPerceptionAudioBackend backend,
            Func<bool> isAudioRouteReady=null) =>
            new(new AttestedAudioBackend(backend),ManifestText(),temporaryDirectory,isAudioRouteReady);

        private static string ManifestText()
        {
            TextAsset manifest=Resources.Load<TextAsset>("focused_hrtf_trials");
            Assert.IsNotNull(manifest);
            return manifest.text;
        }

        private static ListenerPoseCommand Pose(long timestampNs) =>
            new(Vector3.zero,Vector3.forward,Vector3.up,timestampNs);

        private static float Parameter(FocusedIconCommand command,string name)
        {
            foreach(AudioParameter parameter in command.Parameters)
                if(parameter.Name==name) return parameter.Value;
            Assert.Fail("Missing parameter: "+name);
            return 0f;
        }

        private static void AdvancePulse(HrtfLocalizationCalibration calibration,long stopNs,long startNs)
        {
            calibration.AcceptListenerPose(Pose(stopNs));
            calibration.Tick(stopNs);
            calibration.AcceptListenerPose(Pose(startNs));
            calibration.Tick(startNs);
        }

        private void WriteCommand(string command) =>
            File.WriteAllText(Path.Combine(temporaryDirectory,HrtfCalibrationCommandSpool.CommandFileName),command);

        private sealed class ThrowingAudioBackend : IPerceptionAudioBackend
        {
            public string Dispatch(SpatialAudioCommand command) => string.Empty;
            public string SetListenerPose(ListenerPoseCommand command) => string.Empty;
            public string UpsertFocusedIcon(FocusedIconCommand command) =>
                throw new InvalidOperationException("synthetic dispatch failure");
            public string StopFocusedIcon(string reason) => string.Empty;
            public string SetDwellSpeech(long generation,bool active,float duckGain) => string.Empty;
            public string EmitInterfaceState(InterfaceAudioCommand command) => string.Empty;
        }

        private sealed class AttestedAudioBackend : IPerceptionAudioBackend,IHrtfAudioProfileCapability
        {
            private readonly IPerceptionAudioBackend inner;
            public AttestedAudioBackend(IPerceptionAudioBackend inner) => this.inner=inner;
            public bool IsHrtfProfileReady(string requiredProfile) =>
                requiredProfile==HrtfLocalizationCalibration.Profile;
            public string Dispatch(SpatialAudioCommand command) => inner.Dispatch(command);
            public string SetListenerPose(ListenerPoseCommand command) => inner.SetListenerPose(command);
            public string UpsertFocusedIcon(FocusedIconCommand command) => inner.UpsertFocusedIcon(command);
            public string StopFocusedIcon(string reason) => inner.StopFocusedIcon(reason);
            public string SetDwellSpeech(long generation,bool active,float duckGain) =>
                inner.SetDwellSpeech(generation,active,duckGain);
            public string EmitInterfaceState(InterfaceAudioCommand command) => inner.EmitInterfaceState(command);
        }
    }
}
