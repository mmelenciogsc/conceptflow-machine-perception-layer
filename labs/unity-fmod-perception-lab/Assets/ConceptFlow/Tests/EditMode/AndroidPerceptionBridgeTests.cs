// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.Collections.Generic;
using System.Text;
using NUnit.Framework;

namespace ConceptFlow.Mpl.PerceptionLab.Tests
{
    public sealed class AndroidPerceptionBridgeTests
    {
        [Test]
        public void DecoderRejectsTruncatedOrWrongMagic()
        {
            Assert.IsFalse(PerceptionBusBinaryDecoder.TryDecodeWorld(Array.Empty<byte>(),out _));
            Assert.IsFalse(PerceptionBusBinaryDecoder.TryDecodeWorld(new byte[64],out _));
            Assert.IsFalse(PerceptionBusBinaryDecoder.TryDecodeTouchBatch(new byte[7],new List<PerceptionTouchSnapshot>()));
        }

        [Test]
        public void EmptyTouchBatchDecodes()
        {
            byte[] bytes={0x43,0x46,0x54,0x42,0,1,0,0};
            var values=new List<PerceptionTouchSnapshot>();
            Assert.IsTrue(PerceptionBusBinaryDecoder.TryDecodeTouchBatch(bytes,values));
            Assert.AreEqual(0,values.Count);
        }

        [Test]
        public void FocusSnapshotDecodesAndRejectsInconsistentFocusFlag()
        {
            var bytes=new List<byte>();
            U32(bytes,0x43464653); U16(bytes,1); U16(bytes,1);
            I64(bytes,7); I64(bytes,2); I64(bytes,6); I64(bytes,100); I64(bytes,900);
            String(bytes,"track-7");
            Assert.IsTrue(PerceptionBusBinaryDecoder.TryDecodeFocus(bytes.ToArray(),out PerceptionFocusSnapshot focus));
            Assert.AreEqual(7,focus.Revision); Assert.AreEqual("track-7",focus.FocusedTrackId);

            bytes[7]=0;
            Assert.IsFalse(PerceptionBusBinaryDecoder.TryDecodeFocus(bytes.ToArray(),out _));
        }

        [Test]
        public void HeadPoseDecodesAndRejectsNonUnitQuaternion()
        {
            var bytes=new List<byte>();
            U32(bytes,0x43464850); U16(bytes,1); U16(bytes,0);
            I64(bytes,4); I64(bytes,2); I64(bytes,100); I32(bytes,3);
            F32(bytes,1f); F32(bytes,0f); F32(bytes,0f); F32(bytes,0f);
            Assert.IsTrue(PerceptionBusBinaryDecoder.TryDecodeHeadPose(bytes.ToArray(),out PerceptionHeadPoseSnapshot pose));
            Assert.AreEqual(4,pose.Sequence); Assert.AreEqual(1f,pose.W);

            int quaternionOffset=8+8+8+8+4;
            bytes[quaternionOffset]=0; bytes[quaternionOffset+1]=0;
            bytes[quaternionOffset+2]=0; bytes[quaternionOffset+3]=0;
            Assert.IsFalse(PerceptionBusBinaryDecoder.TryDecodeHeadPose(bytes.ToArray(),out _));
        }

        [Test]
        public void WorldSnapshotRejectsEveryNonfiniteEntityField()
        {
            Assert.IsFalse(PerceptionBusBinaryDecoder.TryDecodeWorld(
                WorldBytes(float.NaN,0.1f,1f,2f,3f),out _));
            Assert.IsFalse(PerceptionBusBinaryDecoder.TryDecodeWorld(
                WorldBytes(.8f,float.PositiveInfinity,1f,2f,3f),out _));
            Assert.IsFalse(PerceptionBusBinaryDecoder.TryDecodeWorld(
                WorldBytes(.8f,.1f,1f,float.NegativeInfinity,3f),out _));
            Assert.IsTrue(PerceptionBusBinaryDecoder.TryDecodeWorld(
                WorldBytes(.8f,.1f,1f,2f,3f),out _));
        }

        private static byte[] WorldBytes(float confidence,float uncertainty,float x,float y,float z)
        {
            var bytes=new List<byte>();
            U32(bytes,0x43465753); U16(bytes,1); U16(bytes,3);
            I64(bytes,1); I64(bytes,1); I64(bytes,1); I64(bytes,100);
            I64(bytes,110); I64(bytes,1_000); String(bytes,"depth-v1"); String(bytes,"test");
            bytes.Add(0); U16(bytes,1);
            String(bytes,"track-1"); String(bytes,"person"); bytes.Add(3); bytes.Add(3); U16(bytes,0);
            I64(bytes,1); I64(bytes,100); I64(bytes,110);
            F32(bytes,confidence); F32(bytes,2f); F32(bytes,uncertainty);
            F32(bytes,x); F32(bytes,y); F32(bytes,z);
            return bytes.ToArray();
        }

        private static void U16(List<byte> destination,ushort value)
        { destination.Add((byte)(value>>8)); destination.Add((byte)value); }
        private static void U32(List<byte> destination,uint value)
        {
            destination.Add((byte)(value>>24)); destination.Add((byte)(value>>16));
            destination.Add((byte)(value>>8)); destination.Add((byte)value);
        }
        private static void I32(List<byte> destination,int value) => U32(destination,unchecked((uint)value));
        private static void I64(List<byte> destination,long value)
        {
            ulong bits=unchecked((ulong)value);
            for(int shift=56;shift>=0;shift-=8) destination.Add((byte)(bits>>shift));
        }
        private static void F32(List<byte> destination,float value) =>
            I32(destination,BitConverter.SingleToInt32Bits(value));
        private static void String(List<byte> destination,string value)
        {
            byte[] encoded=Encoding.UTF8.GetBytes(value); U16(destination,(ushort)encoded.Length);
            destination.AddRange(encoded);
        }
    }
}
