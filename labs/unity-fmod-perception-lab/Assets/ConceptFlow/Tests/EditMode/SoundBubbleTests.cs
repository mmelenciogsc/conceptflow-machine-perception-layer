// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.Linq;
using NUnit.Framework;
using UnityEngine;

namespace ConceptFlow.Mpl.PerceptionLab.Tests
{
    public sealed class SoundBubbleTests
    {
        private CanonicalConfig config = null!;
        private BodySurfaceField body = null!;

        [SetUp] public void SetUp() { config=CanonicalConfig.Load(); body=new BodySurfaceField(config); }

        [Test] public void ExactDefaultRadius() => Assert.That(body.BubbleRadiusMeters,Is.EqualTo(.9144f).Within(1e-7f));

        [Test] public void EqualClearanceAcrossHeadLateralAndLowerBody()
        {
            var samples=new[]{
                (BodyRegion.Head,Vector3.up),
                (BodyRegion.LeftLateral,Vector3.left),
                (BodyRegion.LowerBody,Vector3.left)};
            foreach(var sample in samples)
            {
                BodyCapsule segment=body.Segments.First(item=>item.Region==sample.Item1 && (sample.Item1!=BodyRegion.LowerBody||item.Start.x<0));
                Vector3 axis=(segment.Start+segment.End)*.5f;
                Clearance value=body.Evaluate(axis+sample.Item2*(segment.Radius+.30f));
                Assert.That(value.Distance,Is.EqualTo(.30f).Within(1e-5f));
                Assert.That(value.Region,Is.EqualTo(sample.Item1));
            }
        }

        [Test] public void FourBanksHaveThreeRingsAndInwardNormals()
        {
            var field=new VirtualSpeakerField(body,config);
            Assert.That(field.Emitters.Count,Is.EqualTo(144));
            foreach(SpeakerBank bank in Enum.GetValues(typeof(SpeakerBank)))
            {
                var emitters=field.Emitters.Where(item=>item.Bank==bank).ToArray();
                Assert.That(emitters.Length,Is.EqualTo(36));
                Assert.That(emitters.Select(item=>item.Ring).Distinct().Count(),Is.EqualTo(3));
                Assert.That(emitters.All(item=>Mathf.Abs(body.Evaluate(item.Position).Distance-body.BubbleRadiusMeters)<2e-5f),Is.True);
                Assert.That(emitters.All(item=>Vector3.Dot(item.Position-body.Evaluate(item.Position).SurfacePoint,item.InwardNormal)<0f),Is.True);
            }
        }

        [Test] public void WeightsNormalizeAndMoveContinuously()
        {
            var field=new VirtualSpeakerField(body,config);
            float[] first=field.Weights(new Vector3(.49f,0,.51f));
            float[] second=field.Weights(new Vector3(.51f,0,.49f));
            Assert.That(first.Sum(),Is.EqualTo(1f).Within(1e-5f));
            Assert.That(second.Sum(),Is.EqualTo(1f).Within(1e-5f));
            Assert.That(first.Zip(second,(a,b)=>Mathf.Abs(a-b)).Sum(),Is.LessThan(.15f));
        }

        [Test] public void HeadTurnDoesNotRotateBodyEnvelope()
        {
            Vector3 localBodyPoint=new(.3f,1.2f,.1f);
            Matrix4x4 worldFromBody=Matrix4x4.TRS(new Vector3(2,0,0),Quaternion.Euler(0,30,0),Vector3.one);
            Matrix4x4 bodyFromHeadA=Matrix4x4.TRS(new Vector3(0,1.6f,0),Quaternion.identity,Vector3.one);
            Matrix4x4 bodyFromHeadB=Matrix4x4.TRS(new Vector3(0,1.6f,0),Quaternion.Euler(0,90,0),Vector3.one);
            Vector3 envelopePoint=worldFromBody.MultiplyPoint3x4(localBodyPoint);
            Assert.That(Vector3.Distance(envelopePoint,new Vector3(2.30980766f,1.2f,-.06339747f)),Is.LessThan(1e-5f));
            Assert.That((worldFromBody*bodyFromHeadA).MultiplyVector(Vector3.forward),Is.Not.EqualTo((worldFromBody*bodyFromHeadB).MultiplyVector(Vector3.forward)));
        }

        [Test] public void InspectableBackendIsBoundedAndTextual()
        {
            string value=new InspectableFmodBackend().Dispatch(new SpatialAudioCommand(InspectableFmodBackend.AnchorEvent,"Intrusion Anchor",Vector3.forward,Vector3.back,.2f,.3f));
            StringAssert.Contains("Intrusion Anchor",value); StringAssert.Contains("gain=0.200",value);
        }

        [Test] public void AudioOrientationRepairsSlopedAndCollinearBases()
        {
            AudioOrientation.Orthonormalize(new Vector3(.99f,-.12f,.01f),Vector3.up,
                out Vector3 slopedForward,out Vector3 slopedUp);
            Assert.That(slopedForward.magnitude,Is.EqualTo(1f).Within(1e-5f));
            Assert.That(slopedUp.magnitude,Is.EqualTo(1f).Within(1e-5f));
            Assert.That(Mathf.Abs(Vector3.Dot(slopedForward,slopedUp)),Is.LessThan(1e-5f));

            AudioOrientation.Orthonormalize(Vector3.up,Vector3.up,
                out Vector3 verticalForward,out Vector3 verticalUp);
            Assert.That(verticalForward.magnitude,Is.EqualTo(1f).Within(1e-5f));
            Assert.That(verticalUp.magnitude,Is.EqualTo(1f).Within(1e-5f));
            Assert.That(Mathf.Abs(Vector3.Dot(verticalForward,verticalUp)),Is.LessThan(1e-5f));
        }
    }
}
