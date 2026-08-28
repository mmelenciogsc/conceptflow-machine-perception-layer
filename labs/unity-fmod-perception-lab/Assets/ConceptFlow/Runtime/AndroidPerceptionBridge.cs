// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.Collections.Generic;
using System.Text;

namespace ConceptFlow.Mpl.PerceptionLab
{
    public enum PerceptionFrame : byte { Camera = 1, Head = 2, World = 3 }
    public enum PerceptionFocusMode : byte
    {
        Inactive=0, Browsing=1, ActionMenu=2, VqaPending=3, VqaResult=4, BeaconActive=5
    }
    public enum PerceptionBeaconAnchorMode : byte
    {
        None=0, WorldAnchored=1, OrientationStabilizedRelative=2
    }
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

    public sealed class PerceptionBeaconSnapshot
    {
        public string TrackId=string.Empty;
        public string ClassId=string.Empty;
        public PerceptionBeaconAnchorMode AnchorMode;
        public long ActivationId;
        public long ActivatedTimestampNs;
        public long ValidUntilTimestampNs;
        public long SourceFrameId;
        public long SourceCaptureTimestampNs;
        public float Confidence;
        public float DistanceMeters;
        public bool HasDistanceUncertainty;
        public float DistanceUncertaintyMeters;
        public float X;
        public float Y;
        public float Z;
        public bool HasReferenceHeadOrientation;
        public long ReferenceHeadTimestampNs;
        public int ReferenceHeadAccuracy;
        public float ReferenceHeadW;
        public float ReferenceHeadX;
        public float ReferenceHeadY;
        public float ReferenceHeadZ;
    }

    /** Focus references world state; an active beacon carries a bounded immutable anchor snapshot. */
    public sealed class PerceptionFocusSnapshot
    {
        public long Revision;
        public long SessionGeneration;
        public long WorldRevision;
        public long UpdatedTimestampNs;
        public long ValidUntilTimestampNs;
        public bool HasFocus;
        public string FocusedTrackId = string.Empty;
        public PerceptionFocusMode Mode;
        public PerceptionBeaconSnapshot Beacon;
    }

    /** Latest bounded head pose supplied by Android on an independently polled lane. */
    public sealed class PerceptionHeadPoseSnapshot
    {
        public long Sequence;
        public long SessionGeneration;
        public long TimestampNs;
        public int Accuracy;
        public float W;
        public float X;
        public float Y;
        public float Z;
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
        private const uint FocusMagic = 0x43464653; // CFFS
        private const uint HeadPoseMagic = 0x43464850; // CFHP
        private const ushort Version = 1;
        private const ushort FocusVersion = 2;
        private const int MaximumEntities = 64;
        private const int MaximumStringBytes = 128;
        private const long MaximumBeaconReferenceAgeNs = 250_000_000L;

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
                    float norm=candidate.HeadW*candidate.HeadW+candidate.HeadX*candidate.HeadX+
                        candidate.HeadY*candidate.HeadY+candidate.HeadZ*candidate.HeadZ;
                    if(candidate.HeadTimestampNs<0 || candidate.HeadAccuracy<1 || candidate.HeadAccuracy>3 ||
                       !IsFinite(candidate.HeadW) || !IsFinite(candidate.HeadX) || !IsFinite(candidate.HeadY) ||
                       !IsFinite(candidate.HeadZ) || Math.Abs(norm-1f)>.04f) return false;
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
                    if(string.IsNullOrWhiteSpace(entity.TrackId) || string.IsNullOrWhiteSpace(entity.ClassId) ||
                       entity.SourceFrameId<=0 || entity.SourceCaptureTimestampNs<0 ||
                       entity.OutputTimestampNs<entity.SourceCaptureTimestampNs ||
                       !IsFinite(entity.Confidence) || entity.Confidence<0f || entity.Confidence>1f ||
                       !IsFinite(entity.DistanceMeters) || entity.DistanceMeters<=0f ||
                       !IsFinite(entity.UncertaintyMeters) || (entity.HasUncertainty&&entity.UncertaintyMeters<0f) ||
                       !IsFinite(entity.X) || !IsFinite(entity.Y) || !IsFinite(entity.Z)) return false;
                    candidate.Entities.Add(entity);
                }
                if(!input.AtEnd || candidate.Revision<=0 || candidate.SessionGeneration<0 || candidate.SourceFrameId<0 ||
                   candidate.SourceCaptureTimestampNs<0 || candidate.PublishedTimestampNs<0 ||
                   candidate.ValidUntilTimestampNs<candidate.PublishedTimestampNs) return false;
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

        public static bool TryDecodeFocus(byte[] bytes, out PerceptionFocusSnapshot state)
        {
            state=null;
            if(bytes==null) return false;
            try
            {
                var input=new BigEndianReader(bytes);
                if(input.ReadUInt32()!=FocusMagic) return false;
                ushort version=input.ReadUInt16();
                if(version!=Version && version!=FocusVersion) return false;
                ushort flags=input.ReadUInt16();
                if((flags&~(version==Version?1:3))!=0) return false;
                var candidate=new PerceptionFocusSnapshot
                {
                    HasFocus=(flags&1)!=0,
                    Revision=input.ReadInt64(),
                    SessionGeneration=input.ReadInt64(),
                    WorldRevision=input.ReadInt64(),
                    UpdatedTimestampNs=input.ReadInt64(),
                    ValidUntilTimestampNs=input.ReadInt64(),
                    FocusedTrackId=input.ReadString(MaximumStringBytes),
                };
                if(version==Version)
                {
                    candidate.Mode=candidate.HasFocus?PerceptionFocusMode.Browsing:PerceptionFocusMode.Inactive;
                }
                else
                {
                    candidate.Mode=(PerceptionFocusMode)input.ReadByte();
                    var anchorMode=(PerceptionBeaconAnchorMode)input.ReadByte();
                    ushort beaconFlags=input.ReadUInt16();
                    if(!Enum.IsDefined(typeof(PerceptionFocusMode),candidate.Mode) ||
                       !Enum.IsDefined(typeof(PerceptionBeaconAnchorMode),anchorMode) ||
                       (beaconFlags&~3)!=0) return false;
                    bool hasBeacon=(flags&2)!=0;
                    if(hasBeacon)
                    {
                        var beacon=new PerceptionBeaconSnapshot
                        {
                            TrackId=input.ReadString(MaximumStringBytes),
                            ClassId=input.ReadString(MaximumStringBytes),
                            AnchorMode=anchorMode,
                            ActivationId=input.ReadInt64(),
                            ActivatedTimestampNs=input.ReadInt64(),
                            ValidUntilTimestampNs=input.ReadInt64(),
                            SourceFrameId=input.ReadInt64(),
                            SourceCaptureTimestampNs=input.ReadInt64(),
                            Confidence=input.ReadSingle(),
                            DistanceMeters=input.ReadSingle(),
                            HasDistanceUncertainty=(beaconFlags&1)!=0,
                            DistanceUncertaintyMeters=input.ReadSingle(),
                            X=input.ReadSingle(), Y=input.ReadSingle(), Z=input.ReadSingle(),
                            HasReferenceHeadOrientation=(beaconFlags&2)!=0,
                        };
                        if(beacon.HasReferenceHeadOrientation)
                        {
                            beacon.ReferenceHeadTimestampNs=input.ReadInt64();
                            beacon.ReferenceHeadAccuracy=input.ReadInt32();
                            beacon.ReferenceHeadW=input.ReadSingle(); beacon.ReferenceHeadX=input.ReadSingle();
                            beacon.ReferenceHeadY=input.ReadSingle(); beacon.ReferenceHeadZ=input.ReadSingle();
                        }
                        if(!ValidBeacon(beacon) || beacon.TrackId!=candidate.FocusedTrackId ||
                           beacon.ActivatedTimestampNs>candidate.UpdatedTimestampNs ||
                           beacon.ValidUntilTimestampNs!=candidate.ValidUntilTimestampNs) return false;
                        candidate.Beacon=beacon;
                    }
                    else if(anchorMode!=PerceptionBeaconAnchorMode.None || beaconFlags!=0) return false;
                    if((candidate.Mode==PerceptionFocusMode.BeaconActive)!=hasBeacon) return false;
                }
                if(!input.AtEnd || candidate.Revision<=0 || candidate.SessionGeneration<0 ||
                   candidate.WorldRevision<=0 || candidate.UpdatedTimestampNs<0 ||
                   candidate.ValidUntilTimestampNs<candidate.UpdatedTimestampNs ||
                   candidate.HasFocus!=!string.IsNullOrEmpty(candidate.FocusedTrackId)) return false;
                state=candidate; return true;
            }
            catch(Exception error) when(error is IndexOutOfRangeException || error is ArgumentException || error is DecoderFallbackException)
            { return false; }
        }

        private static bool ValidBeacon(PerceptionBeaconSnapshot value)
        {
            if(value==null || value.AnchorMode==PerceptionBeaconAnchorMode.None ||
               string.IsNullOrWhiteSpace(value.TrackId) || string.IsNullOrWhiteSpace(value.ClassId) ||
               value.ActivationId<=0 || value.ActivatedTimestampNs<0 ||
               value.ValidUntilTimestampNs<=value.ActivatedTimestampNs || value.SourceFrameId<=0 ||
               value.SourceCaptureTimestampNs<0 ||
               value.SourceCaptureTimestampNs>value.ActivatedTimestampNs ||
               !IsFinite(value.Confidence) || value.Confidence<0f ||
               value.Confidence>1f || !IsFinite(value.DistanceMeters) || value.DistanceMeters<=0f ||
               !IsFinite(value.DistanceUncertaintyMeters) ||
               (value.HasDistanceUncertainty&&value.DistanceUncertaintyMeters<0f) ||
               !IsFinite(value.X) || !IsFinite(value.Y) || !IsFinite(value.Z)) return false;
            bool relative=value.AnchorMode==PerceptionBeaconAnchorMode.OrientationStabilizedRelative;
            if(relative!=value.HasReferenceHeadOrientation) return false;
            if(!relative) return true;
            float norm=value.ReferenceHeadW*value.ReferenceHeadW+value.ReferenceHeadX*value.ReferenceHeadX+
                value.ReferenceHeadY*value.ReferenceHeadY+value.ReferenceHeadZ*value.ReferenceHeadZ;
            return value.ReferenceHeadTimestampNs>=0 &&
                value.ReferenceHeadTimestampNs<=value.ActivatedTimestampNs &&
                value.ActivatedTimestampNs-value.ReferenceHeadTimestampNs<=MaximumBeaconReferenceAgeNs &&
                value.ReferenceHeadAccuracy>=1 &&
                value.ReferenceHeadAccuracy<=3 && IsFinite(value.ReferenceHeadW) &&
                IsFinite(value.ReferenceHeadX) && IsFinite(value.ReferenceHeadY) &&
                IsFinite(value.ReferenceHeadZ) && Math.Abs(norm-1f)<=.04f;
        }

        public static bool TryDecodeHeadPose(byte[] bytes, out PerceptionHeadPoseSnapshot state)
        {
            state=null;
            if(bytes==null) return false;
            try
            {
                var input=new BigEndianReader(bytes);
                if(input.ReadUInt32()!=HeadPoseMagic || input.ReadUInt16()!=Version || input.ReadUInt16()!=0) return false;
                var candidate=new PerceptionHeadPoseSnapshot
                {
                    Sequence=input.ReadInt64(),
                    SessionGeneration=input.ReadInt64(),
                    TimestampNs=input.ReadInt64(),
                    Accuracy=input.ReadInt32(),
                    W=input.ReadSingle(), X=input.ReadSingle(), Y=input.ReadSingle(), Z=input.ReadSingle(),
                };
                float magnitudeSquared=candidate.W*candidate.W+candidate.X*candidate.X+
                    candidate.Y*candidate.Y+candidate.Z*candidate.Z;
                if(!input.AtEnd || candidate.Sequence<=0 || candidate.SessionGeneration<0 ||
                   candidate.TimestampNs<0 || candidate.Accuracy<1 || candidate.Accuracy>3 ||
                   !IsFinite(candidate.W) || !IsFinite(candidate.X) || !IsFinite(candidate.Y) ||
                   !IsFinite(candidate.Z) || Math.Abs(magnitudeSquared-1f)>.04f) return false;
                state=candidate; return true;
            }
            catch(Exception error) when(error is IndexOutOfRangeException || error is ArgumentException)
            { return false; }
        }

        private static bool IsFinite(float value) => !float.IsNaN(value) && !float.IsInfinity(value);

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

    public interface IPerceptionSnapshotSource : IDisposable
    {
        bool TryPoll(out PerceptionWorldSnapshot state);
        bool TryPollFocus(out PerceptionFocusSnapshot state);
        bool TryPollHeadPose(out PerceptionHeadPoseSnapshot state);
        bool TryGetMonotonicTimestampNs(out long timestampNs);
        bool DrainTouch(int maximum,List<PerceptionTouchSnapshot> destination);
    }

    /** Polls only Java-side cached state; Binder/network work stays on the Java HandlerThread. */
    public sealed class AndroidPerceptionBridgeClient : IPerceptionSnapshotSource
    {
        private long lastRevision;
        private long lastFocusRevision;
        private long lastHeadSequence;
#if UNITY_ANDROID && !UNITY_EDITOR
        private readonly UnityEngine.AndroidJavaClass bridge = new("org.conceptflow.mpl.host.realtime.AndroidPerceptionBridge");
#endif
        public AndroidPerceptionBridgeClient()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            bridge.CallStatic("initialize");
#endif
        }
        public bool TryPoll(out PerceptionWorldSnapshot state)
        {
            state=null;
#if UNITY_ANDROID && !UNITY_EDITOR
            byte[] bytes=Unsigned(bridge.CallStatic<sbyte[]>("pollWorldState",lastRevision));
            if(!PerceptionBusBinaryDecoder.TryDecodeWorld(bytes,out state)) return false;
            lastRevision=state.Revision; return true;
#else
            return false;
#endif
        }
        public bool DrainTouch(int maximum,List<PerceptionTouchSnapshot> destination)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            byte[] bytes=Unsigned(bridge.CallStatic<sbyte[]>("drainTouchEvents",Math.Max(1,Math.Min(128,maximum))));
            return PerceptionBusBinaryDecoder.TryDecodeTouchBatch(bytes,destination);
#else
            destination?.Clear(); return false;
#endif
        }
        public bool TryPollFocus(out PerceptionFocusSnapshot state)
        {
            state=null;
#if UNITY_ANDROID && !UNITY_EDITOR
            byte[] bytes=Unsigned(bridge.CallStatic<sbyte[]>("pollFocusState",lastFocusRevision));
            if(!PerceptionBusBinaryDecoder.TryDecodeFocus(bytes,out state)) return false;
            lastFocusRevision=state.Revision; return true;
#else
            return false;
#endif
        }
        public bool TryPollHeadPose(out PerceptionHeadPoseSnapshot state)
        {
            state=null;
#if UNITY_ANDROID && !UNITY_EDITOR
            byte[] bytes=Unsigned(bridge.CallStatic<sbyte[]>("pollHeadPose",lastHeadSequence));
            if(!PerceptionBusBinaryDecoder.TryDecodeHeadPose(bytes,out state)) return false;
            lastHeadSequence=state.Sequence; return true;
#else
            return false;
#endif
        }
        public bool TryGetMonotonicTimestampNs(out long timestampNs)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            timestampNs=bridge.CallStatic<long>("elapsedRealtimeNanos");
            return timestampNs>=0L;
#else
            timestampNs=0L; return false;
#endif
        }
        public void Dispose()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            bridge.CallStatic("shutdown");
            bridge.Dispose();
#endif
        }

#if UNITY_ANDROID && !UNITY_EDITOR
        // Unity 6 maps Java byte[] to signed CLR bytes. Requesting byte[] still works but emits a
        // warning on every poll, which can flood logcat and perturb the real-time lab.
        private static byte[] Unsigned(sbyte[] source)
        {
            if(source==null) return null;
            var destination=new byte[source.Length];
            Buffer.BlockCopy(source,0,destination,0,source.Length);
            return destination;
        }
#endif
    }
}
