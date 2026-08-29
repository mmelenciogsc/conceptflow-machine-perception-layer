// SPDX-License-Identifier: MIT OR Apache-2.0
using System.Collections;
using System;
using System.IO;
using NUnit.Framework;
using UnityEngine;
using UnityEngine.TestTools;
using Object = UnityEngine.Object;

namespace ConceptFlow.Mpl.PerceptionLab.Tests
{
    public sealed class PerceptionLabPlayModeTests
    {
        [UnityTest]
        public IEnumerator BroadWallScenarioProducesTextualMetricState()
        {
            var root=new GameObject("Perception lab test root");
            var controller=root.AddComponent<PerceptionLabController>();
            controller.BuildScenario(LabScenario.BroadWall);
            yield return null;
            StringAssert.Contains("BroadWall",controller.Status);
            StringAssert.Contains("clearance",controller.Status);
            StringAssert.Contains("proximity",controller.Status);
            Assert.Greater(controller.LastAudioCommandCount,0,
                "A synchronized scenario collider must produce at least the intrusion anchor command.");
            Assert.Greater(controller.LastBroadphaseCandidateCount,0,
                "Editor PlayMode should exercise the primary physics broadphase path.");
            Object.Destroy(root);
        }

        [UnityTest]
        public IEnumerator InjectedSnapshotsRenderWithoutControllerOwningSelection()
        {
            var root=new GameObject("Injected perception test root");
            var controller=root.AddComponent<PerceptionLabController>();
            controller.ConfigurePerceptionRuntime(null,new InspectableFmodBackend(),SyntheticAdapter());
            var world=new PerceptionWorldSnapshot
            {
                Revision=2,SessionGeneration=4,PublishedTimestampNs=100,
                ValidUntilTimestampNs=1_000,Validity=PerceptionValidity.PerceptionReady,
            };
            world.Entities.Add(new PerceptionEntitySnapshot
            {
                TrackId="person-1",ClassId="person",Frame=PerceptionFrame.World,HasPosition=true,
                SourceFrameId=1,SourceCaptureTimestampNs=100,OutputTimestampNs=100,
                Confidence=.9f,DistanceMeters=2f,X=0f,Y=0f,Z=2f,
            });
            controller.ApplyWorldSnapshot(world);
            controller.ApplyFocusSnapshot(new PerceptionFocusSnapshot
            {
                Revision=1,SessionGeneration=4,WorldRevision=2,UpdatedTimestampNs=110,
                ValidUntilTimestampNs=1_000,HasFocus=true,FocusedTrackId="person-1",
            });
            controller.ApplyHeadPoseSnapshot(new PerceptionHeadPoseSnapshot
            {
                Sequence=1,SessionGeneration=4,TimestampNs=100,Accuracy=3,W=1f,
            });
            Assert.IsTrue(controller.RenderFocusedObject(200));
            Assert.AreEqual(1,controller.ActiveFocusedIconCount);
            Assert.AreEqual("person-1",controller.ActiveFocusedTrackId);

            controller.ApplyInteractionState(new NonvisualInteractionState(true,
                NonvisualInteractionTarget.Back,VqaInteractionState.Idle,BeaconInteractionState.Off,true),0);
            controller.TickInteractionPresenter(NonvisualInteractionPresenter.DefaultDwellNs);
            Assert.IsTrue(controller.TryTakeNonvisualAnnouncement(out NonvisualAnnouncement announcement));
            Assert.AreEqual("Back",announcement.Text);
            yield return null;
            Object.Destroy(root);
        }

        [UnityTest]
        public IEnumerator CachedSnapshotSourceIsConsumedDuringUpdate()
        {
            var root=new GameObject("Cached perception test root");
            var controller=root.AddComponent<PerceptionLabController>();
            var world=new PerceptionWorldSnapshot
            {
                Revision=3,SessionGeneration=5,PublishedTimestampNs=100,
                ValidUntilTimestampNs=1_000,Validity=PerceptionValidity.PerceptionReady,
            };
            world.Entities.Add(new PerceptionEntitySnapshot
            {
                TrackId="door-1",ClassId="door",Frame=PerceptionFrame.World,HasPosition=true,
                SourceFrameId=1,SourceCaptureTimestampNs=100,OutputTimestampNs=100,
                Confidence=.9f,DistanceMeters=2f,X=1f,Y=0f,Z=2f,
            });
            var focus=new PerceptionFocusSnapshot
            {
                Revision=2,SessionGeneration=5,WorldRevision=3,UpdatedTimestampNs=110,
                ValidUntilTimestampNs=1_000,HasFocus=true,FocusedTrackId="door-1",
                AccessibilityAnnouncementToken="ready:5:2",
                AccessibilityAnnouncementText="Door. 2 o'clock. about 6 feet away.",
            };
            var source=new FakeSnapshotSource(world,focus,200);
            controller.ConfigurePerceptionRuntime(source,new InspectableFmodBackend(),SyntheticAdapter());
            yield return null;
            Assert.AreEqual(1,controller.ActiveFocusedIconCount);
            Assert.AreEqual("door-1",controller.ActiveFocusedTrackId);
            Assert.IsTrue(source.ClockRead);
            Assert.AreEqual(1,source.AccessibilityAnnouncementCount);
            Assert.AreEqual("ready:5:2",source.LastAccessibilityToken);
            Object.Destroy(root);
        }

        [UnityTest]
        public IEnumerator LiveSourceNeverDispatchesSyntheticScenarioGeometry()
        {
            var root=new GameObject("Live source synthetic suppression test root");
            var controller=root.AddComponent<PerceptionLabController>();
            var backend=new InspectableFmodBackend();
            var source=new FakeSnapshotSource(FocusWorld(),null,200L);
            controller.ConfigurePerceptionRuntime(source,backend,SyntheticAdapter());
            controller.BuildScenario(LabScenario.NoisyAmbient);
            yield return null;
            Assert.AreEqual(0,backend.SpatialCommandCount);
            StringAssert.Contains("synthetic geometry suppressed",controller.Status);
            Object.Destroy(root);
        }

        [UnityTest]
        public IEnumerator ExpiredVqaResultDoesNotAnnounceOnInitialPollOrReconnect()
        {
            var root=new GameObject("Expired focus announcement test root");
            var controller=root.AddComponent<PerceptionLabController>();
            var initial=new FakeSnapshotSource(FocusWorld(),ExpiredVqaResult(),200L);
            controller.ConfigurePerceptionRuntime(initial,new InspectableFmodBackend(),SyntheticAdapter());
            yield return null;
            Assert.IsTrue(initial.FocusPolled);
            Assert.AreEqual(0,initial.AccessibilityAnnouncementCount);

            var reconnected=new FakeSnapshotSource(FocusWorld(),ExpiredVqaResult(),250L);
            controller.ConfigurePerceptionRuntime(reconnected,new InspectableFmodBackend(),SyntheticAdapter());
            yield return null;
            Assert.IsTrue(reconnected.FocusPolled);
            Assert.AreEqual(0,reconnected.AccessibilityAnnouncementCount,
                "A reconnect rebootstrap must not speak an expired VQA result.");
            Object.Destroy(root);
        }

        [UnityTest]
        public IEnumerator ActiveHrtfTrialSuppressesOrdinaryFocusedRendering()
        {
            var root=new GameObject("HRTF integration test root");
            var controller=root.AddComponent<PerceptionLabController>();
            var backend=new InspectableFmodBackend();
            var attestedBackend=new AttestedAudioBackend(backend);
            var focus=new PerceptionFocusSnapshot
            {
                Revision=1,SessionGeneration=4,WorldRevision=1,UpdatedTimestampNs=100,
                ValidUntilTimestampNs=1_000,HasFocus=false,
                AccessibilityAnnouncementToken="must-not-escape",
                AccessibilityAnnouncementText="Hidden during localization.",
            };
            var source=new FakeSnapshotSource(new PerceptionWorldSnapshot
            {
                Revision=1,SessionGeneration=4,PublishedTimestampNs=100,
                ValidUntilTimestampNs=1_000,Validity=PerceptionValidity.PerceptionReady,
            },focus,100L);
            controller.ConfigurePerceptionRuntime(source,attestedBackend,SyntheticAdapter());
            string directory=Path.Combine(Path.GetTempPath(),"conceptflow-hrtf-play-"+Guid.NewGuid().ToString("N"));
            TextAsset manifest=Resources.Load<TextAsset>("focused_hrtf_trials");
            controller.ConfigureHrtfCalibrationRuntime(manifest.text,directory);
            controller.ApplyHeadPoseSnapshot(new PerceptionHeadPoseSnapshot
            {
                Sequence=1,SessionGeneration=4,TimestampNs=100,Accuracy=3,W=1f,
            });
            Assert.IsTrue(controller.HrtfCalibration.Start("play-session",100L));
            Assert.IsTrue(controller.HrtfCalibration.Next(100L));
            controller.HrtfCalibration.Tick(100L);
            Assert.AreEqual(1,backend.ActiveFocusedIconCount);
            Assert.IsFalse(controller.RenderFocusedObject(100L));
            Assert.AreEqual(0,controller.ActiveFocusedIconCount);
            Assert.AreEqual(1,backend.ActiveFocusedIconCount,
                "Clearing ordinary focus must not stop the calibration-owned neutral voice.");
            yield return null;
            Assert.IsTrue(source.FocusPolled);
            Assert.AreEqual(0,source.AccessibilityAnnouncementCount);
            controller.HrtfCalibration.Abort();
            Assert.AreEqual(0,backend.ActiveFocusedIconCount);
            Object.Destroy(root);
            Directory.Delete(directory,true);
        }

        [UnityTest]
        public IEnumerator HrtfStartCommandSuspendsAnnouncementsBeforeSameFrameFocusPoll()
        {
            var root=new GameObject("HRTF announcement transition test root");
            var controller=root.AddComponent<PerceptionLabController>();
            var backend=new AttestedAudioBackend(new InspectableFmodBackend());
            var focus=new PerceptionFocusSnapshot
            {
                Revision=1,SessionGeneration=4,WorldRevision=1,UpdatedTimestampNs=100,
                ValidUntilTimestampNs=1_000,HasFocus=false,
                AccessibilityAnnouncementToken="ready:4:2",
                AccessibilityAnnouncementText="This must remain queued out of the trial.",
            };
            var source=new FakeSnapshotSource(FocusWorld(4L,1L),focus,100L);
            controller.ConfigurePerceptionRuntime(source,backend,SyntheticAdapter());
            string directory=Path.Combine(Path.GetTempPath(),"conceptflow-hrtf-transition-"+Guid.NewGuid().ToString("N"));
            TextAsset manifest=Resources.Load<TextAsset>("focused_hrtf_trials");
            controller.ConfigureHrtfCalibrationRuntime(manifest.text,directory);
            File.WriteAllText(Path.Combine(directory,HrtfCalibrationCommandSpool.CommandFileName),
                "v1\t1\tstart\ttransition-session\n");

            yield return null;

            Assert.AreEqual(HrtfCalibrationState.ReadyForTrial,controller.HrtfCalibration.State);
            Assert.IsTrue(source.FocusPolled);
            Assert.IsTrue(source.AccessibilityAnnouncementsSuspended);
            Assert.AreEqual(0,source.AccessibilityAnnouncementCount);
            Object.Destroy(root);
            Directory.Delete(directory,true);
        }

        private static PerceptionWorldSnapshot FocusWorld(long session=5L,long revision=3L) => new()
        {
            Revision=revision,SessionGeneration=session,PublishedTimestampNs=100,
            ValidUntilTimestampNs=1_000,Validity=PerceptionValidity.PerceptionReady,
        };

        private static PerceptionFocusSnapshot ExpiredVqaResult() => new()
        {
            Revision=7,SessionGeneration=5,WorldRevision=3,UpdatedTimestampNs=100,
            ValidUntilTimestampNs=200,HasFocus=true,FocusedTrackId="door-1",
            Mode=PerceptionFocusMode.VqaResult,
            AccessibilityAnnouncementToken="vqa-result:5:2:9",
            AccessibilityAnnouncementText="A closed door is ahead.",
        };

        private static IPerceptionCoordinateFrameAdapterV1 SyntheticAdapter()
        {
            Matrix4x4 mapping=Matrix4x4.Scale(new Vector3(1f,1f,-1f));
            return new ExplicitBasisCoordinateFrameAdapterV1("synthetic-z-reflection/v1",mapping,mapping);
        }

        private sealed class FakeSnapshotSource : IPerceptionSnapshotSource,IAccessibilityAnnouncementSink
        {
            private PerceptionWorldSnapshot world;
            private PerceptionFocusSnapshot focus;
            private PerceptionHeadPoseSnapshot headPose;
            private readonly long nowNs;
            public FakeSnapshotSource(PerceptionWorldSnapshot world,PerceptionFocusSnapshot focus,long nowNs)
            {
                this.world=world; this.focus=focus; this.nowNs=nowNs;
                headPose=new PerceptionHeadPoseSnapshot
                {
                    Sequence=1,SessionGeneration=world.SessionGeneration,
                    TimestampNs=100,Accuracy=3,W=1f,
                };
            }
            public bool ClockRead { get; private set; }
            public bool FocusPolled { get; private set; }
            public int AccessibilityAnnouncementCount { get; private set; }
            public string LastAccessibilityToken { get; private set; }=string.Empty;
            public bool AccessibilityAnnouncementsSuspended { get; private set; }
            public bool TryPoll(out PerceptionWorldSnapshot state)
            { state=world; world=null; return state!=null; }
            public bool TryPollFocus(out PerceptionFocusSnapshot state)
            { FocusPolled=true; state=focus; focus=null; return state!=null; }
            public bool TryPollHeadPose(out PerceptionHeadPoseSnapshot state)
            { state=headPose; headPose=null; return state!=null; }
            public bool TryGetMonotonicTimestampNs(out long timestampNs)
            { ClockRead=true; timestampNs=nowNs; return true; }
            public bool DrainTouch(int maximum,System.Collections.Generic.List<PerceptionTouchSnapshot> destination)
            { destination?.Clear(); return false; }
            public bool TryAnnounceForAccessibility(string token,string text)
            {
                if(AccessibilityAnnouncementsSuspended) return false;
                AccessibilityAnnouncementCount++;
                LastAccessibilityToken=token;
                return !string.IsNullOrEmpty(text);
            }
            public void SetAccessibilityAnnouncementsSuspended(bool suspended) =>
                AccessibilityAnnouncementsSuspended=suspended;
            public void Dispose() { }
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
