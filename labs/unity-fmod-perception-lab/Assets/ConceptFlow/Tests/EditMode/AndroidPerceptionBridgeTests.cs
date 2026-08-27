// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.Collections.Generic;
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
    }
}
