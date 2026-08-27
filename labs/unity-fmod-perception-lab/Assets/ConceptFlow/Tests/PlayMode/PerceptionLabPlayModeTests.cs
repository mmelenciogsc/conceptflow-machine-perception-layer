// SPDX-License-Identifier: MIT OR Apache-2.0
using System.Collections;
using NUnit.Framework;
using UnityEngine;
using UnityEngine.TestTools;

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
            };
            var source=new FakeSnapshotSource(world,focus,200);
            controller.ConfigurePerceptionRuntime(source,new InspectableFmodBackend(),SyntheticAdapter());
            yield return null;
            Assert.AreEqual(1,controller.ActiveFocusedIconCount);
            Assert.AreEqual("door-1",controller.ActiveFocusedTrackId);
            Assert.IsTrue(source.ClockRead);
            Object.Destroy(root);
        }

        private static IPerceptionCoordinateFrameAdapterV1 SyntheticAdapter()
        {
            Matrix4x4 mapping=Matrix4x4.Scale(new Vector3(1f,1f,-1f));
            return new ExplicitBasisCoordinateFrameAdapterV1("synthetic-z-reflection/v1",mapping,mapping);
        }

        private sealed class FakeSnapshotSource : IPerceptionSnapshotSource
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
            public bool TryPoll(out PerceptionWorldSnapshot state)
            { state=world; world=null; return state!=null; }
            public bool TryPollFocus(out PerceptionFocusSnapshot state)
            { state=focus; focus=null; return state!=null; }
            public bool TryPollHeadPose(out PerceptionHeadPoseSnapshot state)
            { state=headPose; headPose=null; return state!=null; }
            public bool TryGetMonotonicTimestampNs(out long timestampNs)
            { ClockRead=true; timestampNs=nowNs; return true; }
            public bool DrainTouch(int maximum,System.Collections.Generic.List<PerceptionTouchSnapshot> destination)
            { destination?.Clear(); return false; }
            public void Dispose() { }
        }
    }
}
