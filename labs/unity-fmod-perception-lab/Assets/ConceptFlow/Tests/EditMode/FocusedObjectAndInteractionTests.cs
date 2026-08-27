// SPDX-License-Identifier: MIT OR Apache-2.0
using NUnit.Framework;
using UnityEngine;

namespace ConceptFlow.Mpl.PerceptionLab.Tests
{
    public sealed class FocusedObjectAndInteractionTests
    {
        [Test]
        public void AuditoryIconRegistryUsesExplicitAliasesAndNeutralFallback()
        {
            var registry=new AuditoryIconRegistry();
            Assert.AreEqual("vehicle",registry.Resolve("car").Concept);
            Assert.AreEqual("vehicle",registry.Resolve("MOTORCYCLE").Concept);
            Assert.AreEqual("person",registry.Resolve(" child ").Concept);
            Assert.IsFalse(registry.Resolve("traffic light").Representational);
            Assert.AreEqual("neutral",registry.Resolve(null).Concept);
        }

        [Test]
        public void FocusRendererMaintainsOneIconAndStopsExpiredFocus()
        {
            var backend=new InspectableFmodBackend();
            var renderer=new FocusedObjectSonification(backend,coordinateAdapter:SyntheticAdapter());
            PerceptionEntitySnapshot farEntity=Entity("a","person",PerceptionFrame.World,1f,2f,3f);
            farEntity.DistanceMeters=12f;
            PerceptionWorldSnapshot world=World(2,1_000_000_000L,farEntity);
            renderer.AcceptWorld(world); renderer.AcceptFocus(Focus(1,2,"a",900_000_000L));
            renderer.AcceptHeadPose(HeadPose(1,400_000_000L));
            Assert.IsTrue(renderer.Render(500_000_000L));
            Assert.AreEqual(1,backend.ActiveFocusedIconCount);
            Assert.AreEqual("a",backend.ActiveFocusedTrackId);
            Assert.AreEqual(new Vector3(1f,2f,-3f),backend.LastFocusedIconCommand.Value.Position);
            Assert.AreEqual(8f,backend.LastFocusedIconCommand.Value.Parameters[3].Value);

            world=World(3,1_100_000_000L,
                Entity("a","person",PerceptionFrame.World,1f,2f,3f),
                Entity("b","door",PerceptionFrame.World,-1f,0f,2f));
            renderer.AcceptWorld(world); renderer.AcceptFocus(Focus(2,3,"b",1_000_000_000L));
            Assert.IsTrue(renderer.Render(600_000_000L));
            Assert.AreEqual(1,backend.ActiveFocusedIconCount);
            Assert.AreEqual("b",backend.ActiveFocusedTrackId);

            Assert.IsFalse(renderer.Render(1_200_000_000L));
            Assert.AreEqual(0,backend.ActiveFocusedIconCount);
        }

        [Test]
        public void WorldRendererRequiresFreshSameSessionMappedHeadPose()
        {
            var backend=new InspectableFmodBackend();
            var renderer=new FocusedObjectSonification(backend,coordinateAdapter:SyntheticAdapter());
            renderer.AcceptWorld(World(2,1_000_000_000L,
                Entity("a","person",PerceptionFrame.World,0f,0f,2f)));
            renderer.AcceptFocus(Focus(1,2,"a",1_000_000_000L));
            Assert.IsFalse(renderer.Render(500_000_000L));
            Assert.IsFalse(backend.LastListenerPoseCommand.HasValue);

            renderer.AcceptHeadPose(HeadPose(1,400_000_000L,sessionGeneration:8));
            Assert.IsFalse(renderer.Render(500_000_000L));
            Assert.IsFalse(backend.LastListenerPoseCommand.HasValue);

            renderer.AcceptHeadPose(HeadPose(2,200_000_000L));
            Assert.IsFalse(renderer.Render(500_000_000L));
            Assert.IsFalse(backend.LastListenerPoseCommand.HasValue);

            renderer.AcceptHeadPose(HeadPose(3,400_000_000L));
            Assert.IsTrue(renderer.Render(500_000_000L));
            Assert.AreEqual(400_000_000L,backend.LastListenerPoseCommand.Value.TimestampNs);
            Assert.AreEqual(1,backend.ActiveFocusedIconCount);

            int commandCount=backend.FocusedCommandCount;
            renderer.AcceptHeadPose(HeadPose(4,450_000_000L));
            Assert.IsTrue(renderer.Render(500_000_000L));
            Assert.AreEqual(commandCount+1,backend.FocusedCommandCount);

            Assert.IsFalse(renderer.Render(700_000_001L));
            Assert.AreEqual(0,backend.ActiveFocusedIconCount);

            var unmapped=new FocusedObjectSonification(
                new InspectableFmodBackend(),coordinateAdapter:new PositionOnlyAdapter());
            unmapped.AcceptWorld(World(2,1_000_000_000L,
                Entity("a","person",PerceptionFrame.World,0f,0f,2f)));
            unmapped.AcceptFocus(Focus(1,2,"a",1_000_000_000L));
            unmapped.AcceptHeadPose(HeadPose(1,400_000_000L));
            Assert.IsFalse(unmapped.Render(500_000_000L));
        }

        [Test]
        public void FocusRendererRequiresFreshHeadPoseAndRejectsUnsupportedOrNonfiniteFrames()
        {
            var backend=new InspectableFmodBackend();
            var renderer=new FocusedObjectSonification(backend,coordinateAdapter:SyntheticAdapter());
            renderer.AcceptFocus(Focus(1,2,"a",1_000_000_000L));
            renderer.AcceptWorld(World(2,1_000_000_000L,Entity("a","person",PerceptionFrame.Head,0f,0f,2f)));
            Assert.IsFalse(renderer.Render(500_000_000L));

            renderer.AcceptHeadPose(new PerceptionHeadPoseSnapshot
                { Sequence=1,SessionGeneration=9,TimestampNs=400_000_000L,Accuracy=3,W=1f });
            Assert.IsTrue(renderer.Render(500_000_000L));
            Assert.AreEqual(1,backend.ActiveFocusedIconCount);

            renderer.AcceptWorld(World(3,1_000_000_000L,Entity("a","person",PerceptionFrame.Camera,0f,0f,2f)));
            renderer.AcceptFocus(Focus(2,3,"a",1_000_000_000L));
            Assert.IsFalse(renderer.Render(500_000_000L));

            PerceptionEntitySnapshot invalid=Entity("a","person",PerceptionFrame.World,float.NaN,0f,2f);
            renderer.AcceptWorld(World(4,1_000_000_000L,invalid));
            renderer.AcceptFocus(Focus(3,4,"a",1_000_000_000L));
            Assert.IsFalse(renderer.Render(500_000_000L));
        }

        [Test]
        public void UnknownFocusedClassUsesNeutralIconFallback()
        {
            var backend=new InspectableFmodBackend();
            var renderer=new FocusedObjectSonification(backend,coordinateAdapter:SyntheticAdapter());
            renderer.AcceptWorld(World(2,1_000_000_000L,
                Entity("a","traffic light",PerceptionFrame.World,0f,0f,2f)));
            renderer.AcceptFocus(Focus(1,2,"a",1_000_000_000L));
            renderer.AcceptHeadPose(HeadPose(1,400_000_000L));
            Assert.IsTrue(renderer.Render(500_000_000L));
            Assert.AreEqual(0f,backend.LastFocusedIconCommand.Value.Parameters[0].Value);
            Assert.AreEqual("procedural/neutral_presence",backend.LastFocusedIconCommand.Value.AssetKey);
        }

        [Test]
        public void FocusRendererRejectsStaleEntityEvenInsideFreshWorldEnvelope()
        {
            var backend=new InspectableFmodBackend();
            var renderer=new FocusedObjectSonification(backend,coordinateAdapter:SyntheticAdapter());
            PerceptionEntitySnapshot entity=Entity("a","person",PerceptionFrame.World,0f,0f,2f);
            entity.SourceCaptureTimestampNs=0L; entity.OutputTimestampNs=0L;
            PerceptionWorldSnapshot world=World(2,3_000_000_000L,entity);
            world.PublishedTimestampNs=1_900_000_000L;
            renderer.AcceptWorld(world);
            PerceptionFocusSnapshot focus=Focus(1,2,"a",3_000_000_000L);
            focus.UpdatedTimestampNs=1_900_000_000L;
            renderer.AcceptFocus(focus);
            Assert.IsFalse(renderer.Render(2_000_000_000L));
            Assert.AreEqual(0,backend.ActiveFocusedIconCount);
        }

        [Test]
        public void DefaultCoordinateAdapterFailsClosed()
        {
            var backend=new InspectableFmodBackend();
            var renderer=new FocusedObjectSonification(backend);
            renderer.AcceptWorld(World(2,1_000_000_000L,
                Entity("a","person",PerceptionFrame.World,0f,0f,2f)));
            renderer.AcceptFocus(Focus(1,2,"a",1_000_000_000L));
            renderer.AcceptHeadPose(HeadPose(1,400_000_000L));
            Assert.IsFalse(renderer.Render(500_000_000L));
            Assert.AreEqual("unverified/fail-closed/v1",renderer.CoordinateMappingId);
        }

        [Test]
        public void ExplicitSyntheticBasisMapsCardinalsAndQuaternionByMatrixConjugation()
        {
            IPerceptionCoordinateFrameAdapterV1 adapter=SyntheticAdapter();
            Assert.IsTrue(adapter.TryMapPosition(PerceptionFrame.Head,new Vector3(1f,2f,3f),out Vector3 point));
            Assert.AreEqual(new Vector3(1f,2f,-3f),point);

            Assert.IsTrue(adapter.TryMapHeadOrientation(new PerceptionHeadPoseSnapshot
                { Sequence=1,SessionGeneration=1,TimestampNs=1,Accuracy=3,W=1f },out Quaternion identity));
            Assert.Less(Quaternion.Angle(Quaternion.identity,identity),.01f);

            Quaternion source=Quaternion.AngleAxis(90f,Vector3.up);
            Assert.IsTrue(adapter.TryMapHeadOrientation(new PerceptionHeadPoseSnapshot
            {
                Sequence=2,SessionGeneration=1,TimestampNs=2,Accuracy=3,
                W=source.w,X=source.x,Y=source.y,Z=source.z,
            },out Quaternion mapped));
            Assert.Less(Vector3.Distance(mapped*Vector3.forward,Vector3.left),.001f);
            Assert.Less(Vector3.Distance(mapped*Vector3.up,Vector3.up),.001f);
        }

        [Test]
        public void PresenterCancelsOldDwellGenerationAndDucksOnlyCurrentSpeech()
        {
            var backend=new InspectableFmodBackend();
            var presenter=new NonvisualInteractionPresenter(backend);
            presenter.Apply(new NonvisualInteractionState(true,NonvisualInteractionTarget.Vqa,
                VqaInteractionState.Idle,BeaconInteractionState.Off,true),0L);
            long first=presenter.Generation;
            presenter.Apply(new NonvisualInteractionState(true,NonvisualInteractionTarget.Beacon,
                VqaInteractionState.Idle,BeaconInteractionState.Guiding,true),500_000_000L);
            long second=presenter.Generation;
            Assert.Greater(second,first);
            presenter.Tick(1_200_000_000L);
            Assert.IsFalse(backend.DwellSpeechActive);
            presenter.Tick(1_700_000_000L);
            Assert.IsTrue(backend.DwellSpeechActive);
            Assert.IsTrue(presenter.TryTakeAnnouncement(out NonvisualAnnouncement transition));
            Assert.AreEqual("Beacon, guidance active",transition.Text);
            Assert.IsTrue(presenter.TryTakeAnnouncement(out NonvisualAnnouncement dwell));
            Assert.IsTrue(dwell.IsDwell); Assert.AreEqual(second,dwell.Generation);

            presenter.Apply(new NonvisualInteractionState(true,NonvisualInteractionTarget.Back,
                VqaInteractionState.Idle,BeaconInteractionState.Guiding,true),1_800_000_000L);
            Assert.IsFalse(backend.DwellSpeechActive);
            presenter.CompleteDwellSpeech(second);
            Assert.IsFalse(backend.DwellSpeechActive);
        }

        [Test]
        public void PresenterDropsQueuedDwellWhenFocusChanges()
        {
            var backend=new InspectableFmodBackend();
            var presenter=new NonvisualInteractionPresenter(backend);
            presenter.Apply(new NonvisualInteractionState(true,NonvisualInteractionTarget.Vqa,
                VqaInteractionState.Idle,BeaconInteractionState.Off,true),0L);
            presenter.Tick(NonvisualInteractionPresenter.DefaultDwellNs);
            Assert.AreEqual(1,presenter.PendingAnnouncementCount);
            presenter.Apply(new NonvisualInteractionState(true,NonvisualInteractionTarget.Back,
                VqaInteractionState.Idle,BeaconInteractionState.Off,true),
                NonvisualInteractionPresenter.DefaultDwellNs+1);
            Assert.IsFalse(presenter.TryTakeAnnouncement(out _));
            Assert.IsFalse(backend.DwellSpeechActive);
        }

        [Test]
        public void IconCreatedDuringDwellReceivesCompleteDuckingState()
        {
            var backend=new InspectableFmodBackend();
            backend.SetDwellSpeech(1,true,.25f);
            var renderer=new FocusedObjectSonification(backend,coordinateAdapter:SyntheticAdapter());
            renderer.AcceptWorld(World(2,1_000_000_000L,
                Entity("a","person",PerceptionFrame.World,0f,0f,2f)));
            renderer.AcceptFocus(Focus(1,2,"a",1_000_000_000L));
            renderer.AcceptHeadPose(HeadPose(1,400_000_000L));

            Assert.IsTrue(renderer.Render(500_000_000L));
            Assert.IsTrue(backend.LastFocusedDwellSpeechActive);
            Assert.AreEqual(.25f,backend.CurrentDuckGain);
        }

        [Test]
        public void PrioritySchedulerIsDeterministicAndCapacityBounded()
        {
            var scheduler=new PerceptualPriorityScheduler(maximumAudioVoices:1);
            scheduler.Submit(new ScheduledPerceptualCue("ordinary","ordinary",PerceptualPriorityLane.OrdinaryIcon,
                10,1_000,200,new object()),20);
            scheduler.Submit(new ScheduledPerceptualCue("geometry","geometry",PerceptualPriorityLane.Geometry,
                11,1_000,200,new object()),20);
            var selected=scheduler.Dispatch(20);
            Assert.AreEqual(1,selected.Count);
            Assert.AreEqual("geometry",selected[0].CueId);
            Assert.AreEqual(1,scheduler.Counters.SuppressedCapacity);
        }

        private static PerceptionWorldSnapshot World(long revision,long validUntil,params PerceptionEntitySnapshot[] entities)
        {
            var world=new PerceptionWorldSnapshot
            {
                Revision=revision,SessionGeneration=9,PublishedTimestampNs=100_000_000L,
                ValidUntilTimestampNs=validUntil,Validity=PerceptionValidity.PerceptionReady,
            };
            world.Entities.AddRange(entities); return world;
        }

        private static PerceptionFocusSnapshot Focus(long revision,long worldRevision,string trackId,long validUntil) =>
            new()
            {
                Revision=revision,SessionGeneration=9,WorldRevision=worldRevision,
                UpdatedTimestampNs=200_000_000L,ValidUntilTimestampNs=validUntil,
                HasFocus=true,FocusedTrackId=trackId,
            };

        private static PerceptionEntitySnapshot Entity(string trackId,string classId,PerceptionFrame frame,float x,float y,float z) =>
            new()
            {
                TrackId=trackId,ClassId=classId,Frame=frame,HasPosition=true,SourceFrameId=1,
                SourceCaptureTimestampNs=400_000_000L,OutputTimestampNs=400_000_000L,
                Confidence=.8f,DistanceMeters=2f,X=x,Y=y,Z=z,
            };

        private static PerceptionHeadPoseSnapshot HeadPose(
            long sequence,
            long timestampNs,
            long sessionGeneration=9) => new()
            {
                Sequence=sequence,SessionGeneration=sessionGeneration,
                TimestampNs=timestampNs,Accuracy=3,W=1f,
            };

        private static IPerceptionCoordinateFrameAdapterV1 SyntheticAdapter()
        {
            Matrix4x4 rightHandedToUnity=Matrix4x4.Scale(new Vector3(1f,1f,-1f));
            return new ExplicitBasisCoordinateFrameAdapterV1(
                "synthetic-z-reflection/v1",rightHandedToUnity,rightHandedToUnity);
        }

        private sealed class PositionOnlyAdapter : IPerceptionCoordinateFrameAdapterV1
        {
            public string MappingId => "position-only/test";
            public bool TryMapPosition(PerceptionFrame frame,Vector3 source,out Vector3 unity)
            { unity=source; return frame==PerceptionFrame.Head||frame==PerceptionFrame.World; }
            public bool TryMapHeadOrientation(PerceptionHeadPoseSnapshot pose,out Quaternion unity)
            { unity=default; return false; }
        }
    }
}
