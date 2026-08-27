// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.Collections.Generic;
using System.Text;

namespace ConceptFlow.Mpl.PerceptionLab
{
    public enum PerceptionFrame : byte { Camera = 1, Head = 2, World = 3 }
    public enum PerceptionValidity : ushort
    {
        SessionStarting = 1, SensorStreamActive = 2, PerceptionReady = 3,
        Disconnected = 4, Stopped = 5
    }

    public sealed class PerceptionEntitySnapshot
    {
        public string TrackId = string.Empty;
        public string ClassId = string.Empty;
        public PerceptionFrame Frame;
        public bool HasPosition;
        public bool HasUncertainty;
        public bool Propagated;
        public long SourceFrameId;
        public long SourceCaptureTimestampNs;
        public long OutputTimestampNs;
        public float Confidence;
        public float DistanceMeters;
        public float UncertaintyMeters;
        public float X;
        public float Y;
        public float Z;
    }

    public sealed class PerceptionWorldSnapshot
    {
        public long Revision;
        public long SessionGeneration;
        public long SourceFrameId;
        public long SourceCaptureTimestampNs;
        public long PublishedTimestampNs;
        public long ValidUntilTimestampNs;
        public string DepthProfileId = string.Empty;
        public string FusionReason = string.Empty;
        public PerceptionValidity Validity;
        public bool HasHeadOrientation;
        public long HeadTimestampNs;
        public int HeadAccuracy;
        public float HeadW;
        public float HeadX;
        public float HeadY;
        public float HeadZ;
        public readonly List<PerceptionEntitySnapshot> Entities = new(64);
    }

    public readonly struct PerceptionTouchSnapshot
    {
        public readonly long EventId;
        public readonly long HostObservedTimestampNs;
        public readonly long SourceUptimeMs;
        public readonly int Key;
        public readonly int Action;
        public readonly int ScanCode;
        public PerceptionTouchSnapshot(long eventId,long hostObservedTimestampNs,long sourceUptimeMs,int key,int action,int scanCode)
        { EventId=eventId; HostObservedTimestampNs=hostObservedTimestampNs; SourceUptimeMs=sourceUptimeMs; Key=key; Action=action; ScanCode=scanCode; }
    }

    /** Strict decoder for the explicit Android big-endian ABI; malformed snapshots fail closed. */
    public static class PerceptionBusBinaryDecoder
    {
        private const uint WorldMagic = 0x43465753; // CFWS
        private const uint TouchMagic = 0x43465442; // CFTB
        private const ushort Version = 1;
        private const int MaximumEntities = 64;
        private const int MaximumStringBytes = 128;

        public static bool TryDecodeWorld(byte[] bytes, out PerceptionWorldSnapshot state)
        {
            state = null;
            if (bytes == null) return false;
            try
            {
                var input = new BigEndianReader(bytes);
                if (input.ReadUInt32()!=WorldMagic || input.ReadUInt16()!=Version) return false;
                var candidate = new PerceptionWorldSnapshot { Validity=(PerceptionValidity)input.ReadUInt16() };
                if (!Enum.IsDefined(typeof(PerceptionValidity),candidate.Validity)) return false;
                candidate.Revision=input.ReadInt64(); candidate.SessionGeneration=input.ReadInt64();
                candidate.SourceFrameId=input.ReadInt64(); candidate.SourceCaptureTimestampNs=input.ReadInt64();
                candidate.PublishedTimestampNs=input.ReadInt64(); candidate.ValidUntilTimestampNs=input.ReadInt64();
                candidate.DepthProfileId=input.ReadString(MaximumStringBytes); candidate.FusionReason=input.ReadString(MaximumStringBytes);
                candidate.HasHeadOrientation=input.ReadBoolean();
                if(candidate.HasHeadOrientation)
                {
                    candidate.HeadTimestampNs=input.ReadInt64(); candidate.HeadAccuracy=input.ReadInt32();
                    candidate.HeadW=input.ReadSingle(); candidate.HeadX=input.ReadSingle();
                    candidate.HeadY=input.ReadSingle(); candidate.HeadZ=input.ReadSingle();
                }
                int count=input.ReadUInt16(); if(count>MaximumEntities) return false;
                for(int index=0;index<count;index++)
                {
                    var entity=new PerceptionEntitySnapshot();
                    entity.TrackId=input.ReadString(MaximumStringBytes); entity.ClassId=input.ReadString(MaximumStringBytes);
                    entity.Frame=(PerceptionFrame)input.ReadByte(); if(!Enum.IsDefined(typeof(PerceptionFrame),entity.Frame)) return false;
                    byte flags=input.ReadByte(); if((flags&~7)!=0 || input.ReadUInt16()!=0) return false;
                    entity.HasPosition=(flags&1)!=0; entity.HasUncertainty=(flags&2)!=0; entity.Propagated=(flags&4)!=0;
                    entity.SourceFrameId=input.ReadInt64(); entity.SourceCaptureTimestampNs=input.ReadInt64(); entity.OutputTimestampNs=input.ReadInt64();
                    entity.Confidence=input.ReadSingle(); entity.DistanceMeters=input.ReadSingle(); entity.UncertaintyMeters=input.ReadSingle();
                    entity.X=input.ReadSingle(); entity.Y=input.ReadSingle(); entity.Z=input.ReadSingle();
                    if(entity.SourceFrameId<=0 || entity.Confidence<0f || entity.Confidence>1f ||
                       float.IsNaN(entity.DistanceMeters) || entity.DistanceMeters<=0f) return false;
                    candidate.Entities.Add(entity);
                }
                if(!input.AtEnd || candidate.Revision<=0 || candidate.SessionGeneration<0 ||
                   candidate.PublishedTimestampNs<0 || candidate.ValidUntilTimestampNs<candidate.PublishedTimestampNs) return false;
                state=candidate; return true;
            }
            catch (Exception error) when (error is IndexOutOfRangeException || error is ArgumentException || error is DecoderFallbackException)
            { return false; }
        }

        public static bool TryDecodeTouchBatch(byte[] bytes, List<PerceptionTouchSnapshot> destination)
        {
            if(bytes==null || destination==null) return false;
            destination.Clear();
            try
            {
                var input=new BigEndianReader(bytes);
                if(input.ReadUInt32()!=TouchMagic || input.ReadUInt16()!=Version) return false;
                int count=input.ReadUInt16(); if(count>128) return false;
                for(int index=0;index<count;index++) destination.Add(new PerceptionTouchSnapshot(
                    input.ReadInt64(),input.ReadInt64(),input.ReadInt64(),input.ReadInt32(),input.ReadInt32(),input.ReadInt32()));
                return input.AtEnd;
            }
            catch (Exception error) when (error is IndexOutOfRangeException || error is ArgumentException)
            { destination.Clear(); return false; }
        }

        private sealed class BigEndianReader
        {
            private readonly byte[] bytes; private int offset;
            public BigEndianReader(byte[] bytes) { this.bytes=bytes; }
            public bool AtEnd => offset==bytes.Length;
            public byte ReadByte() { Require(1); return bytes[offset++]; }
            public bool ReadBoolean() { byte value=ReadByte(); if(value>1) throw new ArgumentException("invalid boolean"); return value==1; }
            public ushort ReadUInt16() { Require(2); ushort value=(ushort)((bytes[offset]<<8)|bytes[offset+1]); offset+=2; return value; }
            public uint ReadUInt32() { Require(4); uint value=((uint)bytes[offset]<<24)|((uint)bytes[offset+1]<<16)|((uint)bytes[offset+2]<<8)|bytes[offset+3]; offset+=4; return value; }
            public int ReadInt32() => unchecked((int)ReadUInt32());
            public long ReadInt64() { Require(8); ulong value=0; for(int i=0;i<8;i++) value=(value<<8)|bytes[offset+i]; offset+=8; return unchecked((long)value); }
            public float ReadSingle() { uint bits=ReadUInt32(); return BitConverter.Int32BitsToSingle(unchecked((int)bits)); }
            public string ReadString(int maximum)
            {
                int length=ReadUInt16(); if(length>maximum) throw new ArgumentException("string too long"); Require(length);
                var strict=new UTF8Encoding(false,true); string value=strict.GetString(bytes,offset,length); offset+=length; return value;
            }
            private void Require(int count) { if(count<0 || offset>bytes.Length-count) throw new IndexOutOfRangeException(); }
        }
    }

    /** Polls only compact latest-state/event payloads. It performs no socket, sensor, or inference work. */
    public sealed class AndroidPerceptionBridgeClient : IDisposable
    {
        private long lastRevision;
#if UNITY_ANDROID && !UNITY_EDITOR
        private readonly UnityEngine.AndroidJavaClass bridge = new("org.conceptflow.mpl.host.realtime.AndroidPerceptionBridge");
#endif
        public bool TryPoll(out PerceptionWorldSnapshot state)
        {
            state=null;
#if UNITY_ANDROID && !UNITY_EDITOR
            byte[] bytes=bridge.CallStatic<byte[]>("pollWorldState",lastRevision);
            if(!PerceptionBusBinaryDecoder.TryDecodeWorld(bytes,out state)) return false;
            lastRevision=state.Revision; return true;
#else
            return false;
#endif
        }
        public bool DrainTouch(int maximum,List<PerceptionTouchSnapshot> destination)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            byte[] bytes=bridge.CallStatic<byte[]>("drainTouchEvents",Math.Max(1,Math.Min(128,maximum)));
            return PerceptionBusBinaryDecoder.TryDecodeTouchBatch(bytes,destination);
#else
            destination?.Clear(); return false;
#endif
        }
        public void Dispose()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            bridge.Dispose();
#endif
        }
    }
}
